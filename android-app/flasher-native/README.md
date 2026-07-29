# Native ESP serial flasher

This Android library wraps Espressif
[`esp-serial-flasher` v2.0.0](https://github.com/espressif/esp-serial-flasher/releases/tag/v2.0.0)
through JNI. The exact vendored source is at
`../third_party/esp-serial-flasher` (commit
`ca7edfcef903a4075332f0ad6c8fbda1e2cb9a9d`).

The module deliberately does not depend on a particular Android USB serial
library. The application supplies a synchronous `SerialTransportBridge`.
This permits the same native flasher to work with:

- the CH9102 USB-to-UART bridge in an M5StickC Plus2, using
  `ResetStrategy.CLASSIC_DTR_RTS`; and
- the ESP32-S3 USB Serial/JTAG endpoint in a Cardputer or Cardputer ADV,
  using `ResetStrategy.USB_JTAG`.

The bridge must have exclusive access to the serial port for the duration of
an operation. Stop any asynchronous serial reader before calling `identify`
or `flash`. Both functions are blocking and must be called off Android's main
thread.

For USB Serial/JTAG, changing DTR/RTS resets the target and temporarily
removes the USB device. `SerialTransportBridge.awaitReconnect` must wait for
reattachment, reopen the port (including Android USB permission if needed),
and make subsequent bridge calls use the new connection.

Example:

```kotlin
val flasher = EspSerialFlasher(
    transport = bridge,
    resetStrategy = ResetStrategy.USB_JTAG,
)

val info = flasher.identify(expectedChip = EspChip.ESP32_S3)

val result = flasher.flash(
    segments = listOf(FlashSegment(0, mergedFirmwareBytes, "Bruce")),
    options = FlashOptions(expectedChip = EspChip.ESP32_S3),
    progressListener = FlashProgressListener { progress ->
        println("${progress.phase}: ${progress.bytesCompleted}/${progress.totalBytes}")
    },
)
```

The native layer checks the connected chip before writing. By default it
also refuses to write when the target reports Secure Boot, flash encryption,
or secure-download mode. ESP32 (the StickC Plus2 chip) does not implement
the ROM `GET_SECURITY_INFO` command, so `DeviceInfo.security.available` is
false for that target.

To add this isolated module to the application later:

```kotlin
// settings.gradle.kts
include(":flasher-native")

// app/build.gradle.kts
implementation(project(":flasher-native"))
```

## Licensing

The wrapper code is part of this project. Espressif's vendored
`esp-serial-flasher` is Apache-2.0 licensed; its complete license text remains
at `../third_party/esp-serial-flasher/LICENSE`. See
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

