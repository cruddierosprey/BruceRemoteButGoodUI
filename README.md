# Bruce Remote for Android

This workspace contains a working Android prototype for controlling Bruce over
USB, an optional Bruce remote-discovery patch, a signed exact-binary patch
pipeline, and native ESP32/ESP32-S3 flashing support.

Supported hardware:

- M5Stack Cardputer ADV (ESP32-S3 native USB)
- M5Stack Cardputer (the same Bruce image auto-detects the original keyboard)
- M5StickC Plus2 (ESP32 through its CH9102 USB-to-UART bridge)

## What works without patched firmware

Current Bruce already provides a 115200 baud serial shell. The app uses its
`info`, `optionsJSON`, `nav`, `options`, and direct feature commands. This
provides menu-level access to features that do not have their own dedicated
command. Bruce 1.12 and newer also provide a compact TFT drawing stream through
`display start`; the app's **Screen** button decodes that stream into an
experimental live preview without requiring the remote patch.

The optional source patch adds:

- `remote hello`: one-line structured firmware, board, chip, and capability
  discovery;
- `remote state`: one-line structured menu state with a revision;
- preservation of the active menu after those remote queries.

The app tries the enhanced commands first and automatically falls back to the
stock Bruce shell.

No Android app can universally control an arbitrary unknown ESP32 firmware.
The phone can only use protocols implemented by that firmware. A firmware that
does not already expose controls must be rebuilt with an agent or matched by an
exact, audited patch recipe. This project refuses unknown input hashes instead
of attempting unsafe blind binary injection.

## Connect and control

1. The Android phone must support USB host/OTG.
2. Use a USB data cable. A charge-only cable cannot work.
3. Install `BruceRemote-debug.apk` from `dist/android`.
4. Start Bruce on the device, connect it to the phone, and grant the Android
   USB permission.
5. Tap **Connect**. The app opens 115200 8-N-1 and intentionally leaves DTR
   and RTS untouched during normal control.
6. Tap a rendered menu item, use the navigation pad, or send a Bruce shell
   command directly.
7. Tap **Screen** to start the display preview. The stream begins with display
   geometry but not a snapshot of pixels that were drawn before logging began,
   so use a navigation control once if the preview is initially blank.

For a StickC Plus2 that is not detected over USB-C-to-C, turn it completely
off, disconnect it, and reconnect. A USB-C OTG-to-USB-A adapter plus a known
data A-to-C cable is a useful fallback. Use a powered OTG hub if the phone
cannot supply stable power.

## Screen preview and true mirroring

The stock-compatible preview replays Bruce's logged drawing primitives:
fills, lines, shapes, text, pixels, and image references. It is especially
useful for menus and text-heavy tools. It is not pixel-perfect because current
Bruce does not log every possible TFT operation, sends an image path instead
of image pixels, can de-duplicate or drop log entries, and has no frame
sequence, checksum, or resynchronization marker. Sprite pushes, fast animations,
and some module-specific graphics can therefore be incomplete.

The M5StickC Plus2 link is also limited to 115200 baud. A 240×135 RGB565 frame
is 64,800 bytes, which takes roughly 5.6 seconds at the theoretical 8-N-1
payload rate. A reliable pixel-exact mode needs a firmware extension that sends
checksummed, sequenced dirty rectangles (for example 16×16 RGB565 tiles with
RAW/RLE encoding), keyframes, and receiver backpressure. That protocol can
coexist with this vector preview in a later patched Bruce build; full raw-frame
streaming should not be the default on the StickC Plus2.

## Flash a merged image

Open **Firmware installer** from the main screen. Close the normal controller
connection first; the installer takes exclusive ownership of the USB port.

### M5StickC Plus2

Select **M5StickC Plus2**, select the CH9102 device and a merged image, then
Identify and Flash. The installer uses the classic DTR/RTS ROM-entry sequence
only in flashing mode.

### Cardputer / Cardputer ADV

Select **Cardputer ADV**. The safe default is manual ROM entry:

1. switch the Cardputer off;
2. hold G0;
3. connect USB/apply power;
4. release G0;
5. grant USB permission again if Android asks;
6. Identify, confirm the detected ESP32-S3, then Flash.

The Cardputer image also supports the regular Cardputer because Bruce probes
the ADV TCA8418 keyboard controller and falls back automatically.

The installer checks the detected chip, 8 MiB flash bounds, security state,
image ranges, cancellation, and post-write verification. It refuses Secure
Boot, flash encryption, and secure-download mode by default.

## Apply a signed patch on the phone

The development packages are under `dist/packages`. In the installer select:

1. the matching `Bruce-Base-*.bin`;
2. that target package's `manifest.json`;
3. `manifest.sig`;
4. `payload.brp`.

Tap **Verify and apply patch**. The app verifies the pinned P-256 signature
before JSON parsing, checks all hashes and bounds, writes a new cached image,
and verifies the result. It never patches the selected base in place. The
result can then be flashed normally.

The included key is a local development key. Do not treat it as a production
release key or redistribute these artifacts unchanged.

## Planned GitHub firmware channel

The intended production flow is a dedicated GitHub repository with:

- a GitHub Actions job that notices a new upstream Bruce release, checks out
  that exact tag, applies the reviewed source patch, and builds both targets;
- a small signed catalog describing the supported device, Bruce version,
  release notes, download URL, image SHA-256, size, and minimum app version;
- GitHub Releases containing merged images, signatures, build provenance, and
  the complete corresponding source required by Bruce's license;
- an Android update screen that checks the catalog, notifies the user,
  downloads and verifies the selected image, identifies the connected ESP32,
  and then opens the existing confirmation-and-flash wizard.

The updater should never silently flash a device. Downloading can be automatic,
but ROM entry, device identification, and final flashing remain explicit user
actions. A production release key should be kept outside the repository (for
example in protected CI signing infrastructure), while only its public key is
pinned in the app.

No external repository is created by this prototype; its GitHub owner and
repository name must be chosen first.

## Project layout

- `android-app/`: Android controller, installer, tests, and JNI flasher module
- `bruce/`: exact Bruce checkout plus the remote patch and build-script fix
- `docs/SCREEN_MIRRORING.md`: stock preview analysis and reliable pixel-mode design
- `dist/firmware/`: merged base and remote-enabled images
- `dist/packages/`: exact-input BRP1 patch recipes and signatures
- `tools/make_firmware_package.py`: deterministic package generator
- `local-keys/`: local development signing private key; never publish it

## Build and verification

Firmware environments:

```text
m5stack-cardputer   # regular Cardputer + Cardputer ADV auto-detection
m5stack-cplus2      # M5StickC Plus2
```

Bruce merged images are generated at offset `0x000000`. The successful builds
use Arduino-ESP32 3.3.9 and the dependencies pinned by Bruce commit
`59e83bfbd8a63a6b67ea23498e15c710a1ed9657`.

Android requires JDK 17, Android SDK 35, NDK 27.1, and CMake 3.22.1. From
`android-app`, run:

```text
gradlew.bat testDebugUnitTest assembleDebug
```

The native flashing layer vendors Espressif `esp-serial-flasher` v2.0.0. The
serial layer uses `usb-serial-for-android`. See the module READMEs and
`dist/SOURCE_AND_SAFETY.md` for licensing and production-signing requirements.

## Primary references

- Bruce serial interface: https://github.com/BruceDevices/firmware/wiki/Serial
- Bruce firmware source: https://github.com/BruceDevices/firmware
- Cardputer ADV documentation: https://docs.m5stack.com/en/core/Cardputer-Adv
- M5StickC Plus2 documentation: https://docs.m5stack.com/en/core/M5StickC%20PLUS2
- Android USB host API: https://developer.android.com/develop/connectivity/usb/host
- usb-serial-for-android: https://github.com/mik3y/usb-serial-for-android
- Espressif serial flasher: https://github.com/espressif/esp-serial-flasher
