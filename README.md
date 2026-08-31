# Bruce Remote — Good UI

A cleaner, phone-first Android remote controller for **Bruce firmware** running on ESP32 / ESP32-S3 devices.

This project is based on the excellent [BruceRemote](https://github.com/floatme/BruceRemote) project, with the goal of keeping its working USB/serial control layer while providing a more polished and practical Android interface for devices that may have **no physical screen or buttons at all**.

> **Tested successfully on:** ESP32-S3 N16R8 (16 MB Flash / 8 MB PSRAM) running stock Bruce firmware, controlled directly from an Android phone over USB.

## Why this exists

Bruce works great on devices with a built-in display and buttons, but generic ESP32-S3 development boards often have neither.

Bruce Remote — Good UI turns the Android phone into the device interface:

```text
ESP32-S3 running Bruce
        │
        │ USB / OTG
        ▼
Android phone
        │
        ├── Live Bruce screen preview
        ├── D-Pad + OK + Back / ESC
        ├── Bruce menu shortcuts
        ├── Serial terminal
        └── Device / connection information
```

No OLED, joystick, buttons, jumper wires or external controller are required for the tested setup.

## Features

- USB Host / OTG connection to compatible ESP32 devices
- Native ESP32-S3 USB serial support
- 115200 8-N-1 Bruce serial communication
- Live Bruce display preview using `display start`
- Large touch-friendly D-Pad
- OK / Select and Back / ESC controls
- Bruce menu discovery and navigation
- Direct menu shortcuts from Android
- Built-in serial terminal
- Device and USB connection information
- Portrait and landscape layouts
- Graceful USB disconnect / reconnect handling
- Dark, mobile-first UI
- No root required
- Bruce remains authoritative — the Android app controls the firmware instead of reimplementing Bruce features

## Tested hardware

The setup below has been tested with the application:

### ESP32-S3 N16R8

- ESP32-S3
- 16 MB Flash
- 8 MB PSRAM
- Dual USB-C development board
- Stock Bruce firmware
- Android phone with USB Host / OTG support

On the tested dual-USB board:

- **COM USB-C port** → used for firmware flashing / USB-UART
- **USB USB-C port** → ESP32-S3 native USB; use this port for the Android remote

Other Bruce-compatible boards may also work when they expose Bruce's serial command interface over a USB serial connection.

## Bruce firmware

The tested configuration uses the **original Bruce firmware**, not a custom display firmware.

For the ESP32-S3 N16R8 board used during development, the working Bruce flasher target was:

```text
ESP32 S3 PSRAM
└── esp32-s3-devkitc-1-psram
```

Official Bruce project:

- https://github.com/BruceDevices/firmware
- https://bruce.computer

After flashing, connect the board's **native USB port** to the Android phone for remote control.

## Android requirements

- Android device with USB Host / OTG support
- USB data cable — charge-only cables will not work
- Compatible ESP32 / ESP32-S3 running Bruce
- Android 8.0+ (`minSdk 26` in the current Android project)

When Android asks whether the application may access the USB device, grant permission.

## Quick start

1. Flash compatible Bruce firmware to the ESP32 device.
2. Disconnect the board from the PC.
3. Connect the board's native USB port to the Android phone with a data-capable USB cable / OTG connection.
4. Open Bruce Remote — Good UI.
5. Grant the Android USB permission when requested.
6. Tap **Connect**.
7. Start the live display stream if it is not already active.
8. Control Bruce with the on-screen D-Pad, **OK**, and **Back / ESC**.

The app also exposes Bruce's current menu options above the screen preview, allowing direct menu selection where supported.

## App screens

### Remote

The main controller view contains:

- USB connection status
- Current Bruce menu options
- Live Bruce screen preview
- D-Pad
- OK / Select
- Back / ESC

This is the primary screen for using a headless ESP32-S3 as if it had its own display and buttons.

### Terminal

A serial terminal for sending Bruce shell commands and viewing responses/logs.

Useful for debugging and advanced use without switching to a separate serial-terminal application.

### Device

Shows information available from the USB connection and Bruce, such as connection state, serial settings and device details.

### Settings

Application-level settings. Bruce itself remains controlled by the firmware and its own command/menu system.

## Important: live screen preview limitations

The live preview is **not a raw framebuffer mirror**.

Bruce's `display start` interface sends drawing operations that the Android application replays. Because not every possible TFT operation is represented in the stream, some graphics can occasionally appear incomplete.

You may notice issues such as:

- part of an icon missing
- sprites not appearing perfectly
- fast animations looking incomplete
- a screen becoming correct only after another navigation event causes a redraw

Text, menus, lines and common UI elements generally work well.

This behavior does **not necessarily indicate a USB connection problem**. If Bruce never sends a particular drawing operation, the Android application cannot reconstruct those missing pixels perfectly.

The project intentionally does not invent missing screen data or fake a framebuffer image.

## USB notes

### Device connects but the app cannot control Bruce

Make sure you are using the correct physical USB port.

On some ESP32-S3 boards, one USB-C connector is connected to a USB-to-UART chip while another goes directly to the ESP32-S3 native USB peripheral. For the tested N16R8 board, Android control works through the **native USB** connector.

### Android does not detect the board

Check that:

- USB OTG / Host mode is supported
- the cable supports data
- the correct ESP32 USB port is being used
- no PC or other application currently owns the same USB connection

### USB is unplugged while connected

The application is designed to return to a disconnected state instead of crashing. Reconnect the board and connect again.

## Building from source

The Android project is located in:

```text
android-app/
```

Open that directory in Android Studio, or build with JDK 17 and Gradle.

Windows:

```powershell
cd android-app
gradlew.bat assembleDebug
```

Linux / macOS:

```bash
cd android-app
./gradlew assembleDebug
```

The generated debug APK will be under the app module's Gradle build output directory.

## Project philosophy

The objective is deliberately small:

> Make Bruce comfortable to use from an Android phone without breaking the protocol that already works.

The application is not intended to replace Bruce, duplicate its modules in Android, or turn the phone into an independent RF/security toolkit.

Bruce runs on the ESP32. The phone is the display, controller and serial client.

## Safety and responsible use

Bruce contains hardware and wireless testing features. Use those features only on devices, networks, radio systems and equipment that you own or have explicit permission to test.

This Android application is only a remote interface; the capabilities available depend on the connected hardware and the Bruce firmware installed on it.

## Credits

This project would not exist without:

- [Bruce Devices / Bruce firmware](https://github.com/BruceDevices/firmware)
- [floatme / BruceRemote](https://github.com/floatme/BruceRemote) — the project this work is based on
- [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)
- The Bruce community and contributors

Bruce Remote — Good UI focuses primarily on the Android user experience and on making the existing remote-control workflow practical for headless ESP32-S3 boards.

## Status

The core workflow has been tested on real hardware:

- [x] USB connection
- [x] ESP32-S3 native USB
- [x] Bruce serial communication
- [x] Live display stream
- [x] D-Pad navigation
- [x] OK / Select
- [x] Back / ESC
- [x] Bruce menu navigation
- [x] Terminal
- [x] Portrait mode
- [x] Landscape mode
- [x] USB disconnect / reconnect handling

The screen preview still inherits the limitations of Bruce's drawing-command stream described above.

---

**Bruce Remote — Good UI is an independent community project and is not an official Bruce Devices application.**
