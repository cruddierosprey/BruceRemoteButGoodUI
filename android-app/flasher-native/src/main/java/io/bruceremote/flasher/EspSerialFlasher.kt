package io.bruceremote.flasher

import java.util.Locale

enum class EspChip(internal val nativeCode: Int, val displayName: String) {
    ESP8266(0, "ESP8266"),
    ESP32(1, "ESP32"),
    ESP32_S2(2, "ESP32-S2"),
    ESP32_C3(3, "ESP32-C3"),
    ESP32_S3(4, "ESP32-S3"),
    ESP32_C2(5, "ESP32-C2"),
    ESP32_C5(6, "ESP32-C5"),
    ESP32_H2(7, "ESP32-H2"),
    ESP32_C6(8, "ESP32-C6"),
    ESP32_P4(9, "ESP32-P4"),
    ESP32_C61(10, "ESP32-C61"),
    UNKNOWN(11, "Unknown"),
    ;

    internal companion object {
        fun fromNative(code: Int): EspChip =
            entries.firstOrNull { it.nativeCode == code } ?: UNKNOWN
    }
}

enum class ResetStrategy(internal val nativeCode: Int) {
    /** CH9102/CH340/CP210x-style EN/GPIO0 auto-reset wiring. */
    CLASSIC_DTR_RTS(1),

    /** ESP32-S3 built-in USB Serial/JTAG reset with USB re-enumeration. */
    USB_JTAG(2),

    /** Do not toggle lines; the user has already put the target in download mode. */
    MANUAL_BOOTLOADER(3),
}

data class FlashSegment(
    val offset: Long,
    val data: ByteArray,
    val name: String = "image",
)

data class SecurityInfo(
    val available: Boolean,
    val ecoVersion: Long,
    val secureBootEnabled: Boolean,
    val secureBootAggressiveRevokeEnabled: Boolean,
    val secureDownloadModeEnabled: Boolean,
    val secureBootRevokedKeyMask: Int,
    val jtagSoftwareDisabled: Boolean,
    val jtagHardwareDisabled: Boolean,
    val usbDisabled: Boolean,
    val flashEncryptionEnabled: Boolean,
    val dcacheInUartDownloadDisabled: Boolean,
    val icacheInUartDownloadDisabled: Boolean,
)

data class DeviceInfo(
    val chip: EspChip,
    val chipName: String,
    val flashSizeBytes: Long?,
    val macAddress: String?,
    val security: SecurityInfo,
)

data class IdentifyOptions(
    val syncTimeoutMillis: Int = 100,
    val connectTrials: Int = 10,
    val resetAfter: Boolean = true,
    val reenumerationTimeoutMillis: Int = 10_000,
)

data class FlashOptions(
    val expectedChip: EspChip,
    val useStub: Boolean = true,
    val verify: Boolean = true,
    val blockSize: Int = 1_024,
    val flashBaudRate: Int = 115_200,
    val syncTimeoutMillis: Int = 100,
    val connectTrials: Int = 10,
    val resetAfter: Boolean = true,
    val reenumerationTimeoutMillis: Int = 10_000,
    /**
     * Opt-in escape hatch for intentionally signed/encrypted images. Leave false
     * for patched Bruce images.
     */
    val allowSecurityRisks: Boolean = false,
)

enum class FlashPhase(internal val nativeCode: Int) {
    CONNECTING(0),
    IDENTIFYING(1),
    CHECKING_SECURITY(2),
    LOADING_STUB(3),
    CHANGING_BAUD(4),
    FLASHING(5),
    VERIFYING(6),
    RESETTING(7),
    COMPLETE(8),
    ;

    internal companion object {
        fun fromNative(code: Int): FlashPhase =
            entries.firstOrNull { it.nativeCode == code } ?: FLASHING
    }
}

data class FlashProgress(
    val phase: FlashPhase,
    val segmentIndex: Int,
    val bytesCompleted: Long,
    val totalBytes: Long,
)

fun interface FlashProgressListener {
    fun onProgress(progress: FlashProgress)
}

fun interface CancellationSignal {
    fun isCancellationRequested(): Boolean

    companion object {
        val NONE = CancellationSignal { false }
    }
}

class FlasherCancellationToken : CancellationSignal {
    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    override fun isCancellationRequested(): Boolean = cancelled
}

enum class FlasherError(internal val nativeCode: Int) {
    LOADER_FAILURE(1),
    TIMEOUT(2),
    IMAGE_SIZE(3),
    INVALID_MD5(4),
    INVALID_PARAMETER(5),
    INVALID_TARGET(6),
    UNSUPPORTED_CHIP(7),
    UNSUPPORTED_FUNCTION(8),
    INVALID_RESPONSE(9),
    CANCELLED(1_001),
    CHIP_MISMATCH(1_002),
    TRANSPORT(1_003),
    SECURITY_BLOCKED(1_004),
    JNI(1_005),
    UNKNOWN(Int.MIN_VALUE),
    ;

    internal companion object {
        fun fromNative(code: Int): FlasherError =
            entries.firstOrNull { it.nativeCode == code } ?: UNKNOWN
    }
}

class FlasherException(
    val nativeCode: Int,
    val operation: String,
    detail: String,
) : RuntimeException("$operation: $detail") {
    val error: FlasherError = FlasherError.fromNative(nativeCode)
}

/**
 * Blocking JNI facade over Espressif esp-serial-flasher v2.0.0.
 *
 * Call [identify] and [flash] from a background thread.
 */
class EspSerialFlasher(
    private val transport: SerialTransportBridge,
    private val resetStrategy: ResetStrategy,
) {
    private val operationLock = Any()

    fun identify(
        expectedChip: EspChip? = null,
        options: IdentifyOptions = IdentifyOptions(),
        progressListener: FlashProgressListener? = null,
        cancellationSignal: CancellationSignal = CancellationSignal.NONE,
    ): DeviceInfo = synchronized(operationLock) {
        require(expectedChip != EspChip.UNKNOWN) { "UNKNOWN cannot be used as an expected chip" }
        validateConnectionOptions(
            options.syncTimeoutMillis,
            options.connectTrials,
            options.reenumerationTimeoutMillis,
        )
        nativeIdentify(
            transport = transport,
            callbacks = NativeCallbacks(progressListener, cancellationSignal),
            resetStrategy = resetStrategy.nativeCode,
            expectedChip = expectedChip?.nativeCode ?: -1,
            syncTimeoutMillis = options.syncTimeoutMillis,
            connectTrials = options.connectTrials,
            resetAfter = options.resetAfter,
            reenumerationTimeoutMillis = options.reenumerationTimeoutMillis,
        ).toPublic()
    }

    fun flash(
        segments: List<FlashSegment>,
        options: FlashOptions,
        progressListener: FlashProgressListener? = null,
        cancellationSignal: CancellationSignal = CancellationSignal.NONE,
    ): DeviceInfo = synchronized(operationLock) {
        validateFlash(segments, options)
        nativeFlash(
            transport = transport,
            callbacks = NativeCallbacks(progressListener, cancellationSignal),
            resetStrategy = resetStrategy.nativeCode,
            expectedChip = options.expectedChip.nativeCode,
            addresses = IntArray(segments.size) { segments[it].offset.toInt() },
            images = Array(segments.size) { segments[it].data },
            useStub = options.useStub,
            verify = options.verify,
            blockSize = options.blockSize,
            flashBaudRate = options.flashBaudRate,
            syncTimeoutMillis = options.syncTimeoutMillis,
            connectTrials = options.connectTrials,
            resetAfter = options.resetAfter,
            reenumerationTimeoutMillis = options.reenumerationTimeoutMillis,
            allowSecurityRisks = options.allowSecurityRisks,
        ).toPublic()
    }

    private fun validateFlash(segments: List<FlashSegment>, options: FlashOptions) {
        require(options.expectedChip != EspChip.UNKNOWN) {
            "A concrete expected chip is required before writing flash"
        }
        require(segments.isNotEmpty()) { "At least one flash segment is required" }
        require(options.blockSize in 256..16_384 && options.blockSize % 4 == 0) {
            "blockSize must be a 4-byte-aligned value from 256 to 16384"
        }
        require(options.flashBaudRate in 9_600..4_000_000) {
            "flashBaudRate must be between 9600 and 4000000"
        }
        validateConnectionOptions(
            options.syncTimeoutMillis,
            options.connectTrials,
            options.reenumerationTimeoutMillis,
        )

        val ranges = segments.mapIndexed { index, segment ->
            require(segment.offset in 0..UINT32_MAX) {
                "Segment $index offset is outside the 32-bit ESP flash address space"
            }
            require(segment.offset % 4L == 0L) {
                "Segment $index offset must be 4-byte aligned"
            }
            require(segment.data.isNotEmpty()) { "Segment $index is empty" }
            val paddedSize = (segment.data.size.toLong() + 3L) and -4L
            require(segment.offset + paddedSize <= UINT32_SPACE_SIZE) {
                "Segment $index extends past the 32-bit address space"
            }
            segment.offset until (segment.offset + paddedSize)
        }.sortedBy { it.first }

        ranges.zipWithNext().forEach { (left, right) ->
            require(left.last < right.first) {
                "Flash segments overlap at 0x${right.first.toString(16)}"
            }
        }
    }

    private fun validateConnectionOptions(
        syncTimeoutMillis: Int,
        connectTrials: Int,
        reenumerationTimeoutMillis: Int,
    ) {
        require(syncTimeoutMillis in 10..60_000) {
            "syncTimeoutMillis must be between 10 and 60000"
        }
        require(connectTrials in 1..100) { "connectTrials must be between 1 and 100" }
        require(reenumerationTimeoutMillis in 1_000..120_000) {
            "reenumerationTimeoutMillis must be between 1000 and 120000"
        }
    }

    private external fun nativeIdentify(
        transport: SerialTransportBridge,
        callbacks: NativeCallbacks,
        resetStrategy: Int,
        expectedChip: Int,
        syncTimeoutMillis: Int,
        connectTrials: Int,
        resetAfter: Boolean,
        reenumerationTimeoutMillis: Int,
    ): NativeDeviceInfo

    private external fun nativeFlash(
        transport: SerialTransportBridge,
        callbacks: NativeCallbacks,
        resetStrategy: Int,
        expectedChip: Int,
        addresses: IntArray,
        images: Array<ByteArray>,
        useStub: Boolean,
        verify: Boolean,
        blockSize: Int,
        flashBaudRate: Int,
        syncTimeoutMillis: Int,
        connectTrials: Int,
        resetAfter: Boolean,
        reenumerationTimeoutMillis: Int,
        allowSecurityRisks: Boolean,
    ): NativeDeviceInfo

    private companion object {
        const val UINT32_MAX = 0xffff_ffffL
        const val UINT32_SPACE_SIZE = 0x1_0000_0000L

        init {
            System.loadLibrary("bruce_flasher")
        }
    }
}

@Suppress("unused")
internal class NativeCallbacks(
    private val listener: FlashProgressListener?,
    private val cancellationSignal: CancellationSignal,
) {
    fun onNativeProgress(
        phaseCode: Int,
        segmentIndex: Int,
        bytesCompleted: Long,
        totalBytes: Long,
    ) {
        listener?.onProgress(
            FlashProgress(
                phase = FlashPhase.fromNative(phaseCode),
                segmentIndex = segmentIndex,
                bytesCompleted = bytesCompleted,
                totalBytes = totalBytes,
            ),
        )
    }

    fun isCancelled(): Boolean = cancellationSignal.isCancellationRequested()
}

@Suppress("unused")
internal class NativeDeviceInfo {
    @JvmField var chipCode: Int = EspChip.UNKNOWN.nativeCode
    @JvmField var chipName: String = EspChip.UNKNOWN.displayName
    @JvmField var flashSizeBytes: Long = 0L
    @JvmField var macAddress: ByteArray? = null
    @JvmField var securityInfoAvailable: Boolean = false
    @JvmField var ecoVersion: Long = 0L
    @JvmField var secureBootEnabled: Boolean = false
    @JvmField var secureBootAggressiveRevokeEnabled: Boolean = false
    @JvmField var secureDownloadModeEnabled: Boolean = false
    @JvmField var secureBootRevokedKeyMask: Int = 0
    @JvmField var jtagSoftwareDisabled: Boolean = false
    @JvmField var jtagHardwareDisabled: Boolean = false
    @JvmField var usbDisabled: Boolean = false
    @JvmField var flashEncryptionEnabled: Boolean = false
    @JvmField var dcacheInUartDownloadDisabled: Boolean = false
    @JvmField var icacheInUartDownloadDisabled: Boolean = false

    fun toPublic(): DeviceInfo = DeviceInfo(
        chip = EspChip.fromNative(chipCode),
        chipName = chipName,
        flashSizeBytes = flashSizeBytes.takeIf { it > 0L },
        macAddress = macAddress?.takeIf { it.size == 6 }?.joinToString(":") {
            String.format(Locale.US, "%02X", it.toInt() and 0xff)
        },
        security = SecurityInfo(
            available = securityInfoAvailable,
            ecoVersion = ecoVersion,
            secureBootEnabled = secureBootEnabled,
            secureBootAggressiveRevokeEnabled = secureBootAggressiveRevokeEnabled,
            secureDownloadModeEnabled = secureDownloadModeEnabled,
            secureBootRevokedKeyMask = secureBootRevokedKeyMask,
            jtagSoftwareDisabled = jtagSoftwareDisabled,
            jtagHardwareDisabled = jtagHardwareDisabled,
            usbDisabled = usbDisabled,
            flashEncryptionEnabled = flashEncryptionEnabled,
            dcacheInUartDownloadDisabled = dcacheInUartDownloadDisabled,
            icacheInUartDownloadDisabled = icacheInUartDownloadDisabled,
        ),
    )
}

