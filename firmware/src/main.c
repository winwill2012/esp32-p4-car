#include <time.h>
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif_sntp.h"
#include "iot_usbh_cdc.h"
#include "iot_usbh_modem.h"
#include "nvs_flash.h"
#include "config.local.h"
#include "intercom.h"

#define SIGNAL_URI "wss://" INTERCOM_HOST "/p4-intercom/rtc/device"
#define CELLULAR_READY BIT0

static EventGroupHandle_t cellular_events;
static const char *TAG = "intercom";

static const usb_modem_id_t modem_ids[] = {
    {
        .match_id = {USB_DEVICE_ID_MATCH_VID_PID, 0x19d1, 0x0001},
        .modem_itf_num = 2, .at_itf_num = -1, .name = "Luat Air780E",
    },
    {.match_id = {0}},
};

static void cellular_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (id == IP_EVENT_PPP_GOT_IP) {
        const ip_event_got_ip_t *event = data;
        ESP_LOGI(TAG, "Air780E 4G PPP connected: " IPSTR, IP2STR(&event->ip_info.ip));
        xEventGroupSetBits(cellular_events, CELLULAR_READY);
    } else if (id == IP_EVENT_PPP_LOST_IP) {
        xEventGroupClearBits(cellular_events, CELLULAR_READY);
        ESP_LOGW(TAG, "Air780E 4G PPP disconnected");
    }
}

static void cellular_start(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);
    cellular_events = xEventGroupCreate();
    assert(cellular_events);
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_PPP_GOT_IP, cellular_event, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_PPP_LOST_IP, cellular_event, NULL));

    const usbh_cdc_driver_config_t usb = {
        .task_stack_size = 4096,
        .task_priority = configMAX_PRIORITIES - 1,
        .task_coreid = 0,
    };
    ESP_ERROR_CHECK(usbh_cdc_driver_install(&usb));
    const usbh_modem_config_t modem = {
        .modem_id_list = modem_ids,
        .at_tx_buffer_size = 256,
        .at_rx_buffer_size = 256,
    };
    ESP_ERROR_CHECK(usbh_modem_install(&modem));
    ESP_LOGI(TAG, "Waiting for Air780E on USB-A");
}

void app_main(void)
{
    bool steering_ready = steering_setup() == ESP_OK;
    cellular_start();
    const i2c_master_bus_config_t i2c = {
        .i2c_port = I2C_NUM_1, .sda_io_num = GPIO_NUM_28, .scl_io_num = GPIO_NUM_29,
        .clk_source = I2C_CLK_SRC_DEFAULT, .glitch_ignore_cnt = 7,
        .flags.enable_internal_pullup = true,
    };
    i2c_master_bus_handle_t bus;
    ESP_ERROR_CHECK(i2c_new_master_bus(&i2c, &bus));
    bool audio_ready = audio_setup(bus) == ESP_OK;
    bool camera_ready = camera_setup(bus) == ESP_OK;
    ESP_LOGI(TAG, "Camera %s, audio %s", camera_ready ? "ready" : "failed", audio_ready ? "ready" : "failed");
    xEventGroupWaitBits(cellular_events, CELLULAR_READY, pdFALSE, pdTRUE, portMAX_DELAY);

    esp_sntp_config_t time_config = ESP_NETIF_SNTP_DEFAULT_CONFIG_MULTIPLE(
        2, ESP_SNTP_SERVER_LIST("ntp.tencent.com", "ntp.aliyun.com")
    );
    ESP_ERROR_CHECK(esp_netif_sntp_init(&time_config));
    while (time(NULL) < 1735689600) {
        if (esp_netif_sntp_sync_wait(pdMS_TO_TICKS(12000)) != ESP_OK) ESP_LOGW(TAG, "Waiting for network time");
    }
    ESP_LOGI(TAG, "System time synchronized");

    if (camera_ready) camera_start();
    if (audio_ready) audio_start();
    if (camera_ready || audio_ready) {
        ESP_ERROR_CHECK(rtc_start(SIGNAL_URI, "Authorization: Bearer " DEVICE_KEY "\r\n",
                                  camera_ready, audio_ready, steering_ready));
    }
}
