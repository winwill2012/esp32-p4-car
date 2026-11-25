#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_check.h"
#include "esp_h264_alloc.h"
#include "esp_h264_enc_single_hw.h"
#include "esp_heap_caps.h"
#include "esp_timer.h"
#include "esp_video_device.h"
#include "esp_video_init.h"
#include "esp_video_ioctl.h"
#include "intercom.h"
#include "ov5647.h"

#include "linux/videodev2.h"

#define CAMERA_BUFFERS 3
#define FRAME_INTERVAL_US (1000000 / VIDEO_FPS)
#define VIDEO_GOP 16
#define MIN_KEYFRAME_INTERVAL_US 250000
#define SHUTTER_LIMIT_US 8000
#define EXPOSURE_UPDATE_US 120000
#define EXPOSURE_TARGET_Y 105
#define EXPOSURE_DEADBAND_Y 8
#define OV5647_VTS 0x380e
#define OV5647_EXPOSURE 0x3500
#define OV5647_EXPOSURE_MODE 0x3503
#define OV5647_GAIN 0x350a
#define OV5647_GROUP 0x3208
#define OV5647_GAIN_MIN 0x10
#define OV5647_GAIN_MAX 0xf8

static const char *TAG = "camera";
static int camera_fd = -1;
static i2c_master_dev_handle_t sensor;
static uint32_t exposure_units_per_second;
static uint32_t shutter_limit;
static uint32_t shutter;
static uint32_t gain;
static uint8_t manual_exposure_mode;
static uint8_t *frames[CAMERA_BUFFERS];
static uint32_t frame_size;
static uint8_t *encoded;
static uint32_t encoded_capacity;
static esp_h264_enc_cfg_hw_t encoder_config;
static atomic_bool streaming;
static atomic_bool keyframe_requested;

static esp_err_t sensor_read(uint16_t reg, uint8_t *data, size_t size)
{
    const uint8_t address[] = { reg >> 8, reg & 0xff };
    return i2c_master_transmit_receive(sensor, address, sizeof(address), data, size, 40);
}

static esp_err_t sensor_write_byte(uint16_t reg, uint8_t value)
{
    const uint8_t message[] = { reg >> 8, reg & 0xff, value };
    return i2c_master_transmit(sensor, message, sizeof(message), 40);
}

static esp_err_t setup_exposure(i2c_master_bus_handle_t bus)
{
    esp_cam_sensor_format_t format;
    ESP_RETURN_ON_FALSE(ioctl(camera_fd, VIDIOC_G_SENSOR_FMT, &format) == 0 && format.fps,
                        ESP_FAIL, TAG, "Read sensor timing");
    const i2c_device_config_t config = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7, .device_address = OV5647_SCCB_ADDR, .scl_speed_hz = 100000,
    };
    ESP_RETURN_ON_ERROR(i2c_master_bus_add_device(bus, &config, &sensor), TAG, "Open OV5647 control");
    uint8_t timing[2], id[2], mode;
    ESP_RETURN_ON_ERROR(sensor_read(0x300a, id, sizeof(id)), TAG, "Read sensor ID");
    ESP_RETURN_ON_FALSE(id[0] == 0x56 && id[1] == 0x47, ESP_ERR_NOT_SUPPORTED, TAG, "Unexpected sensor ID");
    ESP_RETURN_ON_ERROR(sensor_read(OV5647_VTS, timing, sizeof(timing)), TAG, "Read OV5647 frame length");
    uint16_t lines = ((uint16_t)timing[0] << 8) | timing[1];
    ESP_RETURN_ON_FALSE(lines > 0, ESP_FAIL, TAG, "Invalid OV5647 frame length");
    ESP_RETURN_ON_ERROR(sensor_read(OV5647_EXPOSURE_MODE, &mode, 1), TAG, "Read OV5647 exposure mode");

    // OV5647 reports integration in sixteenths of one sensor line.
    exposure_units_per_second = (uint32_t)lines * format.fps * 16;
    shutter_limit = (uint64_t)exposure_units_per_second * SHUTTER_LIMIT_US / 1000000;
    manual_exposure_mode = mode | 0x07; // Manual exposure, gain and frame length.
    return ESP_OK;
}

static esp_err_t write_exposure(uint32_t next_shutter, uint32_t next_gain)
{
    const uint8_t integration[] = { OV5647_EXPOSURE >> 8, OV5647_EXPOSURE & 0xff,
                                   next_shutter >> 16, next_shutter >> 8, next_shutter, manual_exposure_mode };
    const uint8_t analog[] = { OV5647_GAIN >> 8, OV5647_GAIN & 0xff, 0, next_gain, 0, 0 };
    esp_err_t error = sensor_write_byte(OV5647_GROUP, 0x00);
    if (error != ESP_OK) return error;
    error = i2c_master_transmit(sensor, integration, sizeof(integration), 40);
    if (error == ESP_OK) error = i2c_master_transmit(sensor, analog, sizeof(analog), 40);
    esp_err_t end = sensor_write_byte(OV5647_GROUP, 0x10);
    if (error != ESP_OK) return error;
    if (end != ESP_OK) return end;
    ESP_RETURN_ON_ERROR(sensor_write_byte(OV5647_GROUP, 0xa0), TAG, "Apply shutter and gain");
    shutter = next_shutter;
    gain = next_gain;
    return ESP_OK;
}

static uint32_t clamp_u32(uint32_t value, uint32_t min, uint32_t max)
{
    if (value < min) return min;
    if (value > max) return max;
    return value;
}

static esp_err_t update_exposure(const uint8_t *frame)
{
    if (!shutter) return write_exposure(shutter_limit, OV5647_GAIN_MIN * 4);

    // Sample luma in a grid across the packed UYY/VYY frame.
    const uint32_t stride = encoder_config.res.width * 3 / 2;
    uint32_t brightness = 0;
    for (uint32_t y = 0; y < 12; y++) {
        const uint8_t *row = frame + (2 * y + 1) * encoder_config.res.height / 24 * stride;
        for (uint32_t x = 0; x < 12; x++) {
            const uint32_t pair = (2 * x + 1) * encoder_config.res.width / 48;
            brightness += row[pair * 3 + 1];
        }
    }
    brightness /= 12 * 12;
    if (brightness >= EXPOSURE_TARGET_Y - EXPOSURE_DEADBAND_Y &&
        brightness <= EXPOSURE_TARGET_Y + EXPOSURE_DEADBAND_Y) return ESP_OK;

    // Increase integration only up to the shutter limit, then use analog gain.
    const uint32_t signal = shutter * gain;
    const uint32_t requested = signal * EXPOSURE_TARGET_Y / (brightness ? brightness : 1);
    const uint32_t adjusted = clamp_u32(requested, signal * 2 / 3, signal * 3 / 2);
    const uint32_t next_shutter = clamp_u32(adjusted / OV5647_GAIN_MIN, 16, shutter_limit);
    const uint32_t next_gain = clamp_u32((adjusted + next_shutter / 2) / next_shutter,
                                          OV5647_GAIN_MIN, OV5647_GAIN_MAX);
    if (next_shutter == shutter && next_gain == gain) return ESP_OK;
    return write_exposure(next_shutter, next_gain);
}

static void log_exposure(void)
{
    uint8_t registers[4], sensor_gain[2];
    if (shutter && sensor_read(OV5647_EXPOSURE, registers, sizeof(registers)) == ESP_OK &&
        sensor_read(OV5647_GAIN, sensor_gain, sizeof(sensor_gain)) == ESP_OK) {
        uint32_t units = ((uint32_t)(registers[0] & 0xf) << 16) | ((uint32_t)registers[1] << 8) | registers[2];
        uint32_t exposure_us = (uint64_t)units * 1000000 / exposure_units_per_second;
        ESP_LOGI(TAG, "OV5647 current integration about %lu us, gain %u/16, mode 0x%02x",
                 exposure_us, ((sensor_gain[0] & 3) << 8) | sensor_gain[1], registers[3]);
    }
}

esp_err_t camera_setup(i2c_master_bus_handle_t bus)
{
    const esp_video_init_csi_config_t csi = {
        .sccb_config = { .init_sccb = false, .i2c_handle = bus, .freq = 100000 },
        .reset_pin = -1,
        .pwdn_pin = -1,
    };
    const esp_video_init_config_t init = { .csi = &csi };
    ESP_RETURN_ON_ERROR(esp_video_init(&init), TAG, "Initialize CSI");
    camera_fd = open(ESP_VIDEO_MIPI_CSI_DEVICE_NAME, O_RDWR);
    ESP_RETURN_ON_FALSE(camera_fd >= 0, ESP_FAIL, TAG, "Open CSI (%d)", errno);

    struct v4l2_format camera = { .type = V4L2_BUF_TYPE_VIDEO_CAPTURE };
    ESP_RETURN_ON_FALSE(ioctl(camera_fd, VIDIOC_G_FMT, &camera) == 0, ESP_FAIL, TAG, "Read CSI format");
    camera.fmt.pix.pixelformat = V4L2_PIX_FMT_YUV420;
    ESP_RETURN_ON_FALSE(ioctl(camera_fd, VIDIOC_S_FMT, &camera) == 0, ESP_FAIL, TAG, "Select CSI YUV420");
    frame_size = camera.fmt.pix.width * camera.fmt.pix.height * 3 / 2;
    esp_err_t exposure_error = setup_exposure(bus);
    if (exposure_error != ESP_OK) {
        ESP_LOGW(TAG, "Could not configure OV5647 shutter control: %s", esp_err_to_name(exposure_error));
    }

    struct v4l2_requestbuffers request = {
        .count = CAMERA_BUFFERS, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE, .memory = V4L2_MEMORY_MMAP,
    };
    ESP_RETURN_ON_FALSE(ioctl(camera_fd, VIDIOC_REQBUFS, &request) == 0 &&
                        request.count == CAMERA_BUFFERS, ESP_FAIL, TAG, "Request CSI buffers");
    for (uint32_t i = 0; i < request.count; i++) {
        struct v4l2_buffer buffer = { .type = request.type, .memory = request.memory, .index = i };
        ESP_RETURN_ON_FALSE(ioctl(camera_fd, VIDIOC_QUERYBUF, &buffer) == 0, ESP_FAIL, TAG, "Query CSI buffer");
        void *frame = mmap(NULL, buffer.length, PROT_READ | PROT_WRITE, MAP_SHARED, camera_fd, buffer.m.offset);
        ESP_RETURN_ON_FALSE(frame, ESP_FAIL, TAG, "Map CSI buffer");
        frames[i] = frame;
    }

    // The ESP32-P4 ISP and H.264 peripheral share this packed YUV420 layout.
    encoder_config = (esp_h264_enc_cfg_hw_t) {
        .res = { .width = camera.fmt.pix.width, .height = camera.fmt.pix.height },
        .pic_type = ESP_H264_RAW_FMT_O_UYY_E_VYY,
        .fps = VIDEO_FPS,
        .gop = VIDEO_GOP,
        .rc = { .bitrate = VIDEO_BITRATE, .qp_min = 20, .qp_max = 40 },
    };
    encoded = esp_h264_aligned_calloc(64, 1, VIDEO_PACKET_CAPACITY, &encoded_capacity,
                                      MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    ESP_RETURN_ON_FALSE(encoded, ESP_ERR_NO_MEM, TAG, "Allocate H.264 output buffer");
    ESP_LOGI(TAG, "H.264 %ux%u at %d fps, target %d bit/s",
             encoder_config.res.width, encoder_config.res.height, VIDEO_FPS, VIDEO_BITRATE);
    return ESP_OK;
}

uint32_t camera_width(void) { return encoder_config.res.width; }
uint32_t camera_height(void) { return encoder_config.res.height; }

void camera_enable(bool enabled)
{
    atomic_store(&streaming, enabled);
}

void camera_request_keyframe(void)
{
    atomic_store(&keyframe_requested, true);
}

static void camera_task(void *arg)
{
    esp_h264_enc_handle_t encoder = NULL;
    esp_h264_enc_param_hw_handle_t hardware_params = NULL;
    const int type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    bool capturing = false;
    uint32_t queued = 0;
    int64_t next_frame = 0;
    int64_t last_keyframe = 0, next_exposure_update = 0, inspect_exposure_at = 0;
    uint8_t keyframe_interval = VIDEO_GOP;
    while (true) {
        bool enabled = atomic_load(&streaming);
        if (enabled != capturing) {
            while (enabled && queued < CAMERA_BUFFERS) {
                struct v4l2_buffer buffer = { .type = type, .memory = V4L2_MEMORY_MMAP, .index = queued };
                if (ioctl(camera_fd, VIDIOC_QBUF, &buffer) != 0) break;
                ++queued;
            }
            if (enabled && queued < CAMERA_BUFFERS) {
                ESP_LOGW(TAG, "Queue capture buffers (%d)", errno);
                vTaskDelay(pdMS_TO_TICKS(500));
                continue;
            }
            if (enabled && !encoder) {
                if (esp_h264_enc_hw_new(&encoder_config, &encoder) != ESP_H264_ERR_OK ||
                    esp_h264_enc_open(encoder) != ESP_H264_ERR_OK) {
                    if (encoder) esp_h264_enc_del(encoder);
                    encoder = NULL;
                    ESP_LOGE(TAG, "Start hardware H.264 encoder");
                    vTaskDelay(pdMS_TO_TICKS(500));
                    continue;
                }
                esp_h264_enc_hw_get_param_hd(encoder, &hardware_params);
                keyframe_interval = VIDEO_GOP;
            }
            int command = enabled ? VIDIOC_STREAMON : VIDIOC_STREAMOFF;
            if (ioctl(camera_fd, command, &type) != 0) {
                ESP_LOGE(TAG, "Change capture state (%d)", errno);
                vTaskDelay(pdMS_TO_TICKS(500));
                continue;
            }
            capturing = enabled;
            if (!capturing) {
                queued = 0;
                esp_h264_enc_close(encoder);
                esp_h264_enc_del(encoder);
                encoder = NULL;
                hardware_params = NULL;
            }
            inspect_exposure_at = capturing ? esp_timer_get_time() + 1200000 : 0;
            next_exposure_update = 0;
            shutter = 0;
            last_keyframe = 0;
            next_frame = 0;
        }
        if (!capturing) {
            vTaskDelay(pdMS_TO_TICKS(40));
            continue;
        }
        struct v4l2_buffer buffer = { .type = type, .memory = V4L2_MEMORY_MMAP };
        if (ioctl(camera_fd, VIDIOC_DQBUF, &buffer) != 0) {
            vTaskDelay(pdMS_TO_TICKS(10));
            continue;
        }
        int64_t now = esp_timer_get_time();
        if (shutter_limit && now >= next_exposure_update && buffer.index < CAMERA_BUFFERS &&
            buffer.bytesused >= frame_size) {
            esp_err_t error = update_exposure(frames[buffer.index]);
            next_exposure_update = now + EXPOSURE_UPDATE_US;
            if (error != ESP_OK) {
                ESP_LOGW(TAG, "OV5647 exposure update failed: %s", esp_err_to_name(error));
                next_exposure_update = now + 1000000;
            }
        }
        if (inspect_exposure_at && now >= inspect_exposure_at) {
            log_exposure();
            inspect_exposure_at = 0;
        }
        esp_h264_enc_out_frame_t output = { .raw_data = { .buffer = encoded, .len = encoded_capacity } };
        // Skip the raw frame under backpressure so it never becomes an unsent H.264 reference.
        bool publish = now >= next_frame && buffer.index < CAMERA_BUFFERS &&
                       buffer.bytesused >= frame_size && rtc_video_ready();
        if (publish) {
            if (hardware_params && now - last_keyframe >= MIN_KEYFRAME_INTERVAL_US &&
                atomic_exchange(&keyframe_requested, false)) {
                // In this encoder version, a GOP change emits an IDR on the next frame.
                uint8_t alternate = keyframe_interval == VIDEO_GOP ? VIDEO_GOP + 1 : VIDEO_GOP;
                if (esp_h264_enc_set_gop((esp_h264_enc_param_handle_t)hardware_params, alternate) == ESP_H264_ERR_OK) {
                    keyframe_interval = alternate;
                }
            }
            esp_h264_enc_in_frame_t input = {
                .raw_data = { .buffer = frames[buffer.index], .len = frame_size },
                .pts = (uint32_t)(now / 1000),
            };
            esp_h264_err_t error = esp_h264_enc_process(encoder, &input, &output);
            if (error != ESP_H264_ERR_OK) {
                ESP_LOGW(TAG, "H.264 encode failed: %d", error);
                output.length = 0;
                camera_request_keyframe();
            } else if (output.frame_type == ESP_H264_FRAME_TYPE_IDR) {
                last_keyframe = now;
            }
            next_frame = next_frame > now - FRAME_INTERVAL_US ? next_frame + FRAME_INTERVAL_US : now + FRAME_INTERVAL_US;
        }
        if (ioctl(camera_fd, VIDIOC_QBUF, &buffer) != 0) ESP_LOGW(TAG, "Requeue CSI buffer (%d)", errno);
        if (output.length && atomic_load(&streaming)) rtc_publish_video(encoded, output.length, output.pts);
    }
}

void camera_start(void)
{
    BaseType_t created = xTaskCreate(camera_task, "camera", 6144, NULL, 4, NULL);
    assert(created == pdPASS);
}
