# Bruce Remote for Android

`android-app/` is a small Android Views client for Bruce's existing runtime
serial interface. It supports:

- USB attach discovery and Android's per-device permission flow.
- WCH CH9102/CH9102F CDC serial (M5StickC Plus2) and generic CDC ACM
  (including ESP32-S3 native USB on Cardputer ADV).
- 115200 baud, 8 data bits, 1 stop bit, no parity, and no flow control.
- Buffered UTF-8 line parsing and Bruce's non-newline-terminated `# ` prompt.
- `info`, `optionsJSON`, `nav …`, and `options <index>`.
- A native Android rendering of the current Bruce menu, direct option taps,
  navigation controls, raw commands, and a bounded serial log.
- An experimental Bruce 1.12+ screen preview that safely demultiplexes the
  binary `display start` TFT log from normal shell text and replays its drawing
  commands into an Android bitmap.

The app also includes the separate, confirmation-gated firmware installer
described in the workspace-level README.

## USB safety

The app never calls `setDTR()` or `setRTS()`. On the M5StickC Plus2, the
CH9102F's DTR/RTS signals feed the ESP32 auto-program circuit on EN and GPIO0.
Asserting them can reset the Stick or put it into download mode.

Use a USB data cable. The Android device must support USB host/OTG and provide
VBUS. If a direct USB-C-to-C connection does not enumerate, fully turn the
StickC Plus2 off before reconnecting, or try a USB-C OTG adapter plus a known
data-capable USB-A-to-C cable.

## Build

Open `android-app/` in Android Studio, or run Gradle with JDK 17:

```text
gradle :app:assembleDebug
```

The project uses package/application ID `io.bruceremote.app`, `minSdk 26`, and
the maintained `usb-serial-for-android` library.

## Runtime flow

1. The app probes Android's attached USB devices with the library's default
   prober.
2. It adds explicit CH9102F/CH9102X product rules and an interface-based CDC
   ACM fallback for native/composite devices.
3. After permission, it opens port 0 at 115200 8-N-1 and leaves both modem
   control lines untouched.
4. Commands are serialized. A subsequent command is not sent until Bruce emits
   its `# ` prompt.
5. Navigation and option selection automatically queue `optionsJSON` after the
   prompt, so the Android menu reflects the settled device state.

Bruce remains authoritative: physical buttons continue to work, and tapping a
menu row sends `options <n>` rather than reproducing module logic in Android.

## Screen preview

Tap **Screen** while connected. Compatible Bruce builds immediately report the
screen size and then stream future fills, lines, shapes, text, pixels, and image
references. USB reads can split a packet at any byte or combine packets with
shell output, so one incremental parser handles the mixed stream before passing
ordinary bytes to the existing line parser.

This is a vector replay, not LCD framebuffer capture. Bruce does not resend the
screen that existed before logging started, so use a navigation control to
cause a redraw if needed. Images and unlogged sprite/native-driver operations
will be approximate or absent. The app times out cleanly if a pre-1.12 build
does not provide the TFT stream.
