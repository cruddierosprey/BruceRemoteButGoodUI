# Bruce screen mirroring research and design

## Result

There are two distinct levels of mirroring:

| Mode | Firmware requirement | Result |
| --- | --- | --- |
| Bruce vector preview | Stock Bruce 1.12+ | Works now; reconstructs future TFT drawing commands |
| Pixel-exact, recoverable mirror | Patched Bruce | Requires a new sequenced dirty-pixel protocol |
| Arbitrary non-Bruce firmware | Firmware-specific support | Cannot be implemented universally from Android alone |

Bruce added its serial display stream in commit
[`f6ee35f`](https://github.com/BruceDevices/firmware/commit/f6ee35f9544e96434eccb1a06b36db744ceac955).
The official
[`esp32_serial_navigator.html`](https://github.com/BruceDevices/firmware/blob/main/sd_files/esp32_serial_navigator.html)
uses `display start`, splits `0xAA` binary display packets out of normal serial
text, and replays them onto a browser canvas. The Android app now implements
the same mechanism with stricter packet-shape validation and incremental USB
chunk handling.

## Why raw full-frame streaming is not the common solution

Both target displays are 240×135 at runtime and use RGB565:

```text
240 × 135 × 2 bytes = 64,800 bytes per frame
```

Bruce documents the runtime serial link as 115200, 8-N-1. UART framing consumes
10 wire bits for each eight-bit payload byte, giving an ideal payload rate of:

```text
115,200 / 10 = 11,520 bytes/s
64,800 / 11,520 = 5.625 seconds per uncompressed frame
```

That is the hard limit on M5StickC Plus2 through its USB-to-UART bridge before
protocol overhead and contention with the CLI. Cardputer's ESP32-S3 native USB
can move substantially more data, but a single implementation that works on
both devices still needs deltas rather than continuous raw frames.

Reading the LCD controller back is not a dependable alternative. The Bruce
Cardputer and M5StickC Plus2 board configurations select an ST7789 write path
without a TFT MISO/read definition. TFT_eSPI only supports ST7789 reads when
the required MISO or bidirectional SDA read wiring and configuration exist.

## Existing Bruce vector protocol

The serial command handler starts and stops logging with:

```text
display start
display stop
```

Packets share the same byte stream as ordinary Bruce shell output:

```text
0xAA | total_size:u8 | function:u8 | function_payload
```

Multi-byte values are big-endian 16-bit values. Current function IDs include
screen fill, rectangles, round rectangles, circles, triangles, ellipses,
lines, arcs, wide lines, strings, print text, image references, pixels,
horizontal/vertical lines, and screen geometry.

The Android parser:

- retains a partial header or packet across USB reads;
- accepts adjacent packets in one USB read;
- validates the exact length of fixed-size functions and minimum length of
  text/image functions;
- allows `0xAA` inside a packet payload;
- forwards every byte outside a validated packet to the existing Bruce line
  and prompt parser;
- times out cleanly if the firmware does not emit screen geometry.

The Android renderer keeps a 240×135 (or firmware-reported) ARGB bitmap and
replays the vector operations before aspect-fitting it with nearest-neighbor
scaling.

## Limits of the stock stream

This mode is useful, but it is not a framebuffer mirror:

- Starting logging emits screen geometry, not pixels already visible on the
  LCD. A navigation event is needed to trigger the first redraw.
- Image packets contain a filesystem path rather than the decoded image pixels.
- Some direct driver calls, sprite pushes, `pushImage` variants, and other
  module-specific rendering paths are not represented.
- The firmware de-duplicates identical log records. That can suppress a
  legitimate repeated animation operation.
- Its FreeRTOS queue uses a non-blocking send and does not report a full queue
  to the receiver.
- Packets contain no checksum, sequence number, frame number, keyframe marker,
  or receiver flow control. A dropped packet can leave the reconstructed
  bitmap wrong until a later full redraw.
- Text rendered with Android fonts can only approximate the exact TFT_eSPI
  glyph raster.

For these reasons the app labels the feature as an experimental screen preview.

## Recommended patched protocol for pixel-exact mirroring

Keep the existing mode for compatibility and add a separate negotiated
`bruce-mirror/1` channel.

### Framing

Use COBS records terminated by `0x00`. The decoded record should contain:

```text
magic:u16
version:u8
type:u8
flags:u16
session_id:u32
sequence:u32
frame_id:u32
payload_length:u16
payload
crc32c:u32
```

COBS provides an unambiguous record boundary even when payload bytes are
arbitrary. CRC32C detects corruption, while session and sequence values expose
drops and stale packets.

Suggested messages:

- `HELLO` / `CAPABILITIES`
- `START` / `STOP`
- `CREDIT`
- `FRAME_BEGIN`
- `TILE`
- `FRAME_END`
- `KEYFRAME_REQUEST`
- `STATS`

### Pixel representation

Maintain a 64,800-byte RGB565 shadow framebuffer when memory permits. Divide it
into 16×16 tiles and mark tiles dirty in every central TFT wrapper. At a capped
rate:

1. snapshot the dirty map;
2. send only changed tiles;
3. select RAW RGB565 or a simple run-length encoding per tile;
4. close the logical frame with its frame ID and checksum;
5. retain or re-mark tiles if delivery was not accepted.

Optional LZ4 can be negotiated later, but RAW plus RLE keeps the first decoder
small and deterministic. A keyframe sends all tiles and repairs any earlier
loss.

### Backpressure

The phone should grant one or two frame credits. Bruce must not begin another
frame when it has no credit; it should merge new dirty regions into the next
frame instead. This protects normal controls and prevents an unbounded queue on
the StickC Plus2.

### Firmware integration

Extend Bruce's existing `tft_logger` because it is already the central wrapper
used by the serial and WebUI navigators. Cover its current primitives, then add
the missing pixel-copy paths (`pushImage`, sprite pushes, and board-driver
shortcuts). Updating the shadow framebuffer at draw time avoids relying on
unsupported LCD RAM readback.

## Verification checklist

Stock preview:

1. Install app version 0.2.0 or newer.
2. Flash/start Bruce 1.12 or newer.
3. Connect the M5 device to the Android phone in USB host/OTG mode.
4. Tap **Connect**, then **Screen**.
5. Use one navigation control if the initial static display has not redrawn.
6. Exercise fills, menus, centered/right text, full-circle arcs, and rapid
   navigation.
7. Stop the preview and reconnect to confirm `display stop` was drained before
   USB close.

Patched pixel mode:

- random USB read chunk boundaries;
- injected bit errors, packet deletion, duplication, and reordering;
- disconnect/reconnect with a new session ID;
- keyframe recovery after loss;
- queue saturation and credit starvation;
- long runs on StickC Plus2 while issuing interactive CLI commands;
- exact bitmap comparison against a firmware-side shadow-buffer fixture.
