#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/isp.h"
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
#include "ov5647_960p_regs.h"

#include "linux/videodev2.h"

#define CAMERA_BUFFERS 3
#define H264_MACROBLOCK_ROWS 16
#define CROP_ROW_ALIGNMENT 4
#define STANDARD_WIDTH 640
#define STANDARD_HEIGHT 360
#define STANDARD_BITRATE 1100000
#define HD_BITRATE 2400000
#define FALLBACK_HD_BITRATE 2200000
#define FRAME_INTERVAL_US (1000000 / VIDEO_FPS)
#define VIDEO_GOP 16
#define MIN_KEYFRAME_INTERVAL_US 250000
#define SHUTTER_LIMIT_US 8000
#define LOW_LIGHT_SHUTTER_LIMIT_US 10000
#define LOW_LIGHT_GAIN 0x80
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
#define OV5647_960P_PIXEL_CLOCK 88333333

static const esp_cam_sensor_isp_info_t ov5647_960p_isp = {
    .isp_v1_info = {
        .version = SENSOR_ISP_INFO_VERSION_DEFAULT,
        .pclk = OV5647_960P_PIXEL_CLOCK,
        .hts = 1796,
        .vts = 1093,
        .bayer_type = ESP_CAM_SENSOR_BAYER_GBRG,
    },
};

// The pinned sensor driver accepts custom tables via VIDIOC_S_SENSOR_FMT.
// Its stream control applies the configured line-sync setting to every mode.
static const esp_cam_sensor_format_t ov5647_960p = {
    .name = "OV5647 RAW10 1280x960 at 45 fps",
    .format = ESP_CAM_SENSOR_PIXFORMAT_RAW10,
    .port = ESP_CAM_SENSOR_MIPI_CSI,
    .xclk = 24000000,
    .width = 1280,
    .height = 960,
    .regs = ov5647_mipi_2lane_24Minput_1280x960_raw10_45fps,
    .regs_size = sizeof(ov5647_mipi_2lane_24Minput_1280x960_raw10_45fps) / sizeof(ov5647_reginfo_t),
    .fps = 45,
    .isp_info = &ov5647_960p_isp,
    .mipi_info = {
        .mipi_clk = OV5647_960P_PIXEL_CLOCK * 5,
        .lane_num = 2,
        .line_sync_en = CONFIG_CAMERA_OV5647_CSI_LINESYNC_ENABLE,
    },
};

static const char *TAG = "camera";
static int camera_fd = -1;
static i2c_master_dev_handle_t sensor;
static uint32_t exposure_units_per_second;
static uint32_t shutter_limit;
static uint32_t low_light_shutter_limit;
static uint32_t shutter;
static uint32_t gain;
static uint8_t manual_exposure_mode;
static uint8_t *frames[CAMERA_BUFFERS];
static uint32_t frame_size;
static uint32_t encoder_input_offset;
static uint32_t encoder_input_size;
static uint8_t *encoded;
static uint32_t encoded_capacity;
static esp_h264_enc_cfg_hw_t encoder_config;
static uint8_t *standard_frame;
static uint32_t standard_frame_size;
static uint16_t standard_luma_offsets[STANDARD_WIDTH];
static uint16_t standard_chroma_offsets[STANDARD_WIDTH / 2];
static atomic_bool standard_requested;
static atomic_bool streaming;
static atomic_bool keyframe_requested;

esp_err_t __real_esp_isp_new_processor(const esp_isp_processor_cfg_t *config, isp_proc_handle_t *processor);

esp_err_t __wrap_esp_isp_new_processor(const esp_isp_processor_cfg_t *config, isp_proc_handle_t *processor)
{
    if (config->input_data_source != ISP_INPUT_DATA_SOURCE_CSI ||
        config->input_data_color_type != ISP_COLOR_RAW10 ||
        config->h_res != ov5647_960p.width || config->v_res != ov5647_960p.height) {
        return __real_esp_isp_new_processor(config, processor);
    }

    // esp_video 0.5.1 leaves the ISP at BGGR. The OV5647 RAW10 mode outputs GBRG.
    esp_isp_processor_cfg_t tuned = *config;
    tuned.bayer_order = COLOR_RAW_ELEMENT_ORDER_GBRG;
    ESP_RETURN_ON_ERROR(__real_esp_isp_new_processor(&tuned, processor), TAG, "Create OV5647 ISP");

    const esp_isp_bf_config_t denoise = {
        .denoising_level = 5,
        .bf_template = {{1, 2, 1}, {2, 4, 2}, {1, 2, 1}},
    };
    esp_err_t error = esp_isp_bf_configure(*processor, &denoise);
    if (error == ESP_OK) error = esp_isp_bf_enable(*processor);
    if (error != ESP_OK) ESP_LOGW(TAG, "Could not enable OV5647 Bayer noise filter: %s", esp_err_to_name(error));
    return ESP_OK;
}

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
    const uint32_t dark_shutter_us = format.format == ESP_CAM_SENSOR_PIXFORMAT_RAW10 ?
                                     LOW_LIGHT_SHUTTER_LIMIT_US : SHUTTER_LIMIT_US;
    low_light_shutter_limit = (uint64_t)exposure_units_per_second * dark_shutter_us / 1000000;
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

    // Keep the short shutter until the scene would otherwise need high analog gain.
    const uint32_t signal = shutter * gain;
    const uint32_t requested = signal * EXPOSURE_TARGET_Y / (brightness ? brightness : 1);
    const uint32_t adjusted = clamp_u32(requested, signal * 2 / 3, signal * 3 / 2);
    const uint32_t shutter_ceiling = clamp_u32(adjusted / LOW_LIGHT_GAIN, shutter_limit, low_light_shutter_limit);
    const uint32_t next_shutter = clamp_u32(adjusted / OV5647_GAIN_MIN, 16, shutter_ceiling);
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

static void setup_standard_frame(uint32_t width, uint32_t height)
{
    if (width <= STANDARD_WIDTH || height <= STANDARD_HEIGHT || width * 9 != height * 16) return;
    const uint32_t padded_height = (STANDARD_HEIGHT + H264_MACROBLOCK_ROWS - 1)
                                   / H264_MACROBLOCK_ROWS * H264_MACROBLOCK_ROWS;
    standard_frame_size = STANDARD_WIDTH * 3 / 2 * padded_height;
    uint32_t capacity;
    standard_frame = esp_h264_aligned_calloc(64, 1, standard_frame_size, &capacity,
                                             MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!standard_frame) {
        ESP_LOGW(TAG, "Standard definition unavailable: no scaling buffer");
        return;
    }
    for (uint32_t x = 0; x < STANDARD_WIDTH; ++x) {
        const uint32_t source_x = (2 * x + 1) * width / (2 * STANDARD_WIDTH);
        standard_luma_offsets[x] = source_x / 2 * 3 + 1 + source_x % 2;
    }
    for (uint32_t pair = 0; pair < STANDARD_WIDTH / 2; ++pair) {
        standard_chroma_offsets[pair] = (2 * pair + 1) * width / (2 * STANDARD_WIDTH) * 3;
    }
}

// Downsample the 720p crop with a 2x2 average before encoding 360p.
static void downsample_standard_2x(const uint8_t *input)
{
    const uint32_t dest_stride = STANDARD_WIDTH * 3 / 2;
    const uint32_t source_stride = 2 * dest_stride;
    const uint32_t rows = standard_frame_size / dest_stride;
    for (uint32_t row = 0; row < rows; ++row) {
        const uint32_t parity = row % 2;
        const uint32_t visible_row = row < STANDARD_HEIGHT ? row : STANDARD_HEIGHT - 2 + parity;
        const uint8_t *first = input + 2 * visible_row * source_stride;
        const uint8_t *second = first + source_stride;
        // Chroma rows alternate U and V. Average rows of the same component.
        const uint8_t *chroma_first = first - parity * source_stride;
        const uint8_t *chroma_second = chroma_first + 2 * source_stride;
        uint8_t *out = standard_frame + row * dest_stride;
        for (uint32_t pair = 0; pair < STANDARD_WIDTH / 2; ++pair) {
            *out++ = (chroma_first[0] + chroma_first[3] + chroma_second[0] + chroma_second[3] + 2) / 4;
            *out++ = (first[1] + first[2] + second[1] + second[2] + 2) / 4;
            *out++ = (first[4] + first[5] + second[4] + second[5] + 2) / 4;
            first += 6;
            second += 6;
            chroma_first += 6;
            chroma_second += 6;
        }
    }
}

// Keep the previous resampling path for the 800-pixel sensor fallback.
static void scale_standard_frame(const uint8_t *input)
{
    if (encoder_config.res.width == STANDARD_WIDTH * 2 && encoder_config.res.height == STANDARD_HEIGHT * 2) {
        downsample_standard_2x(input);
        return;
    }
    const uint32_t source_stride = encoder_config.res.width * 3 / 2;
    const uint32_t source_pairs = encoder_config.res.height / 2;
    const uint32_t dest_stride = STANDARD_WIDTH * 3 / 2;
    const uint32_t dest_rows = standard_frame_size / dest_stride;
    for (uint32_t row = 0; row < dest_rows; ++row) {
        const uint32_t output_pair = row < STANDARD_HEIGHT ? row / 2 : STANDARD_HEIGHT / 2 - 1;
        const uint32_t source_pair = (2 * output_pair + 1) * source_pairs / STANDARD_HEIGHT;
        const uint8_t *source = input + (2 * source_pair + row % 2) * source_stride;
        uint8_t *dest = standard_frame + row * dest_stride;
        for (uint32_t pair = 0; pair < STANDARD_WIDTH / 2; ++pair) {
            *dest++ = source[standard_chroma_offsets[pair]];
            *dest++ = source[standard_luma_offsets[pair * 2]];
            *dest++ = source[standard_luma_offsets[pair * 2 + 1]];
        }
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

    // The sensor driver retains the format pointer after VIDIOC_S_SENSOR_FMT.
    static esp_cam_sensor_format_t default_format;
    ESP_RETURN_ON_FALSE(ioctl(camera_fd, VIDIOC_G_SENSOR_FMT, &default_format) == 0,
                        ESP_FAIL, TAG, "Read default sensor mode");
    if (ioctl(camera_fd, VIDIOC_S_SENSOR_FMT, &ov5647_960p) != 0) {
        ESP_LOGW(TAG, "OV5647 1280x960 mode failed (%d), restoring default", errno);
        ESP_RETURN_ON_FALSE(ioctl(camera_fd, VIDIOC_S_SENSOR_FMT, &default_format) == 0,
                            ESP_FAIL, TAG, "Restore default sensor mode");
    }

    struct v4l2_format camera = { .type = V4L2_BUF_TYPE_VIDEO_CAPTURE };
    ESP_RETURN_ON_FALSE(ioctl(camera_fd, VIDIOC_G_FMT, &camera) == 0, ESP_FAIL, TAG, "Read CSI format");
    camera.fmt.pix.pixelformat = V4L2_PIX_FMT_YUV420;
    ESP_RETURN_ON_FALSE(ioctl(camera_fd, VIDIOC_S_FMT, &camera) == 0, ESP_FAIL, TAG, "Select CSI YUV420");
    const uint32_t capture_width = camera.fmt.pix.width;
    const uint32_t capture_height = camera.fmt.pix.height;
    const uint32_t row_bytes = capture_width * 3 / 2;
    uint32_t video_height = capture_height;
    frame_size = row_bytes * capture_height;
    encoder_input_offset = 0;
    encoder_input_size = frame_size;
    if (capture_height * 16 > capture_width * 9) {
        video_height = capture_width * 9 / 16;
        const uint32_t crop_top = ((capture_height - video_height) / 2 + CROP_ROW_ALIGNMENT / 2)
                                  / CROP_ROW_ALIGNMENT * CROP_ROW_ALIGNMENT;
        const uint32_t padded_height = (video_height + H264_MACROBLOCK_ROWS - 1)
                                       / H264_MACROBLOCK_ROWS * H264_MACROBLOCK_ROWS;
        // Keep packed 4:2:0 row pairs intact and the H.264 input offset aligned to 64 bytes.
        // The existing capture buffer includes the extra rows read by the final macroblock.
        encoder_input_offset = crop_top * row_bytes;
        encoder_input_size = padded_height * row_bytes;
        ESP_RETURN_ON_FALSE(encoder_input_offset + encoder_input_size <= frame_size,
                            ESP_ERR_INVALID_SIZE, TAG, "H.264 crop exceeds capture frame");
    }
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
        .res = { .width = capture_width, .height = video_height },
        .pic_type = ESP_H264_RAW_FMT_O_UYY_E_VYY,
        .fps = VIDEO_FPS,
        .gop = VIDEO_GOP,
        .rc = {
            .bitrate = capture_width >= 1280 ? HD_BITRATE : FALLBACK_HD_BITRATE,
            .qp_min = capture_width >= 1280 ? 24 : 20,
            .qp_max = 40,
        },
    };
    encoded = esp_h264_aligned_calloc(64, 1, VIDEO_PACKET_CAPACITY, &encoded_capacity,
                                      MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    ESP_RETURN_ON_FALSE(encoded, ESP_ERR_NO_MEM, TAG, "Allocate H.264 output buffer");
    setup_standard_frame(capture_width, video_height);
    ESP_LOGI(TAG, "H.264 %ux%u from CSI %lux%lu at %d fps, target %lu bit/s",
             encoder_config.res.width, encoder_config.res.height, (unsigned long)capture_width,
             (unsigned long)capture_height, VIDEO_FPS, (unsigned long)encoder_config.rc.bitrate);
    return ESP_OK;
}

bool camera_supports_standard(void) { return standard_frame != NULL; }

bool camera_set_standard(bool enabled)
{
    const bool standard = enabled && camera_supports_standard();
    atomic_store(&standard_requested, standard);
    return standard;
}

uint32_t camera_width(void) { return atomic_load(&standard_requested) ? STANDARD_WIDTH : encoder_config.res.width; }
uint32_t camera_height(void) { return atomic_load(&standard_requested) ? STANDARD_HEIGHT : encoder_config.res.height; }

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
    bool encoder_standard = false;
    const int type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    bool capturing = false;
    uint32_t queued = 0;
    int64_t next_frame = 0;
    int64_t last_keyframe = 0, next_exposure_update = 0, inspect_exposure_at = 0;
    uint8_t keyframe_interval = VIDEO_GOP;
    while (true) {
        bool enabled = atomic_load(&streaming);
        bool standard = atomic_load(&standard_requested);
        if (encoder && ((!enabled && !capturing) || (enabled && encoder_standard != standard))) {
            esp_h264_enc_close(encoder);
            esp_h264_enc_del(encoder);
            encoder = NULL;
            hardware_params = NULL;
        }
        if (enabled && !encoder) {
            esp_h264_enc_cfg_hw_t active_config = encoder_config;
            if (standard) {
                active_config.res.width = STANDARD_WIDTH;
                active_config.res.height = STANDARD_HEIGHT;
                active_config.rc.bitrate = STANDARD_BITRATE;
                active_config.rc.qp_min = 20;
            }
            if (esp_h264_enc_hw_new(&active_config, &encoder) != ESP_H264_ERR_OK ||
                esp_h264_enc_open(encoder) != ESP_H264_ERR_OK) {
                if (encoder) esp_h264_enc_del(encoder);
                encoder = NULL;
                ESP_LOGE(TAG, "Start hardware H.264 encoder");
                vTaskDelay(pdMS_TO_TICKS(500));
                continue;
            }
            encoder_standard = standard;
            esp_h264_enc_hw_get_param_hd(encoder, &hardware_params);
            keyframe_interval = VIDEO_GOP;
            last_keyframe = 0;
            ESP_LOGI(TAG, "Streaming H.264 %ux%u at %lu bit/s",
                     active_config.res.width, active_config.res.height, (unsigned long)active_config.rc.bitrate);
        }
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
            int command = enabled ? VIDIOC_STREAMON : VIDIOC_STREAMOFF;
            if (ioctl(camera_fd, command, &type) != 0) {
                ESP_LOGE(TAG, "Change capture state (%d)", errno);
                vTaskDelay(pdMS_TO_TICKS(500));
                continue;
            }
            capturing = enabled;
            if (!capturing) {
                queued = 0;
                if (encoder) {
                    esp_h264_enc_close(encoder);
                    esp_h264_enc_del(encoder);
                }
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
        uint8_t *input_frame = (buffer.index < CAMERA_BUFFERS && buffer.bytesused >= frame_size) ?
                               frames[buffer.index] + encoder_input_offset : NULL;
        if (shutter_limit && now >= next_exposure_update && input_frame) {
            esp_err_t error = update_exposure(input_frame);
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
        bool publish = now >= next_frame && input_frame && rtc_video_ready();
        if (publish) {
            if (hardware_params && now - last_keyframe >= MIN_KEYFRAME_INTERVAL_US &&
                atomic_exchange(&keyframe_requested, false)) {
                // In this encoder version, a GOP change emits an IDR on the next frame.
                uint8_t alternate = keyframe_interval == VIDEO_GOP ? VIDEO_GOP + 1 : VIDEO_GOP;
                if (esp_h264_enc_set_gop((esp_h264_enc_param_handle_t)hardware_params, alternate) == ESP_H264_ERR_OK) {
                    keyframe_interval = alternate;
                }
            }
            if (encoder_standard) scale_standard_frame(input_frame);
            esp_h264_enc_in_frame_t input = {
                .raw_data = {
                    .buffer = encoder_standard ? standard_frame : input_frame,
                    .len = encoder_standard ? standard_frame_size : encoder_input_size,
                },
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
        if (output.length && atomic_load(&streaming) && encoder_standard == atomic_load(&standard_requested)) {
            rtc_publish_video(encoded, output.length, output.pts);
        }
    }
}

void camera_start(void)
{
    BaseType_t created = xTaskCreate(camera_task, "camera", 6144, NULL, 4, NULL);
    assert(created == pdPASS);
}
