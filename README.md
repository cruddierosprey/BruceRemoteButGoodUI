# Bruce Remote

A mobile-first Android remote controller and virtual display for **Bruce firmware** running on ESP32 / ESP32-S3 devices.

This repository is a community fork of [floatme/BruceRemote](https://github.com/floatme/BruceRemote). The original project proved that Bruce can be controlled over USB serial from Android. This fork keeps that core idea and protocol compatibility, but rebuilds the Android experience around a cleaner, touch-friendly **Kotlin + Jetpack Compose + Material 3** interface.

The main reason for this fork was simple: use Bruce comfortably on an ESP32-S3 board that has **no physical display and no navigation buttons**, with the Android phone acting as the screen and controller.

> **Real-hardware tested:** ESP32-S3 N16R8 (16 MB Flash / 8 MB PSRAM), stock/original Bruce firmware, Android phone, direct USB-OTG connection.

---

## What this fork changes

The upstream BruceRemote project already had the important low-level pieces: Android USB serial access, Bruce CLI commands, menu discovery, navigation and experimental display streaming.

This fork focuses on turning that working prototype into a more practical daily-use Android controller.

### UI / UX

- Rebuilt Android interface with **Jetpack Compose + Material 3**
- Mobile-first dark interface
- Large live Bruce display area
- Compact touch-friendly D-Pad
- Dedicated **OK / Select** and **Back / ESC** controls
- Haptic feedback option
- Portrait and landscape layouts
- Android safe-area / system-bar aware layout
- Removed unnecessary fullscreen behavior that could overlap Android system UI
- Simplified controller by removing non-essential permanent buttons

### Bruce control

- Current Bruce menu is requested with `optionsJSON`
- Menu entries are shown as tappable Android shortcuts
- D-Pad navigation uses Bruce `nav ...` commands
- Direct selection uses `options <index>`
- Device discovery tries `remote hello` / `remote state` when available and falls back to stock Bruce commands
- `info`, `display start`, `display stop` and arbitrary shell commands can be sent from the app

Bruce remains the source of truth. The Android app does **not** reimplement Wi-Fi, BLE, RF, IR or other Bruce modules — it controls the Bruce firmware already running on the ESP32.

### USB / serial stability

- Android USB Host / OTG support
- USB attach detection and permission handling
- ESP32-S3 native USB / CDC support
- WCH CH9102 family support
- Generic CDC-ACM fallback
- 115200 baud, 8-N-1
- DTR / RTS intentionally left untouched during normal remote use
- Prompt-synchronized command queue using Bruce's `# ` prompt
- Bounded command queue to reduce input flooding
- Graceful USB disconnect handling
- Connection/session generation checks to avoid stale USB callbacks
- Serial telemetry for received/sent bytes and packet counts

### Built-in terminal

The **Terminal** tab provides a Bruce serial console without needing a second Android serial-terminal app.

It includes:

- RX / TX / system / error log separation
- timestamps
- command history
- quick commands
- copy log
- clear log
- bounded in-memory log history

### Device page

The **Device** tab exposes information gathered from USB and Bruce, including:

- USB device name
- VID:PID
- serial driver
- connection uptime
- bytes received / sent
- received packet count
- Bruce firmware/device information when available

---

## Why it was developed

Generic ESP32-S3 development boards are inexpensive and powerful, but many of them do not include the display and keyboard/button hardware that Bruce-oriented devices such as Cardputer provide.

For the tested setup, the goal was:

```text
ESP32-S3 N16R8 + Bruce
          │
          │ USB
          ▼
      Android phone
          │
          ├── Bruce screen
          ├── D-Pad
          ├── OK / ESC
          ├── Bruce menu shortcuts
          ├── Serial terminal
          └── Device information
```

This means the ESP32-S3 can be used without adding an OLED, joystick, buttons or jumper wiring just to access Bruce's interface.

---

## Development with Google AI Studio

This redesigned Android client was developed **with Google AI Studio as an AI-assisted development environment**.

The process was iterative rather than a one-shot generated application:

1. The upstream BruceRemote repository was used as the protocol/reference implementation.
2. The working USB and Bruce communication behavior was studied first.
3. The Android UI was redesigned around Kotlin, Jetpack Compose and Material 3.
4. Changes were repeatedly installed on a real Android phone.
5. USB connection, menu navigation, display streaming, D-Pad controls, portrait/landscape behavior, disconnect/reconnect behavior and terminal operation were tested against a real ESP32-S3 N16R8 board.
6. The UI was simplified after hardware testing instead of continuously adding new features.

AI Studio was used to assist implementation and refactoring; **the actual requirements, hardware setup, testing and acceptance decisions were human-directed**.

The remote-control path itself is local USB serial communication. The current Android manifest does not request Internet permission for normal operation.

---

## Tested setup

### ESP32-S3 board

Tested with a dual USB-C **ESP32-S3 N16R8** development board:

- ESP32-S3
- 16 MB Flash
- 8 MB PSRAM
- dual USB-C

On the tested board the two USB-C connectors have different roles:

- **COM** port: USB-to-UART / convenient firmware flashing
- **USB** port: ESP32-S3 native USB used by Bruce Remote on Android

If your board has two USB connectors and the app does not detect/control Bruce through one of them, try the connector wired to the ESP32-S3's native USB peripheral.

### Android

The current project configuration uses:

- `minSdk 24`
- `targetSdk 36`
- Android USB Host feature

Your Android device must support **USB Host / OTG** and must be able to power/enumerate the connected ESP32 device.

Use a **data-capable USB cable**. Charge-only cables will not work.

---

## Installing Bruce on the tested N16R8 board

The working setup uses the **official/original Bruce firmware**.

Official Bruce links:

- [Bruce firmware](https://github.com/BruceDevices/firmware)
- [Bruce website / flasher](https://bruce.computer)

For the tested ESP32-S3 N16R8 board, the working Bruce Web Flasher target was:

```text
ESP32 S3 PSRAM
└── esp32-s3-devkitc-1-psram
```

A useful detail for dual-USB boards: firmware flashing may work through the USB-to-UART/COM connector while Bruce's application serial output and Android remote control are available through the **native USB** connector.

---

## Quick start

1. Install stock Bruce on the ESP32 / ESP32-S3.
2. Install the Bruce Remote APK from this repository's **Releases** page.
3. Connect the ESP32's working USB serial/native USB port to the Android device.
4. Grant the Android USB permission when prompted.
5. Open **Bruce Remote**.
6. Select/connect the detected USB device if it is not already connected.
7. The app initializes the Bruce session and can automatically start the display stream.
8. Use the on-screen D-Pad, **OK** and **Back / ESC**, or tap a discovered Bruce menu option directly.

> APK releases will be published under GitHub **Releases**. Source builds are documented below.

---

## App layout

### Remote

The main screen contains:

- USB connection status
- current Bruce menu / option shortcuts
- live Bruce display preview
- D-Pad
- OK / Select
- Back / ESC

### Terminal

Interactive Bruce serial terminal with quick commands, history and bounded logs.

### Device

USB/session telemetry and Bruce/device information.

### Settings

Current settings include:

- haptic feedback
- automatic `display start`
- manual menu/display resync
- protocol information

---

## Live display preview: important limitation

The screen shown on Android is **not a raw framebuffer capture**.

Bruce's `display start` protocol sends TFT drawing operations over the same serial connection. The app separates those binary packets from normal CLI text, then replays the supported drawing operations into an Android bitmap.

The parser recognizes the Bruce TFT packet stream (`0xAA` header) and the renderer handles common primitives such as:

- screen fill
- rectangles / rounded rectangles
- circles
- triangles
- ellipses
- lines / wide lines
- arcs
- text / print operations
- pixels
- fast horizontal / vertical lines
- screen geometry information

### Why can some icons look incomplete?

Bruce does not send a complete screenshot or framebuffer. Some UI graphics can be produced by image/sprite/native-driver operations that are not fully represented as pixel data in the stream.

In the current renderer, an image packet can provide a path/reference rather than the real image pixels. For that reason you may occasionally see:

- incomplete icons
- missing parts of a logo
- imperfect sprites
- fast animations that do not reconstruct perfectly
- a screen that becomes more complete after the next menu redraw

This is a limitation of the available Bruce drawing stream, not necessarily a USB fault.

The app intentionally does not fabricate missing framebuffer data.

---

## How the Android side is structured

The redesigned client separates USB transport, Bruce protocol handling, display parsing and UI state.

Important components in the current source include:

```text
usb/
  UsbSerialController
  UsbModels

protocol/
  BruceProtocol
  BruceCommandQueue
  BruceLineParser
  BruceTftProtocol
  BruceTftStreamParser

display/
  BruceDisplayEngine

viewmodel/
  BruceViewModel

ui/
  Remote
  Terminal
  Device
  Settings
```

### Communication flow

```text
Android USB Host
      │
      ▼
UsbSerialController
      │
      ▼
BruceTftStreamParser ───► BruceDisplayEngine ───► Compose / Android View
      │
      └────────► BruceLineParser ───► Bruce protocol / menu state
                                      │
                                      ▼
                               BruceCommandQueue
                                      │
                                      ▼
                                   ESP32
```

The command queue waits for Bruce's shell prompt before advancing to the next queued request, which helps avoid merging commands during rapid touchscreen input.

---

## Building the redesigned Android app

The AI Studio export is a standard Android Gradle project using Kotlin and Jetpack Compose.

Current important build values:

```text
Application ID : com.aistudio.bruceremote.s3ctrl
minSdk         : 24
targetSdk      : 36
versionName    : 1.0
```

Open the project in a recent Android Studio installation with a compatible JDK/Android SDK.

The current exported source does not include a Gradle wrapper, so either build from Android Studio or use an installed compatible Gradle environment.

Example:

```bash
gradle :app:assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For a public release, build and sign a release APK with your own signing key.

---

## Tested status

The following workflow was tested on real hardware:

- [x] ESP32-S3 N16R8
- [x] stock/original Bruce firmware
- [x] Android USB Host / OTG
- [x] ESP32-S3 native USB serial
- [x] USB permission flow
- [x] connect / disconnect
- [x] hot unplug without intentional app crash
- [x] Bruce CLI communication
- [x] menu discovery
- [x] direct menu selection
- [x] D-Pad navigation
- [x] OK / Select
- [x] Back / ESC
- [x] live display stream
- [x] portrait layout
- [x] landscape layout
- [x] terminal
- [x] device/session telemetry

Other boards may work through CDC-ACM, CH9102 or another driver supported by `usb-serial-for-android`, but **only the hardware explicitly listed above should be considered verified by this fork unless additional test reports are added**.

---

## Upstream and credits

This work builds on existing projects rather than replacing them.

Special thanks to:

- [floatme/BruceRemote](https://github.com/floatme/BruceRemote) — upstream Android remote/reference implementation
- [BruceDevices/firmware](https://github.com/BruceDevices/firmware) — Bruce firmware and serial/display interfaces
- [mik3y/usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) — Android USB serial driver library
- Bruce contributors and community members

The application name remains **Bruce Remote**. This repository is an unofficial community fork and is not an official Bruce Devices Android application.

---

## Responsible use

Bruce includes networking, radio, USB and hardware-testing functionality. Use these capabilities only on devices, networks and systems that you own or are explicitly authorized to test.

Bruce Remote is only a controller/interface. The actual capabilities come from the connected hardware and the Bruce firmware running on it.

---

## Türkçe kısa açıklama

Bu fork'un amacı, **ekranı ve fiziksel kontrol tuşları olmayan ESP32-S3 üzerinde çalışan Bruce firmware'ini Android telefondan rahat şekilde kullanabilmek**.

Test edilen yapı:

```text
ESP32-S3 N16R8
16 MB Flash + 8 MB PSRAM
        │
        │ Native USB / OTG
        ▼
Android + Bruce Remote
```

Uygulama telefonda Bruce ekranını mümkün olduğunca yansıtır; D-Pad, OK ve ESC ile menüler kontrol edilir. Ayrıca Bruce menü seçenekleri Android arayüzünde listelenir, seri terminal ve cihaz bilgileri sunulur.

Android arayüzünün bu sürümü **Google AI Studio kullanılarak, AI destekli ve gerçek donanım üzerinde tekrar tekrar test edilen bir geliştirme süreciyle** hazırlanmıştır. Çalışan Bruce protokolü korunmuş, asıl odak mobil arayüz, kullanım kolaylığı ve bağlantı kararlılığı olmuştur.

Ekran yansıtma gerçek framebuffer aktarımı değildir; Bruce'un gönderdiği TFT çizim komutları Android'de tekrar çizilir. Bu nedenle bazı ikon/sprite parçalarının eksik görünmesi bilinen bir sınırlamadır.

---

If you test another board successfully, consider opening an issue with the board model, USB chipset/VID:PID, Bruce version and Android version so the compatibility list can grow from real hardware reports.
