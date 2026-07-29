package io.bruceremote.app.firmware

enum class UsbBootTransport {
    CH9102_UART,
    NATIVE_USB_SERIAL_JTAG,
}

enum class ResetStrategy {
    CLASSIC_DTR_RTS,
    USB_JTAG_WITH_MANUAL_G0_FALLBACK,
}

data class DeviceProfile(
    val boardId: String,
    val displayName: String,
    val chip: EspChip,
    val flashSizeBytes: Long,
    val usbBootTransport: UsbBootTransport,
    val resetStrategy: ResetStrategy,
    val initialBaudRate: Int,
    val preferredFastBaudRate: Int?,
    val manualBootloaderInstructions: List<String>,
) {
    fun validate(target: FirmwareTarget) {
        if (target.boardId != boardId) {
            throw FirmwarePackageException.DeviceProfileMismatch(
                boardId,
                "manifest board_id is '${target.boardId}'",
            )
        }
        if (target.chip != chip) {
            throw FirmwarePackageException.DeviceProfileMismatch(
                boardId,
                "expected chip '${chip.wireName}', got '${target.chip.wireName}'",
            )
        }
        if (target.flashSizeBytes != flashSizeBytes) {
            throw FirmwarePackageException.DeviceProfileMismatch(
                boardId,
                "expected $flashSizeBytes-byte flash, got ${target.flashSizeBytes}",
            )
        }
    }
}

object DeviceProfiles {
    private const val EIGHT_MIB = 8L * 1024L * 1024L

    val M5STACK_CPLUS2 = DeviceProfile(
        boardId = "m5stack-cplus2",
        displayName = "M5StickC Plus2",
        chip = EspChip.ESP32,
        flashSizeBytes = EIGHT_MIB,
        usbBootTransport = UsbBootTransport.CH9102_UART,
        resetStrategy = ResetStrategy.CLASSIC_DTR_RTS,
        initialBaudRate = 115_200,
        preferredFastBaudRate = 921_600,
        manualBootloaderInstructions = listOf(
            "Disconnect USB.",
            "Fully power the M5StickC Plus2 off.",
            "Reconnect USB and retry.",
        ),
    )

    val CARDPUTER_ADV = DeviceProfile(
        boardId = "cardputer-adv",
        displayName = "M5Stack Cardputer ADV",
        chip = EspChip.ESP32_S3,
        flashSizeBytes = EIGHT_MIB,
        usbBootTransport = UsbBootTransport.NATIVE_USB_SERIAL_JTAG,
        resetStrategy = ResetStrategy.USB_JTAG_WITH_MANUAL_G0_FALLBACK,
        initialBaudRate = 115_200,
        preferredFastBaudRate = null,
        manualBootloaderInstructions = listOf(
            "Set the side power switch to OFF.",
            "Hold the G0 button.",
            "Connect USB power while continuing to hold G0.",
            "Release G0 after the USB device appears.",
        ),
    )

    private val byBoardId = listOf(M5STACK_CPLUS2, CARDPUTER_ADV)
        .associateBy(DeviceProfile::boardId)

    fun all(): List<DeviceProfile> = byBoardId.values.toList()

    fun require(boardId: String): DeviceProfile =
        byBoardId[boardId] ?: throw FirmwarePackageException.UnknownDeviceProfile(boardId)

    fun validate(target: FirmwareTarget): DeviceProfile =
        require(target.boardId).also { it.validate(target) }
}
