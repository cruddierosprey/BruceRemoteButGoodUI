#!/usr/bin/env python3
"""Create an exact-input, signed BRP1 Bruce firmware package.

The output is compatible with the Android FirmwarePackageEngine.  It never
modifies either input image.  The patch uses same-offset COPY runs where useful
and literal DATA records elsewhere; every record is capped at 1 MiB.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import struct
import subprocess
from pathlib import Path


MAGIC = b"BRP1"
VERSION = 1
MAX_CHUNK = 1024 * 1024
MIN_COPY = 64
FLASH_SIZE = 8 * 1024 * 1024


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def split_chunks(data: bytes, size: int = MAX_CHUNK):
    for start in range(0, len(data), size):
        yield start, data[start : start + size]


def make_instructions(base: bytes, target: bytes):
    """Yield (opcode, input_offset, payload/length) instructions."""
    instructions = []
    data_start = 0
    cursor = 0
    comparable = min(len(base), len(target))

    def emit_data(start: int, end: int) -> None:
        for relative, chunk in split_chunks(target[start:end]):
            instructions.append(("data", 0, chunk))

    while cursor < comparable:
        if base[cursor] != target[cursor]:
            cursor += 1
            continue

        run_end = cursor + 1
        while run_end < comparable and base[run_end] == target[run_end]:
            run_end += 1
        if run_end - cursor < MIN_COPY:
            cursor = run_end
            continue

        emit_data(data_start, cursor)
        run_length = run_end - cursor
        for relative in range(0, run_length, MAX_CHUNK):
            length = min(MAX_CHUNK, run_length - relative)
            instructions.append(("copy", cursor + relative, length))
        cursor = run_end
        data_start = cursor

    emit_data(data_start, len(target))
    return instructions


def write_patch(path: Path, base: bytes, target: bytes) -> None:
    instructions = make_instructions(base, target)
    if not instructions:
        raise ValueError("Patch would contain no instructions")
    if len(instructions) > 65_536:
        raise ValueError(f"Patch has too many instructions: {len(instructions)}")

    with path.open("xb") as output:
        output.write(
            struct.pack(
                ">4sBBHqqi",
                MAGIC,
                VERSION,
                0,
                0,
                len(base),
                len(target),
                len(instructions),
            )
        )
        for opcode, offset, value in instructions:
            if opcode == "copy":
                output.write(struct.pack(">Bqi", 0, offset, value))
            else:
                payload = value
                output.write(struct.pack(">Bi", 1, len(payload)))
                output.write(payload)


def verify_patch(path: Path, base: bytes, expected: bytes) -> None:
    """Independently replay a generated patch before it is signed."""
    stream = io.BytesIO(path.read_bytes())
    header = stream.read(struct.calcsize(">4sBBHqqi"))
    magic, version, flags, reserved, input_size, output_size, count = struct.unpack(
        ">4sBBHqqi", header
    )
    if (magic, version, flags, reserved) != (MAGIC, VERSION, 0, 0):
        raise ValueError("Generated BRP1 header failed self-verification")
    if input_size != len(base) or output_size != len(expected):
        raise ValueError("Generated BRP1 sizes failed self-verification")

    rebuilt = bytearray()
    for _ in range(count):
        opcode_raw = stream.read(1)
        if len(opcode_raw) != 1:
            raise ValueError("Generated BRP1 instruction stream is truncated")
        opcode = opcode_raw[0]
        if opcode == 0:
            offset, length = struct.unpack(">qi", stream.read(12))
            rebuilt.extend(base[offset : offset + length])
        elif opcode == 1:
            (length,) = struct.unpack(">i", stream.read(4))
            rebuilt.extend(stream.read(length))
        else:
            raise ValueError(f"Generated BRP1 contains unknown opcode {opcode}")
    if stream.read(1):
        raise ValueError("Generated BRP1 contains trailing bytes")
    if bytes(rebuilt) != expected:
        raise ValueError("Generated BRP1 does not reconstruct the target exactly")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--board-id", choices=("m5stack-cplus2", "cardputer-adv"), required=True)
    parser.add_argument("--chip", choices=("esp32", "esp32s3"), required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--version", default="dev")
    parser.add_argument("--build-id", default="bruce-remote-1")
    parser.add_argument("--catalog-version", type=int, required=True)
    parser.add_argument("--issued-at", type=int, required=True)
    parser.add_argument("--expires-at", type=int, required=True)
    parser.add_argument("--key-id", default="dev-local-2026")
    parser.add_argument("--private-key", type=Path, required=True)
    parser.add_argument("--openssl", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.expires_at <= args.issued_at:
        raise ValueError("--expires-at must be later than --issued-at")
    if args.board_id == "m5stack-cplus2" and args.chip != "esp32":
        raise ValueError("m5stack-cplus2 requires --chip esp32")
    if args.board_id == "cardputer-adv" and args.chip != "esp32s3":
        raise ValueError("cardputer-adv requires --chip esp32s3")

    base = args.base.read_bytes()
    target = args.target.read_bytes()
    if not (0 < len(base) <= FLASH_SIZE and 0 < len(target) <= FLASH_SIZE):
        raise ValueError("Both merged images must fit the 8 MiB target flash")

    args.output_dir.mkdir(parents=True, exist_ok=False)
    patch_path = args.output_dir / "payload.brp"
    manifest_path = args.output_dir / "manifest.json"
    signature_path = args.output_dir / "manifest.sig"

    write_patch(patch_path, base, target)
    verify_patch(patch_path, base, target)
    patch = patch_path.read_bytes()
    target_hash = sha256(target)

    manifest = {
        "schema": 1,
        "package_id": f"bruce-remote-{args.board_id}-{args.commit[:7]}",
        "catalog_version": args.catalog_version,
        "key_id": args.key_id,
        "issued_at": args.issued_at,
        "expires_at": args.expires_at,
        "firmware": {
            "project": "bruce",
            "version": args.version,
            "commit": args.commit,
            "source_url": f"https://github.com/BruceDevices/firmware/tree/{args.commit}",
            "license": "AGPL-3.0-or-later",
        },
        "target": {
            "board_id": args.board_id,
            "chip": args.chip,
            "flash_size": FLASH_SIZE,
        },
        "input": {"size": len(base), "sha256": sha256(base)},
        "patch": {
            "algorithm": "brp1-chunk-v1",
            "file": patch_path.name,
            "size": len(patch),
            "sha256": sha256(patch),
        },
        "output": {"size": len(target), "sha256": target_hash},
        "flash_segments": [
            {
                "flash_offset": 0,
                "source_offset": 0,
                "size": len(target),
                "sha256": target_hash,
            }
        ],
        "agent": {
            "build_id": args.build_id,
            "protocol_min": 1,
            "protocol_max": 1,
        },
        "app": {"min_version_code": 1},
    }
    raw_manifest = (
        json.dumps(manifest, ensure_ascii=True, separators=(",", ":"), sort_keys=True) + "\n"
    ).encode("utf-8")
    manifest_path.write_bytes(raw_manifest)

    subprocess.run(
        [
            str(args.openssl),
            "dgst",
            "-sha256",
            "-sign",
            str(args.private_key),
            "-out",
            str(signature_path),
            str(manifest_path),
        ],
        check=True,
    )

    print(f"base_sha256={sha256(base)}")
    print(f"target_sha256={target_hash}")
    print(f"patch_sha256={sha256(patch)}")
    print(f"patch_bytes={len(patch)}")
    print(f"manifest={manifest_path}")


if __name__ == "__main__":
    main()
