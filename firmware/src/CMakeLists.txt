if(NOT CMAKE_BUILD_EARLY_EXPANSION)
    include(opus.cmake)
    set(opus_include_dirs "${opus_root}" "${opus_root}/include" "${opus_root}/celt"
        "${opus_root}/silk" "${opus_root}/silk/fixed")
endif()

idf_component_register(
    SRCS "main.c" "audio.c" "camera.c" "rtc.c" "steering.c" "lights.c"
         ${opus_main_sources} ${opus_celt_sources} ${opus_silk_sources} ${opus_fixed_sources}
    INCLUDE_DIRS "."
    PRIV_INCLUDE_DIRS ${opus_include_dirs}
    REQUIRES nvs_flash esp_netif esp_event esp_driver_i2s esp_driver_i2c esp_driver_gpio esp_driver_isp esp_driver_ledc esp_timer mbedtls esp_psram lwip
)

target_compile_definitions(${COMPONENT_LIB} PRIVATE OPUS_BUILD FIXED_POINT DISABLE_FLOAT_API VAR_ARRAYS)
target_link_libraries(${COMPONENT_LIB} INTERFACE "-Wl,--wrap=esp_isp_new_processor")
