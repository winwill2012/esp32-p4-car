#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "driver/i2c_master.h"
#include "esp_err.h"

#define SAMPLE_RATE 16000
#define AUDIO_SAMPLES 320
#define VIDEO_FPS 20
#define VIDEO_BITRATE 2200000
#define VIDEO_PACKET_CAPACITY (128 * 1024)
#define AUDIO_PACKET_CAPACITY 512
typedef struct {
    int16_t samples[AUDIO_SAMPLES];
} audio_packet_t;

esp_err_t audio_setup(i2c_master_bus_handle_t bus);
void audio_start(void);
void audio_enable(bool enabled);
void audio_receive_opus(uint8_t *data, size_t size);
esp_err_t camera_setup(i2c_master_bus_handle_t bus);
void camera_start(void);
void camera_enable(bool enabled);
void camera_request_keyframe(void);
uint32_t camera_width(void);
uint32_t camera_height(void);

esp_err_t steering_setup(void);
void steering_set(int position, uint32_t valid_ms);

esp_err_t rtc_start(const char *uri, const char *headers, bool video, bool audio, bool steering);
bool rtc_video_ready(void);
void rtc_publish_audio(const uint8_t *data, size_t size, uint32_t pts);
void rtc_publish_video(const uint8_t *data, size_t size, uint32_t pts);
