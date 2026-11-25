#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include "freertos/FreeRTOS.h"
#include "freertos/idf_additions.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "esp_crt_bundle.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_peer.h"
#include "esp_peer_default.h"
#include "esp_websocket_client.h"
#include "intercom.h"

#define SIGNAL_CAPACITY 16384
#define SESSION_ID_SIZE 36
#define STEERING_WATCHDOG_MS 450
#define STEERING_CLOCK_TOLERANCE_MS 250

typedef struct {
    char *data;
    unsigned epoch;
} signal_message_t;

typedef struct {
    uint32_t pts;
    unsigned generation;
    size_t size;
} media_frame_t;

typedef struct {
    media_frame_t frame;
    uint8_t data[AUDIO_PACKET_CAPACITY];
} encoded_audio_t;

static const char *TAG = "rtc";
static esp_websocket_client_handle_t signaling;
static esp_peer_handle_t peer;
static QueueHandle_t command_queue, audio_queue, video_queue;
static SemaphoreHandle_t video_slot, media_send_lock;
static uint8_t *video_buffer;
static char signal_buffer[SIGNAL_CAPACITY + 1];
static char session[SESSION_ID_SIZE + 1];
static char turn_url[128], turn_user[96], turn_key[96];
static esp_peer_ice_server_cfg_t ice_server;
static bool supports_audio, supports_video, supports_steering;
static atomic_uint signaling_epoch, media_generation;
static atomic_bool media_active, peer_failed;

static void set_media(bool active)
{
    if (atomic_load(&media_active) != active) {
        if (!active) atomic_store(&media_active, false);
        atomic_fetch_add(&media_generation, 1);
        if (supports_audio) audio_enable(active);
        if (supports_video) camera_enable(active);
        if (!active && supports_steering) steering_set(0, 0);
        if (active) atomic_store(&media_active, true);
    }
}

void rtc_publish_audio(const uint8_t *data, size_t size, uint32_t pts)
{
    if (!atomic_load(&media_active) || !size || size > AUDIO_PACKET_CAPACITY) return;
    encoded_audio_t packet = {
        .frame = { .pts = pts, .generation = atomic_load(&media_generation), .size = size },
    };
    memcpy(packet.data, data, size);
    if (xQueueSend(audio_queue, &packet, 0) != pdPASS) {
        encoded_audio_t oldest;
        xQueueReceive(audio_queue, &oldest, 0);
        xQueueSend(audio_queue, &packet, 0);
    }
}

bool rtc_video_ready(void)
{
    return atomic_load(&media_active) && uxSemaphoreGetCount(video_slot) != 0;
}

void rtc_publish_video(const uint8_t *data, size_t size, uint32_t pts)
{
    if (!atomic_load(&media_active) || !size || size > VIDEO_PACKET_CAPACITY ||
        xSemaphoreTake(video_slot, 0) != pdTRUE) return;
    media_frame_t frame = { .pts = pts, .generation = atomic_load(&media_generation), .size = size };
    memcpy(video_buffer, data, size);
    if (xQueueSend(video_queue, &frame, 0) != pdPASS) xSemaphoreGive(video_slot);
}

static int send_signal(const char *kind, const uint8_t *payload, size_t size)
{
    if (!session[0] || !esp_websocket_client_is_connected(signaling)) return -1;
    char prefix[64];
    int prefix_size = snprintf(prefix, sizeof(prefix), "%s %s%s", kind, session, size ? "\n" : "");
    if (prefix_size < 0 || prefix_size >= sizeof(prefix) || size + prefix_size > SIGNAL_CAPACITY) return -1;
    char *message = malloc(prefix_size + size);
    if (!message) return -1;
    memcpy(message, prefix, prefix_size);
    if (size) memcpy(message + prefix_size, payload, size);
    int sent = esp_websocket_client_send_text(signaling, message, prefix_size + size, pdMS_TO_TICKS(800));
    free(message);
    return sent == prefix_size + size ? 0 : -1;
}

static int peer_message(esp_peer_msg_t *message, void *ctx)
{
    if (message->size <= 0 || !message->data) return 0;
    size_t size = message->size;
    if (message->data[size - 1] == 0) --size;
    if (message->type == ESP_PEER_MSG_TYPE_SDP) {
        ESP_LOGI(TAG, "Sending SDP offer, %u bytes", size);
        return send_signal("offer", message->data, size);
    }
    if (message->type == ESP_PEER_MSG_TYPE_CANDIDATE) return send_signal("candidate", message->data, size);
    return 0;
}

static int peer_state(esp_peer_state_t state, void *ctx)
{
    if (state <= ESP_PEER_STATE_CONNECT_FAILED) ESP_LOGI(TAG, "Peer state %d", state);
    if (state == ESP_PEER_STATE_CONNECTED) {
        set_media(true);
        if (supports_steering) send_signal("steering-ready", NULL, 0);
    } else if (state == ESP_PEER_STATE_VIDEO_PLI_RECEIVED && supports_video) {
        camera_request_keyframe();
    } else if (state == ESP_PEER_STATE_DISCONNECTED || state == ESP_PEER_STATE_CONNECT_FAILED) {
        set_media(false);
        atomic_store(&peer_failed, true);
    }
    return 0;
}

static int peer_audio(esp_peer_audio_frame_t *frame, void *ctx)
{
    if (supports_audio && frame->size > 0) audio_receive_opus(frame->data, frame->size);
    return 0;
}

static void close_peer(void)
{
    set_media(false);
    xSemaphoreTake(media_send_lock, portMAX_DELAY);
    if (peer) {
        esp_peer_close(peer);
        peer = NULL;
    }
    xSemaphoreGive(media_send_lock);
    atomic_store(&peer_failed, false);
    session[0] = '\0';
}

static int open_peer(void)
{
    ice_server = (esp_peer_ice_server_cfg_t) {
        .stun_url = turn_url, .user = turn_user, .psw = turn_key,
    };
    esp_peer_default_cfg_t options = {
        .agent_recv_timeout = 500, .keep_role = true, .alive_binding_retries = 3,
    };
    esp_peer_cfg_t config = {
        .server_lists = &ice_server, .server_num = 1,
        .role = ESP_PEER_ROLE_CONTROLLING, .ice_trans_policy = ESP_PEER_ICE_TRANS_POLICY_ALL,
        .audio_info = { .codec = ESP_PEER_AUDIO_CODEC_OPUS, .sample_rate = 48000, .channel = 2 },
        .video_info = {
            .codec = ESP_PEER_VIDEO_CODEC_H264, .width = camera_width(),
            .height = camera_height(), .fps = VIDEO_FPS,
        },
        .audio_dir = supports_audio ? ESP_PEER_MEDIA_DIR_SEND_RECV : ESP_PEER_MEDIA_DIR_NONE,
        .video_dir = supports_video ? ESP_PEER_MEDIA_DIR_SEND_ONLY : ESP_PEER_MEDIA_DIR_NONE,
        .no_auto_reconnect = true, .extra_cfg = &options, .extra_size = sizeof(options),
        .on_state = peer_state, .on_msg = peer_message, .on_audio_data = peer_audio,
    };
    int result = esp_peer_open(&config, esp_peer_get_default_impl(), &peer);
    return result == ESP_PEER_ERR_NONE ? esp_peer_new_connection(peer) : result;
}

static void receive_steering(const char *payload)
{
    int position, end = 0;
    long long sent_ms;
    if (sscanf(payload, "%d %lld%n", &position, &sent_ms, &end) != 2 ||
        payload[end] || position < -100 || position > 100) return;
    if (position == 0) {
        steering_set(0, 0);
        return;
    }
    struct timeval now;
    gettimeofday(&now, NULL);
    int64_t now_ms = (int64_t)now.tv_sec * 1000 + now.tv_usec / 1000;
    if (sent_ms < now_ms - STEERING_WATCHDOG_MS || sent_ms > now_ms + STEERING_CLOCK_TOLERANCE_MS) return;
    uint32_t remaining_ms = sent_ms > now_ms ? STEERING_WATCHDOG_MS :
                            STEERING_WATCHDOG_MS - (now_ms - sent_ms);
    steering_set(position, remaining_ms);
}

static void process_signal(char *text)
{
    char *next = text;
    const char *heading = strsep(&next, "\n");
    const char *id = strchr(heading, ' ');
    if (!id || strlen(++id) != SESSION_ID_SIZE) return;
    if (strncmp(heading, "start ", 6) == 0 && next) {
        const char *url = strsep(&next, "\n");
        const char *user = strsep(&next, "\n");
        const char *key = strsep(&next, "\n");
        if (!url || !user || !key || strlen(url) >= sizeof(turn_url) ||
            strlen(user) >= sizeof(turn_user) || strlen(key) >= sizeof(turn_key)) return;
        close_peer();
        strcpy(session, id);
        strcpy(turn_url, url);
        strcpy(turn_user, user);
        strcpy(turn_key, key);
        int result = open_peer();
        if (result != ESP_PEER_ERR_NONE) {
            ESP_LOGW(TAG, "Start WebRTC failed: %d", result);
            send_signal("failed", NULL, 0);
            close_peer();
        }
    } else if (peer && strcmp(id, session) == 0) {
        if (strncmp(heading, "stop ", 5) == 0) {
            close_peer();
        } else if (strncmp(heading, "answer ", 7) == 0 && next) {
            esp_peer_msg_t message = { .type = ESP_PEER_MSG_TYPE_SDP, .data = (uint8_t *)next, .size = strlen(next) };
            int result = esp_peer_send_msg(peer, &message);
            if (result != ESP_PEER_ERR_NONE) atomic_store(&peer_failed, true);
        } else if (strncmp(heading, "steer ", 6) == 0 && next && supports_steering &&
                   atomic_load(&media_active)) {
            receive_steering(next);
        }
    }
}

static void transfer_media(void)
{
    bool active = atomic_load(&media_active) && peer;
    unsigned generation = atomic_load(&media_generation);
    encoded_audio_t audio;
    for (unsigned i = 0; i < 4 && xQueueReceive(audio_queue, &audio, 0) == pdTRUE; ++i) {
        if (active && audio.frame.generation == generation) {
            esp_peer_audio_frame_t packet = { .data = audio.data, .size = audio.frame.size, .pts = audio.frame.pts };
            esp_peer_send_audio(peer, &packet);
        }
    }
    media_frame_t video;
    if (xQueueReceive(video_queue, &video, 0) == pdTRUE) {
        if (active && video.generation == generation) {
            esp_peer_video_frame_t packet = { .data = video_buffer, .size = video.size, .pts = video.pts };
            if (esp_peer_send_video(peer, &packet) != ESP_PEER_ERR_NONE) camera_request_keyframe();
        }
        xSemaphoreGive(video_slot);
    }
}

static void media_send_task(void *arg)
{
    TickType_t wake = xTaskGetTickCount();
    while (true) {
        xSemaphoreTake(media_send_lock, portMAX_DELAY);
        transfer_media();
        xSemaphoreGive(media_send_lock);
        vTaskDelayUntil(&wake, 1);
    }
}

static void rtc_task(void *arg)
{
    unsigned current_epoch = 0;
    while (true) {
        unsigned epoch = atomic_load(&signaling_epoch);
        if (epoch != current_epoch) {
            close_peer();
            current_epoch = epoch;
        }
        signal_message_t command;
        while (xQueueReceive(command_queue, &command, 0) == pdTRUE) {
            if (command.epoch == current_epoch && esp_websocket_client_is_connected(signaling)) {
                process_signal(command.data);
            }
            free(command.data);
        }
        if (peer && atomic_exchange(&peer_failed, false)) {
            send_signal("failed", NULL, 0);
            close_peer();
        }
        if (peer) esp_peer_main_loop(peer);
        vTaskDelay(1);
    }
}

static void signaling_event(void *arg, esp_event_base_t base, int32_t event, void *data)
{
    if (event == WEBSOCKET_EVENT_CONNECTED) {
        ESP_LOGI(TAG, "WebRTC signaling connected");
        return;
    }
    if (event == WEBSOCKET_EVENT_DISCONNECTED || event == WEBSOCKET_EVENT_CLOSED || event == WEBSOCKET_EVENT_ERROR) {
        atomic_fetch_add(&signaling_epoch, 1);
        return;
    }
    if (event != WEBSOCKET_EVENT_DATA) return;
    const esp_websocket_event_data_t *part = data;
    if (part->op_code != 1 || part->payload_len <= 0 || part->payload_len > SIGNAL_CAPACITY ||
        part->data_len <= 0 || part->payload_offset < 0 ||
        part->payload_offset + part->data_len > part->payload_len) return;
    memcpy(signal_buffer + part->payload_offset, part->data_ptr, part->data_len);
    if (part->payload_offset + part->data_len == part->payload_len) {
        signal_buffer[part->payload_len] = '\0';
        signal_message_t complete = { .data = strdup(signal_buffer), .epoch = atomic_load(&signaling_epoch) };
        if (complete.data && xQueueSend(command_queue, &complete, 0) != pdPASS) free(complete.data);
    }
}

esp_err_t rtc_start(const char *uri, const char *headers, bool video, bool audio, bool steering)
{
    supports_video = video;
    supports_audio = audio;
    supports_steering = steering;
    esp_log_level_set("AGENT", ESP_LOG_WARN);
    command_queue = xQueueCreate(6, sizeof(signal_message_t));
    audio_queue = xQueueCreate(8, sizeof(encoded_audio_t));
    video_queue = xQueueCreate(1, sizeof(media_frame_t));
    video_slot = xSemaphoreCreateBinary();
    media_send_lock = xSemaphoreCreateMutex();
    video_buffer = heap_caps_malloc(VIDEO_PACKET_CAPACITY, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!command_queue || !audio_queue || !video_queue || !video_slot || !media_send_lock || !video_buffer) {
        return ESP_ERR_NO_MEM;
    }
    xSemaphoreGive(video_slot);
    const esp_websocket_client_config_t config = {
        .uri = uri, .headers = headers, .crt_bundle_attach = esp_crt_bundle_attach,
        .buffer_size = SIGNAL_CAPACITY, .task_stack = 6144, .network_timeout_ms = 4000,
        .reconnect_timeout_ms = 2000,
    };
    signaling = esp_websocket_client_init(&config);
    if (!signaling) return ESP_ERR_NO_MEM;
    ESP_ERROR_CHECK(esp_websocket_register_events(signaling, WEBSOCKET_EVENT_ANY, signaling_event, NULL));
    if (xTaskCreateWithCaps(media_send_task, "rtc_send", 16384, NULL, 7, NULL,
                            MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT) != pdPASS) return ESP_ERR_NO_MEM;
    if (xTaskCreateWithCaps(rtc_task, "rtc", 98304, NULL, 6, NULL,
                            MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT) != pdPASS) return ESP_ERR_NO_MEM;
    return esp_websocket_client_start(signaling);
}
