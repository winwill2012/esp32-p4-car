#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "driver/i2c_master.h"
#include "esp_err.h"

#define SAMPLE_RATE 16000
#define AUDIO_SAMPLES 320
#define VIDEO_FPS 20
// A 720p IDR can exceed 128 KiB before rate control settles.
#define VIDEO_PACKET_CAPACITY (384 * 1024)
#define AUDIO_PACKET_CAPACITY 512
typedef struct {
    int16_t samples[AUDIO_SAMPLES];
} audio_packet_t;

esp_err_t audio_setup(i2c_master_bus_handle_t bus);
void audio_start(void);
void audio_enable(bool enabled);
void audio_receive_opus(uint8_t *data, size_t size);
esp_err_t camera_setup(i2c_master_bus_handle_t bus);
bool camera_set_standard(bool enabled);
bool camera_supports_standard(void);
void camera_start(void);
void camera_enable(bool enabled);
void camera_request_keyframe(void);
uint32_t camera_width(void);
uint32_t camera_height(void);

esp_err_t steering_setup(void);
void steering_set(int position, uint32_t valid_ms);

esp_err_t lights_setup(void);
void lights_turn(int position, uint32_t valid_ms);
void lights_reverse(bool on, uint32_t valid_ms);
void lights_headlight(bool on);
void lights_reset(void);

esp_err_t rtc_start(const char *uri, const char *headers, bool video, bool audio, bool steering, bool lights);
bool rtc_video_ready(void);
void rtc_publish_audio(const uint8_t *data, size_t size, uint32_t pts);
void rtc_publish_video(const uint8_t *data, size_t size, uint32_t pts);
