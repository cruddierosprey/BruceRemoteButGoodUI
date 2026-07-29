/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Android USER_DEFINED port for Espressif esp-serial-flasher v2.
 */
#pragma once

#include <jni.h>

#include <chrono>
#include <cstdint>
#include <string>

extern "C" {
#include "esp_loader_io.h"
}

enum class AndroidResetStrategy : int {
    kClassicDtrRts = 1,
    kUsbJtag = 2,
    kManualBootloader = 3,
};

struct AndroidSerialPort {
    esp_loader_port_t base{};
    JavaVM *vm = nullptr;
    jobject transport = nullptr;
    jobject callbacks = nullptr;

    jmethodID write_method = nullptr;
    jmethodID read_method = nullptr;
    jmethodID set_baud_rate_method = nullptr;
    jmethodID set_control_lines_method = nullptr;
    jmethodID purge_input_method = nullptr;
    jmethodID await_reconnect_method = nullptr;
    jmethodID progress_method = nullptr;
    jmethodID is_cancelled_method = nullptr;

    AndroidResetStrategy reset_strategy = AndroidResetStrategy::kManualBootloader;
    uint32_t baud_rate = 115200;
    uint32_t reenumeration_timeout_ms = 10000;
    std::chrono::steady_clock::time_point deadline{};

    bool transport_failed = false;
    std::string transport_failure;
};

extern const esp_loader_port_ops_t android_serial_port_ops;

bool android_serial_port_bind(
    JNIEnv *env,
    AndroidSerialPort *port,
    jobject transport,
    jobject callbacks,
    AndroidResetStrategy reset_strategy,
    uint32_t baud_rate,
    uint32_t reenumeration_timeout_ms);

void android_serial_port_release(JNIEnv *env, AndroidSerialPort *port);

bool android_serial_port_is_cancelled(AndroidSerialPort *port);

bool android_serial_port_report_progress(
    AndroidSerialPort *port,
    int phase,
    int segment_index,
    int64_t completed,
    int64_t total);

