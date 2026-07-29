cmake_minimum_required(VERSION 3.22)

project(bruce_flasher LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(PORT "USER_DEFINED" CACHE STRING "esp-serial-flasher port" FORCE)
set(SERIAL_FLASHER_LOG_LEVEL "WARN" CACHE STRING "esp-serial-flasher log level" FORCE)

set(ESP_SERIAL_FLASHER_DIR
    "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/esp-serial-flasher")

if(NOT EXISTS "${ESP_SERIAL_FLASHER_DIR}/include/esp_loader.h")
    message(FATAL_ERROR
        "Pinned esp-serial-flasher v2.0.0 is missing at ${ESP_SERIAL_FLASHER_DIR}")
endif()

add_subdirectory(
    "${ESP_SERIAL_FLASHER_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}/esp-serial-flasher"
)

add_library(
    bruce_flasher
    SHARED
    android_serial_port.cpp
    esp_flasher_jni.cpp
)

find_library(android_log_lib log REQUIRED)

target_include_directories(
    bruce_flasher
    PRIVATE
    "${ESP_SERIAL_FLASHER_DIR}/include"
)

target_compile_definitions(
    bruce_flasher
    PRIVATE
    ESP_SERIAL_FLASHER_PINNED_VERSION="2.0.0"
    ESP_SERIAL_FLASHER_PINNED_COMMIT="ca7edfcef903a4075332f0ad6c8fbda1e2cb9a9d"
)

target_link_libraries(
    bruce_flasher
    PRIVATE
    flasher
    "${android_log_lib}"
)

