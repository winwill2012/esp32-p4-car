#include "driver/ledc.h"
#include "esp_check.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "intercom.h"

#define STEERING_GPIO 21
#define SERVO_HZ 50
#define SERVO_BITS 14
#define SERVO_CENTER_US 1500
#define SERVO_TRAVEL_US 350

typedef struct {
    int position;
    int64_t expires_at;
} steering_command_t;

static const char *TAG = "steering";
static QueueHandle_t commands;

static uint32_t servo_duty(int pulse_us)
{
    return ((uint32_t)pulse_us * (1U << SERVO_BITS) + 1000000 / SERVO_HZ / 2) /
           (1000000 / SERVO_HZ);
}

static void steering_task(void *arg)
{
    int applied = 0;
    steering_command_t target = {0};
    while (true) {
        xQueueReceive(commands, &target, pdMS_TO_TICKS(20));
        int position = target.expires_at > esp_timer_get_time() ? target.position : 0;
        if (position == applied) continue;

        // This linkage steers right as the pulse gets shorter.
        int pulse_us = SERVO_CENTER_US - position * SERVO_TRAVEL_US / 100;
        esp_err_t result = ledc_set_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0, servo_duty(pulse_us));
        if (result == ESP_OK) result = ledc_update_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0);
        if (result != ESP_OK) {
            ESP_LOGE(TAG, "PWM update failed: %s", esp_err_to_name(result));
            continue;
        }
        if (applied == 0 || position == 0) {
            ESP_LOGI(TAG, "GPIO%d pulse %d us (%s)", STEERING_GPIO, pulse_us,
                     position ? "turn" : (target.position ? "timeout" : "center"));
        }
        applied = position;
    }
}

esp_err_t steering_setup(void)
{
    const ledc_timer_config_t timer = {
        .speed_mode = LEDC_LOW_SPEED_MODE, .timer_num = LEDC_TIMER_0,
        .duty_resolution = LEDC_TIMER_14_BIT, .freq_hz = SERVO_HZ, .clk_cfg = LEDC_AUTO_CLK,
    };
    const ledc_channel_config_t channel = {
        .gpio_num = STEERING_GPIO, .speed_mode = LEDC_LOW_SPEED_MODE, .channel = LEDC_CHANNEL_0,
        .timer_sel = LEDC_TIMER_0, .duty = servo_duty(SERVO_CENTER_US),
    };
    ESP_RETURN_ON_ERROR(ledc_timer_config(&timer), TAG, "Configure steering timer");
    ESP_RETURN_ON_ERROR(ledc_channel_config(&channel), TAG, "Configure steering channel");
    commands = xQueueCreate(1, sizeof(steering_command_t));
    if (!commands) return ESP_ERR_NO_MEM;
    if (xTaskCreate(steering_task, "steering", 3072, NULL, 5, NULL) != pdPASS) {
        vQueueDelete(commands);
        commands = NULL;
        return ESP_ERR_NO_MEM;
    }
    ESP_LOGI(TAG, "GPIO%d steering PWM ready at %d Hz, center %d us", STEERING_GPIO, SERVO_HZ, SERVO_CENTER_US);
    return ESP_OK;
}

void steering_set(int position, uint32_t valid_ms)
{
    steering_command_t command = {
        .position = position,
        .expires_at = esp_timer_get_time() + (int64_t)valid_ms * 1000,
    };
    xQueueOverwrite(commands, &command);
}
