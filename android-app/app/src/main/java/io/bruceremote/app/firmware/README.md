# Signed firmware catalog and BRP1 patches

This package is the trust boundary between an imported/downloaded firmware
package and the ESP flashing layer. It has no network, UI, USB, ZIP, or JSON
library dependency. Callers provide three already-isolated files:

1. the exact upstream Bruce merged binary;
2. `manifest.json` plus its detached `manifest.sig`;
3. the BRP1 payload named by the manifest.

The engine never patches a device or a firmware file in place. It creates a
new output file, verifies it, and returns flash segments to the USB flasher.

## Trust and verification order

`TrustedKeyRing` contains a small app-pinned allowlist of P-256 public keys.
`FirmwareManifestVerifier` performs these operations in order:

1. enforce the raw manifest/signature size limits;
2. validate canonical DER ECDSA encoding;
3. verify `SHA256withECDSA` over the **exact manifest bytes** against every
   pinned key;
4. strictly decode UTF-8 and JSON;
5. require `key_id` to identify the key that actually verified;
6. validate every field, board, range, timestamp, and catalog rollback floor.

Do not normalize, pretty-print, or reserialize `manifest.json` before
verification. A newline change changes the signature. Key selection does not
trust an unverified `key_id`; the bounded keyring is tried first.

The detached signature is ASN.1 DER `(r, s)`, not a raw 64-byte concatenation.
Public keys are X.509 SubjectPublicKeyInfo values. Keys on any curve other than
NIST P-256/secp256r1 are rejected.

`FirmwarePackageEngine.apply` then:

1. requires the exact M5 device profile;
2. rejects aliased input, payload, and output paths;
3. verifies input and payload length plus SHA-256;
4. atomically creates a previously nonexistent output;
5. applies the bounded patch;
6. flushes and syncs the output;
7. verifies output length/SHA-256 and each flash segment SHA-256.

Any failed application removes the newly created partial output. Failure to
remove it is itself a typed error and must be surfaced prominently.

## Manifest schema 1

All shown fields are required and unknown fields are rejected. JSON numbers
must be integers. Duplicate keys, trailing commas, trailing data, malformed
UTF-8, and unsafe filenames are rejected.

```json
{
  "schema": 1,
  "package_id": "bruce-cardputer-adv-1.14-agent1",
  "catalog_version": 42,
  "key_id": "release-2026",
  "issued_at": 1784851200,
  "expires_at": 1816387200,
  "firmware": {
    "project": "bruce",
    "version": "1.14",
    "commit": "0123456789abcdef0123456789abcdef01234567",
    "source_url": "https://github.com/example/bruce-agent-source",
    "license": "AGPL-3.0"
  },
  "target": {
    "board_id": "cardputer-adv",
    "chip": "esp32s3",
    "flash_size": 8388608
  },
  "input": {
    "size": 7340032,
    "sha256": "64-lowercase-hex-characters..."
  },
  "patch": {
    "algorithm": "brp1-chunk-v1",
    "file": "payload.brp",
    "size": 123456,
    "sha256": "64-lowercase-hex-characters..."
  },
  "output": {
    "size": 7340032,
    "sha256": "64-lowercase-hex-characters..."
  },
  "flash_segments": [
    {
      "flash_offset": 0,
      "source_offset": 0,
      "size": 7340032,
      "sha256": "64-lowercase-hex-characters..."
    }
  ],
  "agent": {
    "build_id": "bruce-agent-1",
    "protocol_min": 1,
    "protocol_max": 1
  },
  "app": {
    "min_version_code": 1
  }
}
```

Supported targets are deliberately closed:

| `board_id` | chip | flash | boot transport |
|---|---|---:|---|
| `m5stack-cplus2` | `esp32` | 8 MiB | CH9102 UART |
| `cardputer-adv` | `esp32s3` | 8 MiB | native USB Serial/JTAG |

Flash segments must be ordered, non-overlapping in target flash, four-byte
aligned, and cover the output artifact contiguously by `source_offset`. The
flasher must still ROM-detect the chip and flash size before writing; the
manifest is not a substitute for device interrogation.

## BRP1 deterministic chunk format

All multibyte integers are signed, big-endian two's-complement values. Negative
values are invalid. There is no implicit padding and no trailing data.

| Bytes | Meaning |
|---:|---|
| 4 | ASCII `BRP1` |
| 1 | version, exactly `1` |
| 1 | flags, exactly `0` |
| 2 | reserved, exactly `0` |
| 8 | exact base/input size |
| 8 | exact output size |
| 4 | instruction count |

Instructions immediately follow:

- `COPY`: opcode `0x00`, 8-byte input offset, 4-byte length.
- `DATA`: opcode `0x01`, 4-byte length, then exactly that many literal bytes.

Every instruction must emit at least one byte. Default bounds are 65,536
instructions, 1 MiB per instruction, 16 MiB per artifact, and 16 MiB per patch.
The sum of emitted bytes must equal the declared output size. COPY ranges must
be entirely inside the already SHA-256-verified base image.

The format is intentionally simple. CI can generate a full image as one or
more DATA records, or a delta as COPY/DATA records. Patch generation must be
deterministic for the same base/output pair, but only the Android applier lives
in this package.

## Catalog production

Catalog CI, not the phone, should:

1. pin an exact official Bruce source revision and official merged-binary hash;
2. apply the published remote-agent source patch;
3. reproducibly build one output for each board;
4. create BRP1 from the exact official input to that exact output;
5. compute all sizes and SHA-256 values;
6. emit the manifest once, without later reformatting;
7. sign its exact UTF-8 bytes with an offline P-256 release key;
8. publish corresponding AGPL source and build scripts.

There is intentionally no “best effort” patch for an unknown Bruce build.
Input hash mismatch means the recipe is inapplicable. Obtain the exact official
base or a separately signed recipe.

The caller should persist the highest accepted `catalog_version` and pass it as
`ManifestVerificationPolicy.minimumCatalogVersion`. Key rotation requires an
app update or a next-key statement signed by an already trusted key; never
trust a key downloaded alongside the package that it authenticates.

Package containers should be imported with an exact filename allowlist and
size limits before invoking this code. Do not extract arbitrary ZIP paths,
directories, duplicate entries, or symbolic links.
