/*
 * SPDX-License-Identifier: Apache-2.0
 */
#include "android_serial_port.h"

#include <jni.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

extern "C" {
#include "esp_loader.h"
}

namespace {

constexpr int kErrorCancelled = 1001;
constexpr int kErrorChipMismatch = 1002;
constexpr int kErrorTransport = 1003;
constexpr int kErrorSecurityBlocked = 1004;
constexpr int kErrorJni = 1005;

constexpr uint32_t kInitialBaudRate = 115200;

enum class Phase : int {
    kConnecting = 0,
    kIdentifying = 1,
    kCheckingSecurity = 2,
    kLoadingStub = 3,
    kChangingBaud = 4,
    kFlashing = 5,
    kVerifying = 6,
    kResetting = 7,
    kComplete = 8,
};

struct DeviceSnapshot {
    target_chip_t chip = ESP_UNKNOWN_CHIP;
    uint32_t flash_size = 0;
    bool mac_available = false;
    std::array<uint8_t, 6> mac{};
    bool security_available = false;
    esp_loader_target_security_info_t security{};
};

class OperationSession {
public:
    explicit OperationSession(JNIEnv *env) : env_(env) {}

    ~OperationSession() {
        if (loader_initialized_) {
            esp_loader_deinit(&loader);
        }
        android_serial_port_release(env_, &port);
    }

    bool bind(
        jobject transport,
        jobject callbacks,
        jint reset_strategy,
        jint reenumeration_timeout_ms) {
        if (reset_strategy < static_cast<jint>(AndroidResetStrategy::kClassicDtrRts) ||
            reset_strategy > static_cast<jint>(AndroidResetStrategy::kManualBootloader)) {
            return false;
        }
        if (!android_serial_port_bind(
                env_,
                &port,
                transport,
                callbacks,
                static_cast<AndroidResetStrategy>(reset_strategy),
                kInitialBaudRate,
                static_cast<uint32_t>(reenumeration_timeout_ms))) {
            return false;
        }
        const esp_loader_error_t result =
            esp_loader_init_serial(&loader, &port.base);
        if (result != ESP_LOADER_SUCCESS) {
            return false;
        }
        loader_initialized_ = true;
        return true;
    }

    JNIEnv *env() const {
        return env_;
    }

    esp_loader_t loader{};
    AndroidSerialPort port{};

private:
    JNIEnv *env_ = nullptr;
    bool loader_initialized_ = false;
};

const char *chip_name(target_chip_t chip) {
    static constexpr const char *kNames[] = {
        "ESP8266",
        "ESP32",
        "ESP32-S2",
        "ESP32-C3",
        "ESP32-S3",
        "ESP32-C2",
        "ESP32-C5",
        "ESP32-H2",
        "ESP32-C6",
        "ESP32-P4",
        "ESP32-C61",
    };
    const auto index = static_cast<unsigned>(chip);
    return index < std::size(kNames) ? kNames[index] : "Unknown";
}

const char *loader_error_message(esp_loader_error_t error) {
    switch (error) {
        case ESP_LOADER_ERROR_FAIL:
            return "unspecified loader failure";
        case ESP_LOADER_ERROR_TIMEOUT:
            return "the target did not respond before the timeout";
        case ESP_LOADER_ERROR_IMAGE_SIZE:
            return "the image is larger than target flash";
        case ESP_LOADER_ERROR_INVALID_MD5:
            return "post-write MD5 verification failed";
        case ESP_LOADER_ERROR_INVALID_PARAM:
            return "esp-serial-flasher rejected a parameter";
        case ESP_LOADER_ERROR_INVALID_TARGET:
            return "the connected target is invalid";
        case ESP_LOADER_ERROR_UNSUPPORTED_CHIP:
            return "the connected chip is not supported";
        case ESP_LOADER_ERROR_UNSUPPORTED_FUNC:
            return "the target does not support this operation";
        case ESP_LOADER_ERROR_INVALID_RESPONSE:
            return "the target returned an invalid protocol response";
        case ESP_LOADER_SUCCESS:
            return "success";
    }
    return "unknown esp-serial-flasher error";
}

void throw_flasher_exception(
    JNIEnv *env,
    int code,
    const char *operation,
    const std::string &message) {
    if (env->ExceptionCheck()) {
        return;
    }
    jclass exception_class =
        env->FindClass("io/bruceremote/flasher/FlasherException");
    if (exception_class == nullptr) {
        env->ExceptionClear();
        jclass fallback = env->FindClass("java/lang/IllegalStateException");
        if (fallback != nullptr) {
            env->ThrowNew(fallback, message.c_str());
            env->DeleteLocalRef(fallback);
        }
        return;
    }

    jmethodID constructor = env->GetMethodID(
        exception_class,
        "<init>",
        "(ILjava/lang/String;Ljava/lang/String;)V");
    if (constructor == nullptr) {
        env->DeleteLocalRef(exception_class);
        return;
    }
    jstring operation_string = env->NewStringUTF(operation);
    jstring message_string = env->NewStringUTF(message.c_str());
    jobject exception = env->NewObject(
        exception_class,
        constructor,
        static_cast<jint>(code),
        operation_string,
        message_string);
    if (exception != nullptr) {
        env->Throw(static_cast<jthrowable>(exception));
        env->DeleteLocalRef(exception);
    }
    if (operation_string != nullptr) {
        env->DeleteLocalRef(operation_string);
    }
    if (message_string != nullptr) {
        env->DeleteLocalRef(message_string);
    }
    env->DeleteLocalRef(exception_class);
}

bool fail_if_cancelled(OperationSession &session, const char *operation) {
    if (!android_serial_port_is_cancelled(&session.port)) {
        return false;
    }
    if (!session.env()->ExceptionCheck()) {
        throw_flasher_exception(
            session.env(),
            kErrorCancelled,
            operation,
            "operation cancelled");
    }
    return true;
}

bool require_success(
    OperationSession &session,
    esp_loader_error_t result,
    const char *operation) {
    if (result == ESP_LOADER_SUCCESS) {
        return true;
    }
    if (fail_if_cancelled(session, operation)) {
        return false;
    }
    if (session.env()->ExceptionCheck()) {
        return false;
    }
    if (session.port.transport_failed) {
        throw_flasher_exception(
            session.env(),
            kErrorTransport,
            operation,
            session.port.transport_failure.empty()
                ? "serial transport failed"
                : session.port.transport_failure);
    } else {
        throw_flasher_exception(
            session.env(),
            static_cast<int>(result),
            operation,
            loader_error_message(result));
    }
    return false;
}

bool report(
    OperationSession &session,
    Phase phase,
    int segment_index,
    int64_t completed,
    int64_t total) {
    if (fail_if_cancelled(session, "progress")) {
        return false;
    }
    if (!android_serial_port_report_progress(
            &session.port,
            static_cast<int>(phase),
            segment_index,
            completed,
            total)) {
        if (!session.env()->ExceptionCheck()) {
            throw_flasher_exception(
                session.env(),
                kErrorJni,
                "progress",
                "progress callback failed");
        }
        return false;
    }
    return true;
}

bool connect(
    OperationSession &session,
    jint sync_timeout_ms,
    jint connect_trials,
    bool with_stub) {
    esp_loader_connect_args_t args = {
        .sync_timeout = static_cast<uint32_t>(sync_timeout_ms),
        .trials = static_cast<int32_t>(connect_trials),
    };
    const esp_loader_error_t result = with_stub
        ? esp_loader_connect_with_stub(&session.loader, &args)
        : esp_loader_connect(&session.loader, &args);
    return require_success(
        session,
        result,
        with_stub ? "load flasher stub" : "connect");
}

bool enforce_expected_chip(
    OperationSession &session,
    jint expected_chip,
    const char *operation) {
    const target_chip_t actual = esp_loader_get_target(&session.loader);
    if (expected_chip < 0 || actual == static_cast<target_chip_t>(expected_chip)) {
        return true;
    }
    std::string message = "expected ";
    message += chip_name(static_cast<target_chip_t>(expected_chip));
    message += " but connected target is ";
    message += chip_name(actual);
    throw_flasher_exception(
        session.env(),
        kErrorChipMismatch,
        operation,
        message);
    return false;
}

DeviceSnapshot read_snapshot(OperationSession &session) {
    DeviceSnapshot snapshot;
    snapshot.chip = esp_loader_get_target(&session.loader);

    uint32_t flash_size = 0;
    if (esp_loader_flash_detect_size(&session.loader, &flash_size) ==
        ESP_LOADER_SUCCESS) {
        snapshot.flash_size = flash_size;
    }

    if (esp_loader_read_mac(&session.loader, snapshot.mac.data()) ==
        ESP_LOADER_SUCCESS) {
        snapshot.mac_available = true;
    }

    esp_loader_target_security_info_t security{};
    if (esp_loader_get_security_info(&session.loader, &security) ==
        ESP_LOADER_SUCCESS) {
        snapshot.security_available = true;
        snapshot.security = security;
    }
    return snapshot;
}

bool has_blocking_security_state(const DeviceSnapshot &snapshot) {
    if (!snapshot.security_available) {
        return false;
    }
    return snapshot.security.secure_boot_enabled ||
           snapshot.security.flash_encryption_enabled ||
           snapshot.security.secure_download_mode_enabled;
}

jobject make_device_info(JNIEnv *env, const DeviceSnapshot &snapshot) {
    jclass info_class =
        env->FindClass("io/bruceremote/flasher/NativeDeviceInfo");
    if (info_class == nullptr) {
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(info_class, "<init>", "()V");
    if (constructor == nullptr) {
        env->DeleteLocalRef(info_class);
        return nullptr;
    }
    jobject info = env->NewObject(info_class, constructor);
    if (info == nullptr) {
        env->DeleteLocalRef(info_class);
        return nullptr;
    }

    auto int_field = [&](const char *name, jint value) {
        jfieldID field = env->GetFieldID(info_class, name, "I");
        if (field != nullptr) {
            env->SetIntField(info, field, value);
        }
    };
    auto long_field = [&](const char *name, jlong value) {
        jfieldID field = env->GetFieldID(info_class, name, "J");
        if (field != nullptr) {
            env->SetLongField(info, field, value);
        }
    };
    auto bool_field = [&](const char *name, bool value) {
        jfieldID field = env->GetFieldID(info_class, name, "Z");
        if (field != nullptr) {
            env->SetBooleanField(info, field, static_cast<jboolean>(value));
        }
    };

    int_field("chipCode", static_cast<jint>(snapshot.chip));
    jfieldID chip_name_field =
        env->GetFieldID(info_class, "chipName", "Ljava/lang/String;");
    if (chip_name_field != nullptr) {
        jstring name = env->NewStringUTF(chip_name(snapshot.chip));
        env->SetObjectField(info, chip_name_field, name);
        env->DeleteLocalRef(name);
    }
    long_field("flashSizeBytes", static_cast<jlong>(snapshot.flash_size));

    if (snapshot.mac_available) {
        jfieldID mac_field = env->GetFieldID(info_class, "macAddress", "[B");
        jbyteArray mac = env->NewByteArray(snapshot.mac.size());
        if (mac_field != nullptr && mac != nullptr) {
            env->SetByteArrayRegion(
                mac,
                0,
                snapshot.mac.size(),
                reinterpret_cast<const jbyte *>(snapshot.mac.data()));
            env->SetObjectField(info, mac_field, mac);
        }
        if (mac != nullptr) {
            env->DeleteLocalRef(mac);
        }
    }

    bool_field("securityInfoAvailable", snapshot.security_available);
    if (snapshot.security_available) {
        long_field("ecoVersion", snapshot.security.eco_version);
        bool_field("secureBootEnabled", snapshot.security.secure_boot_enabled);
        bool_field(
            "secureBootAggressiveRevokeEnabled",
            snapshot.security.secure_boot_aggressive_revoke_enabled);
        bool_field(
            "secureDownloadModeEnabled",
            snapshot.security.secure_download_mode_enabled);
        int revoked_mask = 0;
        for (int index = 0; index < 3; ++index) {
            if (snapshot.security.secure_boot_revoked_keys[index]) {
                revoked_mask |= (1 << index);
            }
        }
        int_field("secureBootRevokedKeyMask", revoked_mask);
        bool_field(
            "jtagSoftwareDisabled",
            snapshot.security.jtag_software_disabled);
        bool_field(
            "jtagHardwareDisabled",
            snapshot.security.jtag_hardware_disabled);
        bool_field("usbDisabled", snapshot.security.usb_disabled);
        bool_field(
            "flashEncryptionEnabled",
            snapshot.security.flash_encryption_enabled);
        bool_field(
            "dcacheInUartDownloadDisabled",
            snapshot.security.dcache_in_uart_download_disabled);
        bool_field(
            "icacheInUartDownloadDisabled",
            snapshot.security.icache_in_uart_download_disabled);
    }

    env->DeleteLocalRef(info_class);
    return info;
}

bool reset_after_operation(OperationSession &session, bool reset_after) {
    if (!reset_after) {
        return true;
    }
    if (!report(session, Phase::kResetting, -1, 0, 0)) {
        return false;
    }
    esp_loader_reset_target(&session.loader);
    if (session.env()->ExceptionCheck()) {
        return false;
    }
    if (session.port.transport_failed) {
        throw_flasher_exception(
            session.env(),
            kErrorTransport,
            "reset",
            session.port.transport_failure.empty()
                ? "target reset failed"
                : session.port.transport_failure);
        return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_io_bruceremote_flasher_EspSerialFlasher_nativeIdentify(
    JNIEnv *env,
    jobject,
    jobject transport,
    jobject callbacks,
    jint reset_strategy,
    jint expected_chip,
    jint sync_timeout_ms,
    jint connect_trials,
    jboolean reset_after,
    jint reenumeration_timeout_ms) {
    OperationSession session(env);
    if (!session.bind(
            transport,
            callbacks,
            reset_strategy,
            reenumeration_timeout_ms)) {
        if (!env->ExceptionCheck()) {
            throw_flasher_exception(
                env,
                kErrorJni,
                "initialize",
                "could not bind the Android serial transport");
        }
        return nullptr;
    }

    if (!report(session, Phase::kConnecting, -1, 0, 0) ||
        !connect(session, sync_timeout_ms, connect_trials, false) ||
        !enforce_expected_chip(session, expected_chip, "identify") ||
        !report(session, Phase::kIdentifying, -1, 0, 0)) {
        return nullptr;
    }

    const DeviceSnapshot snapshot = read_snapshot(session);
    if (env->ExceptionCheck() ||
        !report(session, Phase::kCheckingSecurity, -1, 0, 0) ||
        !reset_after_operation(session, reset_after == JNI_TRUE) ||
        !report(session, Phase::kComplete, -1, 0, 0)) {
        return nullptr;
    }
    return make_device_info(env, snapshot);
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_bruceremote_flasher_EspSerialFlasher_nativeFlash(
    JNIEnv *env,
    jobject,
    jobject transport,
    jobject callbacks,
    jint reset_strategy,
    jint expected_chip,
    jintArray addresses,
    jobjectArray images,
    jboolean use_stub,
    jboolean verify,
    jint block_size,
    jint flash_baud_rate,
    jint sync_timeout_ms,
    jint connect_trials,
    jboolean reset_after,
    jint reenumeration_timeout_ms,
    jboolean allow_security_risks) {
    if (addresses == nullptr || images == nullptr ||
        env->GetArrayLength(addresses) != env->GetArrayLength(images) ||
        env->GetArrayLength(addresses) == 0) {
        throw_flasher_exception(
            env,
            ESP_LOADER_ERROR_INVALID_PARAM,
            "validate",
            "addresses and images must have the same non-zero length");
        return nullptr;
    }

    OperationSession session(env);
    if (!session.bind(
            transport,
            callbacks,
            reset_strategy,
            reenumeration_timeout_ms)) {
        if (!env->ExceptionCheck()) {
            throw_flasher_exception(
                env,
                kErrorJni,
                "initialize",
                "could not bind the Android serial transport");
        }
        return nullptr;
    }

    if (!report(session, Phase::kConnecting, -1, 0, 0) ||
        !connect(session, sync_timeout_ms, connect_trials, false) ||
        !enforce_expected_chip(session, expected_chip, "pre-flash chip check") ||
        !report(session, Phase::kIdentifying, -1, 0, 0)) {
        return nullptr;
    }

    DeviceSnapshot snapshot = read_snapshot(session);
    if (!report(session, Phase::kCheckingSecurity, -1, 0, 0)) {
        return nullptr;
    }
    if (allow_security_risks != JNI_TRUE &&
        has_blocking_security_state(snapshot)) {
        throw_flasher_exception(
            env,
            kErrorSecurityBlocked,
            "security check",
            "target reports Secure Boot, flash encryption, or secure-download mode");
        return nullptr;
    }

    if (use_stub == JNI_TRUE) {
        if (!report(session, Phase::kLoadingStub, -1, 0, 0) ||
            !connect(session, sync_timeout_ms, connect_trials, true) ||
            !enforce_expected_chip(session, expected_chip, "stub chip check")) {
            return nullptr;
        }
    }

    if (flash_baud_rate != static_cast<jint>(kInitialBaudRate)) {
        if (!report(session, Phase::kChangingBaud, -1, 0, 0) ||
            !require_success(
                session,
                esp_loader_change_transmission_rate(
                    &session.loader,
                    static_cast<uint32_t>(flash_baud_rate)),
                "change baud rate")) {
            return nullptr;
        }
    }

    const jsize image_count = env->GetArrayLength(images);
    std::vector<uint32_t> offsets(static_cast<size_t>(image_count));
    env->GetIntArrayRegion(
        addresses,
        0,
        image_count,
        reinterpret_cast<jint *>(offsets.data()));
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    int64_t total_bytes = 0;
    for (jsize index = 0; index < image_count; ++index) {
        jbyteArray image =
            static_cast<jbyteArray>(env->GetObjectArrayElement(images, index));
        if (image == nullptr) {
            throw_flasher_exception(
                env,
                ESP_LOADER_ERROR_INVALID_PARAM,
                "validate",
                "image array contains null");
            return nullptr;
        }
        const int64_t length = env->GetArrayLength(image);
        env->DeleteLocalRef(image);
        total_bytes += (length + 3) & ~int64_t{3};
    }

    int64_t completed_bytes = 0;
    std::vector<uint8_t> block(static_cast<size_t>(block_size));
    for (jsize index = 0; index < image_count; ++index) {
        jbyteArray image =
            static_cast<jbyteArray>(env->GetObjectArrayElement(images, index));
        if (image == nullptr) {
            return nullptr;
        }
        const uint32_t image_length =
            static_cast<uint32_t>(env->GetArrayLength(image));
        const uint32_t padded_length = (image_length + 3U) & ~uint32_t{3};

        esp_loader_flash_cfg_t flash_config{};
        flash_config.offset = offsets[static_cast<size_t>(index)];
        flash_config.image_size = padded_length;
        flash_config.block_size = static_cast<uint32_t>(block_size);
        flash_config.skip_verify = verify != JNI_TRUE;

        if (!report(
                session,
                Phase::kFlashing,
                index,
                completed_bytes,
                total_bytes) ||
            !require_success(
                session,
                esp_loader_flash_start(&session.loader, &flash_config),
                "start flash")) {
            env->DeleteLocalRef(image);
            return nullptr;
        }

        uint32_t position = 0;
        while (position < padded_length) {
            if (fail_if_cancelled(session, "flash")) {
                env->DeleteLocalRef(image);
                return nullptr;
            }
            const uint32_t chunk_size = std::min<uint32_t>(
                static_cast<uint32_t>(block_size),
                padded_length - position);
            std::fill(block.begin(), block.begin() + chunk_size, 0xff);
            if (position < image_length) {
                const uint32_t source_size =
                    std::min<uint32_t>(chunk_size, image_length - position);
                env->GetByteArrayRegion(
                    image,
                    position,
                    source_size,
                    reinterpret_cast<jbyte *>(block.data()));
                if (env->ExceptionCheck()) {
                    env->DeleteLocalRef(image);
                    return nullptr;
                }
            }
            if (!require_success(
                    session,
                    esp_loader_flash_write(
                        &session.loader,
                        &flash_config,
                        block.data(),
                        chunk_size),
                    "write flash")) {
                env->DeleteLocalRef(image);
                return nullptr;
            }
            position += chunk_size;
            completed_bytes += chunk_size;
            if (!report(
                    session,
                    Phase::kFlashing,
                    index,
                    completed_bytes,
                    total_bytes)) {
                env->DeleteLocalRef(image);
                return nullptr;
            }
        }

        if (!report(
                session,
                Phase::kVerifying,
                index,
                completed_bytes,
                total_bytes) ||
            !require_success(
                session,
                esp_loader_flash_finish(&session.loader, &flash_config),
                verify == JNI_TRUE ? "verify flash" : "finish flash")) {
            env->DeleteLocalRef(image);
            return nullptr;
        }
        env->DeleteLocalRef(image);
    }

    if (!reset_after_operation(session, reset_after == JNI_TRUE) ||
        !report(
            session,
            Phase::kComplete,
            image_count - 1,
            completed_bytes,
            total_bytes)) {
        return nullptr;
    }
    return make_device_info(env, snapshot);
}

