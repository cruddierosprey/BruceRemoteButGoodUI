/*
 * SPDX-License-Identifier: Apache-2.0
 */
#include "android_serial_port.h"

#include <android/log.h>

#include <algorithm>
#include <cstdarg>
#include <thread>

extern "C" {
#include "esp_loader_error.h"
}

namespace {

constexpr char kLogTag[] = "BruceFlasher";
constexpr uint32_t kResetHoldTimeMs = 100;
constexpr uint32_t kBootHoldTimeMs = 50;

class ScopedEnv {
public:
    explicit ScopedEnv(JavaVM *vm) : vm_(vm) {
        if (vm_ == nullptr) {
            return;
        }
        const jint result = vm_->GetEnv(reinterpret_cast<void **>(&env_), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            if (vm_->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
                attached_ = true;
            } else {
                env_ = nullptr;
            }
        } else if (result != JNI_OK) {
            env_ = nullptr;
        }
    }

    ~ScopedEnv() {
        if (attached_) {
            vm_->DetachCurrentThread();
        }
    }

    JNIEnv *get() const {
        return env_;
    }

private:
    JavaVM *vm_ = nullptr;
    JNIEnv *env_ = nullptr;
    bool attached_ = false;
};

AndroidSerialPort *as_android_port(esp_loader_port_t *base) {
    return container_of(base, AndroidSerialPort, base);
}

void mark_failed(AndroidSerialPort *port, const char *message) {
    port->transport_failed = true;
    port->transport_failure = message;
}

bool has_java_exception(JNIEnv *env, AndroidSerialPort *port, const char *operation) {
    if (env != nullptr && env->ExceptionCheck()) {
        mark_failed(port, operation);
        return true;
    }
    return false;
}

uint32_t millis_remaining(
    const std::chrono::steady_clock::time_point &deadline,
    uint32_t maximum) {
    const auto now = std::chrono::steady_clock::now();
    if (now >= deadline) {
        return 0;
    }
    const auto remaining =
        std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now).count();
    return std::min<uint32_t>(
        maximum,
        static_cast<uint32_t>(std::max<int64_t>(remaining, 1)));
}

bool call_boolean_no_args(
    AndroidSerialPort *port,
    jmethodID method,
    const char *operation) {
    ScopedEnv scoped(port->vm);
    JNIEnv *env = scoped.get();
    if (env == nullptr || method == nullptr) {
        mark_failed(port, operation);
        return false;
    }
    const jboolean result = env->CallBooleanMethod(port->transport, method);
    return !has_java_exception(env, port, operation) && result == JNI_TRUE;
}

bool set_control_lines(AndroidSerialPort *port, bool dtr, bool rts) {
    ScopedEnv scoped(port->vm);
    JNIEnv *env = scoped.get();
    if (env == nullptr || port->set_control_lines_method == nullptr) {
        mark_failed(port, "setControlLines is unavailable");
        return false;
    }
    const jboolean result = env->CallBooleanMethod(
        port->transport,
        port->set_control_lines_method,
        static_cast<jboolean>(dtr),
        static_cast<jboolean>(rts));
    return !has_java_exception(env, port, "setControlLines failed") &&
           result == JNI_TRUE;
}

bool set_baud_rate(AndroidSerialPort *port, uint32_t baud_rate) {
    ScopedEnv scoped(port->vm);
    JNIEnv *env = scoped.get();
    if (env == nullptr || port->set_baud_rate_method == nullptr) {
        mark_failed(port, "setBaudRate is unavailable");
        return false;
    }
    const jboolean result = env->CallBooleanMethod(
        port->transport,
        port->set_baud_rate_method,
        static_cast<jint>(baud_rate));
    if (has_java_exception(env, port, "setBaudRate failed") || result != JNI_TRUE) {
        if (result != JNI_TRUE && !port->transport_failed) {
            mark_failed(port, "setBaudRate returned false");
        }
        return false;
    }
    port->baud_rate = baud_rate;
    return true;
}

bool await_reconnect(AndroidSerialPort *port) {
    ScopedEnv scoped(port->vm);
    JNIEnv *env = scoped.get();
    if (env == nullptr || port->await_reconnect_method == nullptr) {
        mark_failed(port, "awaitReconnect is unavailable");
        return false;
    }
    const jboolean result = env->CallBooleanMethod(
        port->transport,
        port->await_reconnect_method,
        static_cast<jint>(port->reenumeration_timeout_ms));
    if (has_java_exception(env, port, "awaitReconnect failed") || result != JNI_TRUE) {
        if (result != JNI_TRUE && !port->transport_failed) {
            mark_failed(port, "USB device did not reconnect");
        }
        return false;
    }
    return set_baud_rate(port, port->baud_rate);
}

void delay_raw(uint32_t milliseconds) {
    std::this_thread::sleep_for(std::chrono::milliseconds(milliseconds));
}

esp_loader_error_t port_init(esp_loader_port_t *base) {
    AndroidSerialPort *port = as_android_port(base);
    port->transport_failed = false;
    port->transport_failure.clear();
    return set_baud_rate(port, port->baud_rate)
        ? ESP_LOADER_SUCCESS
        : ESP_LOADER_ERROR_FAIL;
}

void port_deinit(esp_loader_port_t *) {
    // The application owns the UsbDeviceConnection. JNI global references are
    // released by the operation session after esp_loader_deinit().
}

void port_enter_bootloader(esp_loader_port_t *base) {
    AndroidSerialPort *port = as_android_port(base);
    if (android_serial_port_is_cancelled(port)) {
        return;
    }

    switch (port->reset_strategy) {
        case AndroidResetStrategy::kClassicDtrRts:
            // Espressif UnixTightReset sequence. Change both lines together to
            // avoid the (0,0) glitch on the common two-transistor circuit.
            if (!set_control_lines(port, false, false) ||
                !set_control_lines(port, true, true) ||
                !set_control_lines(port, false, true)) {
                return;
            }
            delay_raw(kResetHoldTimeMs);
            if (!set_control_lines(port, true, false)) {
                return;
            }
            delay_raw(kBootHoldTimeMs);
            if (!set_control_lines(port, false, false)) {
                return;
            }
            break;

        case AndroidResetStrategy::kUsbJtag:
            // Espressif USBJTAGSerialReset sequence. The final RESET release
            // re-enumerates the Android USB device.
            if (!set_control_lines(port, false, false)) {
                return;
            }
            delay_raw(kResetHoldTimeMs);
            if (!set_control_lines(port, true, false)) {
                return;
            }
            delay_raw(kResetHoldTimeMs);
            if (!set_control_lines(port, true, true) ||
                !set_control_lines(port, false, true)) {
                return;
            }
            delay_raw(kResetHoldTimeMs);
            if (!set_control_lines(port, false, false) || !await_reconnect(port)) {
                return;
            }
            break;

        case AndroidResetStrategy::kManualBootloader:
            break;
    }

    call_boolean_no_args(port, port->purge_input_method, "purgeInput failed");
}

void port_reset_target(esp_loader_port_t *base) {
    AndroidSerialPort *port = as_android_port(base);
    switch (port->reset_strategy) {
        case AndroidResetStrategy::kClassicDtrRts:
            if (!set_control_lines(port, false, true)) {
                return;
            }
            delay_raw(kResetHoldTimeMs);
            set_control_lines(port, false, false);
            break;

        case AndroidResetStrategy::kUsbJtag:
            if (!set_control_lines(port, false, true)) {
                return;
            }
            delay_raw(kResetHoldTimeMs);
            if (!set_control_lines(port, false, false)) {
                return;
            }
            await_reconnect(port);
            break;

        case AndroidResetStrategy::kManualBootloader:
            break;
    }
}

void port_start_timer(esp_loader_port_t *base, uint32_t milliseconds) {
    as_android_port(base)->deadline =
        std::chrono::steady_clock::now() + std::chrono::milliseconds(milliseconds);
}

uint32_t port_remaining_time(esp_loader_port_t *base) {
    return millis_remaining(as_android_port(base)->deadline, UINT32_MAX);
}

void port_delay_ms(esp_loader_port_t *, uint32_t milliseconds) {
    delay_raw(milliseconds);
}

void port_log(
    esp_loader_port_t *,
    esp_loader_log_level_t level,
    const char *format,
    va_list args) {
    int priority = ANDROID_LOG_DEBUG;
    switch (level) {
        case ESP_LOADER_LOG_LEVEL_ERROR:
            priority = ANDROID_LOG_ERROR;
            break;
        case ESP_LOADER_LOG_LEVEL_WARN:
            priority = ANDROID_LOG_WARN;
            break;
        case ESP_LOADER_LOG_LEVEL_INFO:
            priority = ANDROID_LOG_INFO;
            break;
        default:
            break;
    }
    __android_log_vprint(priority, kLogTag, format, args);
}

void port_log_hex(
    esp_loader_port_t *,
    esp_loader_log_level_t,
    const char *,
    const uint8_t *,
    size_t) {
    // Hex dumps are intentionally suppressed to avoid leaking flash data into
    // Android logcat. Text warnings and errors remain enabled.
}

esp_loader_error_t port_change_baud(esp_loader_port_t *base, uint32_t baud_rate) {
    return set_baud_rate(as_android_port(base), baud_rate)
        ? ESP_LOADER_SUCCESS
        : ESP_LOADER_ERROR_FAIL;
}

esp_loader_error_t port_write(
    esp_loader_port_t *base,
    const uint8_t *data,
    uint16_t size,
    uint32_t timeout) {
    AndroidSerialPort *port = as_android_port(base);
    if (port->transport_failed || android_serial_port_is_cancelled(port)) {
        return ESP_LOADER_ERROR_FAIL;
    }

    ScopedEnv scoped(port->vm);
    JNIEnv *env = scoped.get();
    if (env == nullptr || port->write_method == nullptr) {
        mark_failed(port, "write is unavailable");
        return ESP_LOADER_ERROR_FAIL;
    }

    jbyteArray buffer = env->NewByteArray(size);
    if (buffer == nullptr) {
        mark_failed(port, "could not allocate write buffer");
        return ESP_LOADER_ERROR_FAIL;
    }
    env->SetByteArrayRegion(
        buffer,
        0,
        size,
        reinterpret_cast<const jbyte *>(data));
    if (has_java_exception(env, port, "could not copy write buffer")) {
        env->DeleteLocalRef(buffer);
        return ESP_LOADER_ERROR_FAIL;
    }

    const auto deadline =
        std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout);
    jint offset = 0;
    while (offset < size) {
        if (android_serial_port_is_cancelled(port)) {
            env->DeleteLocalRef(buffer);
            return ESP_LOADER_ERROR_FAIL;
        }
        const uint32_t remaining_timeout = millis_remaining(deadline, timeout);
        if (remaining_timeout == 0) {
            env->DeleteLocalRef(buffer);
            return ESP_LOADER_ERROR_TIMEOUT;
        }
        const jint transferred = env->CallIntMethod(
            port->transport,
            port->write_method,
            buffer,
            offset,
            static_cast<jint>(size) - offset,
            static_cast<jint>(remaining_timeout));
        if (has_java_exception(env, port, "transport write failed")) {
            env->DeleteLocalRef(buffer);
            return ESP_LOADER_ERROR_FAIL;
        }
        if (transferred < 0 || transferred > static_cast<jint>(size) - offset) {
            mark_failed(port, "transport write returned an invalid byte count");
            env->DeleteLocalRef(buffer);
            return ESP_LOADER_ERROR_FAIL;
        }
        if (transferred == 0) {
            delay_raw(1);
            continue;
        }
        offset += transferred;
    }

    env->DeleteLocalRef(buffer);
    return ESP_LOADER_SUCCESS;
}

esp_loader_error_t port_read(
    esp_loader_port_t *base,
    uint8_t *data,
    uint16_t size,
    uint32_t timeout) {
    AndroidSerialPort *port = as_android_port(base);
    if (port->transport_failed || android_serial_port_is_cancelled(port)) {
        return ESP_LOADER_ERROR_FAIL;
    }

    ScopedEnv scoped(port->vm);
    JNIEnv *env = scoped.get();
    if (env == nullptr || port->read_method == nullptr) {
        mark_failed(port, "read is unavailable");
        return ESP_LOADER_ERROR_FAIL;
    }

    jbyteArray buffer = env->NewByteArray(size);
    if (buffer == nullptr) {
        mark_failed(port, "could not allocate read buffer");
        return ESP_LOADER_ERROR_FAIL;
    }

    const auto deadline =
        std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout);
    jint offset = 0;
    while (offset < size) {
        if (android_serial_port_is_cancelled(port)) {
            env->DeleteLocalRef(buffer);
            return ESP_LOADER_ERROR_FAIL;
        }
        const uint32_t remaining_timeout = millis_remaining(deadline, timeout);
        if (remaining_timeout == 0) {
            env->DeleteLocalRef(buffer);
            return ESP_LOADER_ERROR_TIMEOUT;
        }
        const jint transferred = env->CallIntMethod(
            port->transport,
            port->read_method,
            buffer,
            offset,
            static_cast<jint>(size) - offset,
            static_cast<jint>(remaining_timeout));
        if (has_java_exception(env, port, "transport read failed")) {
            env->DeleteLocalRef(buffer);
            return ESP_LOADER_ERROR_FAIL;
        }
        if (transferred < 0 || transferred > static_cast<jint>(size) - offset) {
            mark_failed(port, "transport read returned an invalid byte count");
            env->DeleteLocalRef(buffer);
            return ESP_LOADER_ERROR_FAIL;
        }
        if (transferred == 0) {
            delay_raw(1);
            continue;
        }
        env->GetByteArrayRegion(
            buffer,
            offset,
            transferred,
            reinterpret_cast<jbyte *>(data + offset));
        if (has_java_exception(env, port, "could not copy read buffer")) {
            env->DeleteLocalRef(buffer);
            return ESP_LOADER_ERROR_FAIL;
        }
        offset += transferred;
    }

    env->DeleteLocalRef(buffer);
    return ESP_LOADER_SUCCESS;
}

}  // namespace

const esp_loader_port_ops_t android_serial_port_ops = {
    port_init,
    port_deinit,
    port_enter_bootloader,
    port_reset_target,
    port_start_timer,
    port_remaining_time,
    port_delay_ms,
    port_log,
    port_log_hex,
    port_change_baud,
    port_write,
    port_read,
    nullptr,
    nullptr,
    nullptr,
    nullptr,
};

bool android_serial_port_bind(
    JNIEnv *env,
    AndroidSerialPort *port,
    jobject transport,
    jobject callbacks,
    AndroidResetStrategy reset_strategy,
    uint32_t baud_rate,
    uint32_t reenumeration_timeout_ms) {
    if (env == nullptr || port == nullptr || transport == nullptr || callbacks == nullptr) {
        return false;
    }

    if (env->GetJavaVM(&port->vm) != JNI_OK) {
        return false;
    }
    port->transport = env->NewGlobalRef(transport);
    port->callbacks = env->NewGlobalRef(callbacks);
    if (port->transport == nullptr || port->callbacks == nullptr) {
        android_serial_port_release(env, port);
        return false;
    }

    jclass transport_class = env->GetObjectClass(transport);
    jclass callbacks_class = env->GetObjectClass(callbacks);
    if (transport_class == nullptr || callbacks_class == nullptr) {
        if (transport_class != nullptr) {
            env->DeleteLocalRef(transport_class);
        }
        if (callbacks_class != nullptr) {
            env->DeleteLocalRef(callbacks_class);
        }
        android_serial_port_release(env, port);
        return false;
    }

    port->write_method =
        env->GetMethodID(transport_class, "write", "([BIII)I");
    port->read_method =
        env->GetMethodID(transport_class, "read", "([BIII)I");
    port->set_baud_rate_method =
        env->GetMethodID(transport_class, "setBaudRate", "(I)Z");
    port->set_control_lines_method =
        env->GetMethodID(transport_class, "setControlLines", "(ZZ)Z");
    port->purge_input_method =
        env->GetMethodID(transport_class, "purgeInput", "()Z");
    port->await_reconnect_method =
        env->GetMethodID(transport_class, "awaitReconnect", "(I)Z");
    port->progress_method =
        env->GetMethodID(callbacks_class, "onNativeProgress", "(IIJJ)V");
    port->is_cancelled_method =
        env->GetMethodID(callbacks_class, "isCancelled", "()Z");

    env->DeleteLocalRef(transport_class);
    env->DeleteLocalRef(callbacks_class);

    if (env->ExceptionCheck() ||
        port->write_method == nullptr ||
        port->read_method == nullptr ||
        port->set_baud_rate_method == nullptr ||
        port->set_control_lines_method == nullptr ||
        port->purge_input_method == nullptr ||
        port->await_reconnect_method == nullptr ||
        port->progress_method == nullptr ||
        port->is_cancelled_method == nullptr) {
        return false;
    }

    port->base.ops = &android_serial_port_ops;
    port->reset_strategy = reset_strategy;
    port->baud_rate = baud_rate;
    port->reenumeration_timeout_ms = reenumeration_timeout_ms;
    return true;
}

void android_serial_port_release(JNIEnv *env, AndroidSerialPort *port) {
    if (env == nullptr || port == nullptr) {
        return;
    }
    if (port->transport != nullptr) {
        env->DeleteGlobalRef(port->transport);
        port->transport = nullptr;
    }
    if (port->callbacks != nullptr) {
        env->DeleteGlobalRef(port->callbacks);
        port->callbacks = nullptr;
    }
}

bool android_serial_port_is_cancelled(AndroidSerialPort *port) {
    if (port == nullptr || port->callbacks == nullptr ||
        port->is_cancelled_method == nullptr) {
        return false;
    }
    ScopedEnv scoped(port->vm);
    JNIEnv *env = scoped.get();
    if (env == nullptr) {
        mark_failed(port, "could not attach JNI thread for cancellation check");
        return true;
    }
    const jboolean result =
        env->CallBooleanMethod(port->callbacks, port->is_cancelled_method);
    if (has_java_exception(env, port, "cancellation callback failed")) {
        return true;
    }
    return result == JNI_TRUE;
}

bool android_serial_port_report_progress(
    AndroidSerialPort *port,
    int phase,
    int segment_index,
    int64_t completed,
    int64_t total) {
    if (port == nullptr || port->callbacks == nullptr ||
        port->progress_method == nullptr) {
        return false;
    }
    ScopedEnv scoped(port->vm);
    JNIEnv *env = scoped.get();
    if (env == nullptr) {
        mark_failed(port, "could not attach JNI thread for progress callback");
        return false;
    }
    env->CallVoidMethod(
        port->callbacks,
        port->progress_method,
        static_cast<jint>(phase),
        static_cast<jint>(segment_index),
        static_cast<jlong>(completed),
        static_cast<jlong>(total));
    return !has_java_exception(env, port, "progress callback failed");
}

