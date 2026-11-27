#include "driver/gpio.h"
#include "esp_check.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "intercom.h"

#define HEADLIGHT GPIO_NUM_7
#define REVERSE_LIGHT GPIO_NUM_8
#define LEFT_LIGHT GPIO_NUM_9
#define RIGHT_LIGHT GPIO_NUM_10
#define TURN_THRESHOLD 10
#define TURN_HALF_PERIOD_US 375000

static const char *TAG = "lights";
static SemaphoreHandle_t light_lock;
static struct {
    bool headlight;
    int direction;
    int64_t turn_until;
    int64_t reverse_until;
    int64_t blink_start;
} lights;

static void apply_locked(int64_t now)
{
    if (now >= lights.turn_until) lights.direction = 0;
    bool blink_on = lights.direction != 0 && ((now - lights.blink_start) / TURN_HALF_PERIOD_US) % 2 == 0;
    gpio_set_level(HEADLIGHT, !lights.headlight);
    gpio_set_level(REVERSE_LIGHT, now >= lights.reverse_until);
    gpio_set_level(LEFT_LIGHT, !(blink_on && lights.direction < 0));
    gpio_set_level(RIGHT_LIGHT, !(blink_on && lights.direction > 0));
}

static void refresh(void *arg)
{
    xSemaphoreTake(light_lock, portMAX_DELAY);
    apply_locked(esp_timer_get_time());
    xSemaphoreGive(light_lock);
}

esp_err_t lights_setup(void)
{
    const gpio_num_t outputs[] = {HEADLIGHT, REVERSE_LIGHT, LEFT_LIGHT, RIGHT_LIGHT};
    uint64_t mask = 0;
    for (size_t i = 0; i < sizeof(outputs) / sizeof(outputs[0]); ++i) {
        ESP_RETURN_ON_ERROR(gpio_set_level(outputs[i], 1), TAG, "Set initial lights off");
        mask |= 1ULL << outputs[i];
    }
    const gpio_config_t config = {
        .pin_bit_mask = mask, .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
    };
    ESP_RETURN_ON_ERROR(gpio_config(&config), TAG, "Configure light outputs");
    light_lock = xSemaphoreCreateMutex();
    if (!light_lock) return ESP_ERR_NO_MEM;
    const esp_timer_create_args_t timer_config = { .callback = refresh, .name = "car_lights" };
    esp_timer_handle_t timer;
    esp_err_t result = esp_timer_create(&timer_config, &timer);
    if (result == ESP_OK) {
        result = esp_timer_start_periodic(timer, 20000);
        if (result != ESP_OK) esp_timer_delete(timer);
    }
    if (result != ESP_OK) {
        vSemaphoreDelete(light_lock);
        return result;
    }
    ESP_LOGI(TAG, "Lights ready, active-low GPIO7 headlight, GPIO8 reverse, GPIO9 left, GPIO10 right");
    return ESP_OK;
}

void lights_turn(int position, uint32_t valid_ms)
{
    int direction = position <= -TURN_THRESHOLD ? -1 : position >= TURN_THRESHOLD ? 1 : 0;
    xSemaphoreTake(light_lock, portMAX_DELAY);
    int64_t now = esp_timer_get_time();
    if (direction != lights.direction || now >= lights.turn_until) lights.blink_start = now;
    lights.direction = direction;
    lights.turn_until = direction ? now + (int64_t)valid_ms * 1000 : 0;
    apply_locked(now);
    xSemaphoreGive(light_lock);
}

void lights_reverse(bool on, uint32_t valid_ms)
{
    xSemaphoreTake(light_lock, portMAX_DELAY);
    int64_t now = esp_timer_get_time();
    lights.reverse_until = on ? now + (int64_t)valid_ms * 1000 : 0;
    apply_locked(now);
    xSemaphoreGive(light_lock);
}

void lights_headlight(bool on)
{
    xSemaphoreTake(light_lock, portMAX_DELAY);
    lights.headlight = on;
    apply_locked(esp_timer_get_time());
    xSemaphoreGive(light_lock);
}

void lights_reset(void)
{
    xSemaphoreTake(light_lock, portMAX_DELAY);
    lights.headlight = false;
    lights.turn_until = 0;
    lights.reverse_until = 0;
    apply_locked(esp_timer_get_time());
    xSemaphoreGive(light_lock);
}
