#include <math.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/idf_additions.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "driver/i2s_std.h"
#include "esp_aec.h"
#include "esp_attr.h"
#include "esp_check.h"
#include "esp_codec_dev.h"
#include "esp_codec_dev_defaults.h"
#include "esp_heap_caps.h"
#include "esp_timer.h"
#include "intercom.h"
#include "opus.h"

#define MAX_AEC_SAMPLES 512
#define REFERENCE_LEAD_US 2500
#define MIC_GAIN_DB 30
#define SPEECH_RMS_TARGET 3200.0f
#define QUIET_RMS 110.0f
#define MAX_SPEECH_GAIN 4.0f
#define AUDIO_PACKET_INTERVAL_US (AUDIO_SAMPLES * 1000000 / SAMPLE_RATE)

typedef struct {
    int64_t completed_at;
    _Alignas(16) int16_t samples[MAX_AEC_SAMPLES];
} captured_audio_t;

typedef struct {
    QueueHandle_t queue;
    captured_audio_t frame;
} dma_audio_t;

typedef struct {
    audio_packet_t pcm;
    int64_t timestamp;
    unsigned generation;
} uplink_audio_t;

static const char *TAG = "audio";
static i2s_chan_handle_t transmit, receive;
static QueueHandle_t playback, uplink;
static TaskHandle_t uplink_task_handle;
static esp_timer_handle_t uplink_timer;
static atomic_bool enabled;
static atomic_uint audio_generation;
static OpusEncoder *opus_encoder;
static OpusDecoder *opus_decoder;
static aec_handle_t *echo_canceller;
static size_t aec_samples;
static DRAM_ATTR dma_audio_t mic_dma, speaker_dma;

static void notify_audio_tick(void *arg)
{
    xTaskNotifyGive(uplink_task_handle);
}

static bool IRAM_ATTR on_audio_dma(i2s_chan_handle_t channel, i2s_event_data_t *event, void *context)
{
    dma_audio_t *source = context;
    if (event->size != aec_samples * 2 * sizeof(int16_t)) return false;
    source->frame.completed_at = esp_timer_get_time();
    const int16_t *samples = event->dma_buf;
    for (size_t i = 0; i < aec_samples; ++i) source->frame.samples[i] = samples[i * 2];
    BaseType_t waking = pdFALSE;
    xQueueSendFromISR(source->queue, &source->frame, &waking);
    return waking == pdTRUE;
}

static esp_err_t setup_echo_canceller(void)
{
    aec_config_t aec_config = {
        .mic_num = 1, .ref_num = 1, .out_num = 1, .filter_length = 4,
        .sample_rate = SAMPLE_RATE, .caps = MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT,
        .mode = AEC_MODE_FD_LOW_COST, .nlp_level = AEC_NLP_LEVEL_NORMAL,
    };
    echo_canceller = aec_create_from_config(&aec_config);
    ESP_RETURN_ON_FALSE(echo_canceller, ESP_FAIL, TAG, "Initialize full-duplex echo cancellation");
    int samples = aec_get_chunksize(echo_canceller);
    ESP_RETURN_ON_FALSE(samples > 0 && samples <= MAX_AEC_SAMPLES, ESP_FAIL, TAG,
                        "Unsupported AEC block length: %d", samples);
    aec_samples = samples;
    mic_dma.queue = xQueueCreate(6, sizeof(captured_audio_t));
    speaker_dma.queue = xQueueCreate(6, sizeof(captured_audio_t));
    ESP_RETURN_ON_FALSE(mic_dma.queue && speaker_dma.queue, ESP_ERR_NO_MEM, TAG, "Allocate microphone and AEC reference queues");
    return ESP_OK;
}

esp_err_t audio_setup(i2c_master_bus_handle_t bus)
{
    ESP_RETURN_ON_ERROR(setup_echo_canceller(), TAG, "Configure echo cancellation");
    i2s_chan_config_t channels = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    channels.dma_desc_num = 4;
    channels.dma_frame_num = aec_samples;
    channels.auto_clear_after_cb = true;
    ESP_RETURN_ON_ERROR(i2s_new_channel(&channels, &transmit, &receive), TAG, "Create audio channels");
    i2s_std_config_t format = {
        .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(SAMPLE_RATE),
        .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO),
        .gpio_cfg = { .mclk = GPIO_NUM_2, .bclk = GPIO_NUM_4, .ws = GPIO_NUM_6,
                      .dout = GPIO_NUM_3, .din = GPIO_NUM_5 },
    };
    format.clk_cfg.mclk_multiple = I2S_MCLK_MULTIPLE_384;
    ESP_RETURN_ON_ERROR(i2s_channel_init_std_mode(transmit, &format), TAG, "Initialize speaker I2S");
    ESP_RETURN_ON_ERROR(i2s_channel_init_std_mode(receive, &format), TAG, "Initialize microphone I2S");
    const i2s_event_callbacks_t tx_callbacks = { .on_sent = on_audio_dma };
    const i2s_event_callbacks_t rx_callbacks = { .on_recv = on_audio_dma };
    ESP_RETURN_ON_ERROR(i2s_channel_register_event_callback(transmit, &tx_callbacks, &speaker_dma), TAG, "Track played PCM");
    ESP_RETURN_ON_ERROR(i2s_channel_register_event_callback(receive, &rx_callbacks, &mic_dma), TAG, "Track captured PCM");
    ESP_RETURN_ON_ERROR(i2s_channel_enable(transmit), TAG, "Enable speaker I2S");
    ESP_RETURN_ON_ERROR(i2s_channel_enable(receive), TAG, "Enable microphone I2S");

    audio_codec_i2c_cfg_t control = {
        .port = I2C_NUM_1, .addr = ES8311_CODEC_DEFAULT_ADDR, .bus_handle = bus,
    };
    audio_codec_i2s_cfg_t data = { .port = I2S_NUM_0, .rx_handle = receive, .tx_handle = transmit };
    const audio_codec_data_if_t *data_if = audio_codec_new_i2s_data(&data);
    es8311_codec_cfg_t chip = {
        .ctrl_if = audio_codec_new_i2c_ctrl(&control),
        .gpio_if = audio_codec_new_gpio(),
        .codec_mode = ESP_CODEC_DEV_WORK_MODE_BOTH,
        .use_mclk = true,
        .pa_pin = GPIO_NUM_11,
        .hw_gain = { .pa_voltage = 5.0, .codec_dac_voltage = 3.3 },
        .mclk_div = 384,
    };
    ESP_RETURN_ON_FALSE(data_if && chip.ctrl_if && chip.gpio_if, ESP_ERR_NO_MEM, TAG, "Allocate codec interfaces");
    const audio_codec_if_t *codec_if = es8311_codec_new(&chip);
    ESP_RETURN_ON_FALSE(codec_if, ESP_FAIL, TAG, "Initialize ES8311");
    esp_codec_dev_cfg_t config = {
        .dev_type = ESP_CODEC_DEV_TYPE_IN_OUT, .codec_if = codec_if, .data_if = data_if,
    };
    esp_codec_dev_handle_t codec = esp_codec_dev_new(&config);
    ESP_RETURN_ON_FALSE(codec, ESP_FAIL, TAG, "Create audio device");
    esp_codec_dev_sample_info_t samples = {
        .bits_per_sample = 16, .channel = 2, .channel_mask = 3, .sample_rate = SAMPLE_RATE, .mclk_multiple = 384,
    };
    ESP_RETURN_ON_FALSE(esp_codec_dev_open(codec, &samples) == ESP_CODEC_DEV_OK, ESP_FAIL, TAG, "Open audio device");
    ESP_RETURN_ON_FALSE(esp_codec_dev_set_out_vol(codec, 100) == ESP_CODEC_DEV_OK, ESP_FAIL, TAG, "Set maximum speaker volume");
    ESP_RETURN_ON_FALSE(esp_codec_dev_set_in_gain(codec, MIC_GAIN_DB) == ESP_CODEC_DEV_OK, ESP_FAIL, TAG, "Set microphone gain");
    ESP_LOGI(TAG, "Microphone gain %d dB, full-duplex AEC block %d", MIC_GAIN_DB, (int)aec_samples);

    int opus_error;
    opus_encoder = opus_encoder_create(SAMPLE_RATE, 1, OPUS_APPLICATION_VOIP, &opus_error);
    ESP_RETURN_ON_FALSE(opus_encoder, ESP_FAIL, TAG, "Open microphone Opus encoder: %d", opus_error);
    ESP_RETURN_ON_FALSE(opus_encoder_ctl(opus_encoder, OPUS_SET_BITRATE(24000)) == OPUS_OK &&
                        opus_encoder_ctl(opus_encoder, OPUS_SET_COMPLEXITY(3)) == OPUS_OK,
                        ESP_FAIL, TAG, "Configure microphone Opus encoder");
    opus_decoder = opus_decoder_create(SAMPLE_RATE, 1, &opus_error);
    ESP_RETURN_ON_FALSE(opus_decoder, ESP_FAIL, TAG, "Open speaker Opus decoder: %d", opus_error);
    playback = xQueueCreate(5, sizeof(audio_packet_t));
    uplink = xQueueCreate(6, sizeof(uplink_audio_t));
    ESP_RETURN_ON_FALSE(playback && uplink, ESP_ERR_NO_MEM, TAG, "Allocate audio queues");
    const esp_timer_create_args_t timer = {
        .callback = notify_audio_tick, .name = "opus_pacing", .skip_unhandled_events = true,
    };
    ESP_RETURN_ON_ERROR(esp_timer_create(&timer, &uplink_timer), TAG, "Create Opus pacing timer");
    return ESP_OK;
}

void audio_enable(bool active)
{
    if (active) {
        atomic_store(&enabled, true);
        esp_err_t result = esp_timer_start_periodic(uplink_timer, AUDIO_PACKET_INTERVAL_US);
        if (result != ESP_OK && result != ESP_ERR_INVALID_STATE) {
            ESP_LOGE(TAG, "Start Opus pacing: %s", esp_err_to_name(result));
        }
    } else {
        atomic_store(&enabled, false);
        atomic_fetch_add(&audio_generation, 1);
        esp_timer_stop(uplink_timer);
        xQueueReset(playback);
        xQueueReset(uplink);
    }
}

static void enqueue_audio(const audio_packet_t *packet)
{
    if (!atomic_load(&enabled)) return;
    if (xQueueSend(playback, packet, 0) != pdPASS) {
        audio_packet_t expired;
        xQueueReceive(playback, &expired, 0);
        xQueueSend(playback, packet, 0);
    }
}

void audio_receive_opus(uint8_t *data, size_t size)
{
    if (!atomic_load(&enabled)) return;
    int16_t pcm[AUDIO_SAMPLES * 6];
    int samples = opus_decode(opus_decoder, data, size, pcm, AUDIO_SAMPLES * 6, 0);
    if (samples <= 0) return;
    size_t remaining = samples;
    for (size_t offset = 0; remaining > 0; offset += AUDIO_SAMPLES) {
        audio_packet_t packet = { 0 };
        size_t count = remaining < AUDIO_SAMPLES ? remaining : AUDIO_SAMPLES;
        memcpy(packet.samples, pcm + offset, count * sizeof(*pcm));
        enqueue_audio(&packet);
        remaining -= count;
    }
}

static void publish_speech(audio_packet_t *pcm, float *gain, int64_t timestamp)
{
    uint8_t encoded[AUDIO_PACKET_CAPACITY];
    uint64_t energy = 0;
    int peak = 1;
    for (size_t i = 0; i < AUDIO_SAMPLES; ++i) {
        int sample = pcm->samples[i];
        energy += sample * sample;
        if (abs(sample) > peak) peak = abs(sample);
    }
    float rms = sqrtf((float)energy / AUDIO_SAMPLES);
    float desired = rms >= QUIET_RMS ? fminf(MAX_SPEECH_GAIN, SPEECH_RMS_TARGET / rms) : 1.0f;
    *gain += (desired - *gain) * (desired < *gain ? 0.55f : 0.14f);
    const float limited = fminf(*gain, 29000.0f / peak);
    for (size_t i = 0; i < AUDIO_SAMPLES; ++i) pcm->samples[i] = (int16_t)(pcm->samples[i] * limited);
    int size = opus_encode(opus_encoder, pcm->samples, AUDIO_SAMPLES, encoded, sizeof(encoded));
    if (size > 0) rtc_publish_audio(encoded, size, (uint32_t)(timestamp / 1000));
}

static void uplink_task(void *arg)
{
    bool primed = false;
    unsigned last_generation = 0;
    float gain = 1.0f;
    while (true) {
        ulTaskNotifyTake(pdFALSE, portMAX_DELAY);
        unsigned generation = atomic_load(&audio_generation);
        if (generation != last_generation) {
            last_generation = generation;
            primed = false;
            gain = 1.0f;
        }
        if (!atomic_load(&enabled)) continue;
        // Two packets cover the uneven 32 ms AEC output without building a long speech queue.
        if (!primed && uxQueueMessagesWaiting(uplink) < 2) continue;
        uplink_audio_t packet;
        primed = xQueueReceive(uplink, &packet, 0) == pdTRUE;
        if (primed && packet.generation == generation && atomic_load(&enabled)) {
            publish_speech(&packet.pcm, &gain, packet.timestamp);
        }
    }
}

static void capture_task(void *arg)
{
    captured_audio_t mic, reference = {0};
    _Alignas(16) int16_t filtered[MAX_AEC_SAMPLES], silence[MAX_AEC_SAMPLES] = {0};
    const int64_t block_us = aec_samples * 1000000 / SAMPLE_RATE;
    const TickType_t reference_wait = pdMS_TO_TICKS((block_us + REFERENCE_LEAD_US + 9000) / 1000);
    int64_t last_capture = 0, timestamp = 0;
    uplink_audio_t packet;
    size_t filled = 0;
    unsigned last_generation = 0;
    while (true) {
        xQueueReceive(mic_dma.queue, &mic, portMAX_DELAY);
        if (!atomic_load(&enabled)) {
            while (xQueueReceive(speaker_dma.queue, &reference, 0) == pdPASS) {}
            last_capture = 0;
            continue;
        }
        unsigned generation = atomic_load(&audio_generation);
        if (generation != last_generation || mic.completed_at - last_capture > block_us * 3 / 2) {
            last_generation = generation;
            filled = 0;
            timestamp = mic.completed_at - block_us;
        }
        last_capture = mic.completed_at;

        // A reference block slightly ahead of capture lets the filter model a causal echo path.
        const int64_t target = mic.completed_at + REFERENCE_LEAD_US;
        const int64_t deadline = target + block_us + 4500;
        while (reference.completed_at < target && esp_timer_get_time() < deadline) {
            if (xQueueReceive(speaker_dma.queue, &reference, reference_wait) != pdPASS) break;
        }
        bool matched = reference.completed_at >= target && reference.completed_at <= deadline;
        aec_process(echo_canceller, mic.samples, matched ? reference.samples : silence, filtered);

        for (size_t offset = 0; offset < aec_samples;) {
            size_t count = AUDIO_SAMPLES - filled;
            if (count > aec_samples - offset) count = aec_samples - offset;
            memcpy(packet.pcm.samples + filled, filtered + offset, count * sizeof(int16_t));
            offset += count;
            filled += count;
            if (filled != AUDIO_SAMPLES) continue;
            packet.timestamp = timestamp;
            packet.generation = generation;
            if (xQueueSend(uplink, &packet, 0) != pdPASS) {
                uplink_audio_t oldest;
                xQueueReceive(uplink, &oldest, 0);
                xQueueSend(uplink, &packet, 0);
            }
            timestamp += AUDIO_PACKET_INTERVAL_US;
            filled = 0;
        }
    }
}

static void playback_task(void *arg)
{
    audio_packet_t mono;
    int16_t stereo[AUDIO_SAMPLES * 2];
    while (true) {
        if (xQueueReceive(playback, &mono, pdMS_TO_TICKS(18)) == pdPASS && atomic_load(&enabled)) {
            for (size_t i = 0; i < AUDIO_SAMPLES; ++i) {
                stereo[i * 2] = mono.samples[i];
                stereo[i * 2 + 1] = mono.samples[i];
            }
        } else {
            memset(stereo, 0, sizeof(stereo));
        }
        size_t length;
        i2s_channel_write(transmit, stereo, sizeof(stereo), &length, 100);
    }
}

void audio_start(void)
{
    // Keep the AEC and encoder off the core serving the USB modem and I2S interrupts.
    BaseType_t capture_created = xTaskCreatePinnedToCoreWithCaps(capture_task, "mic", 98304, NULL, 6, NULL,
                                                                1, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    BaseType_t playback_created = xTaskCreate(playback_task, "speaker", 4096, NULL, 6, NULL);
    BaseType_t uplink_created = xTaskCreatePinnedToCoreWithCaps(uplink_task, "mic_send", 98304, NULL, 7,
                                                               &uplink_task_handle, 1,
                                                               MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    assert(capture_created == pdPASS && playback_created == pdPASS && uplink_created == pdPASS);
}
