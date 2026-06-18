#!/usr/bin/env python3
"""Probe Sony DSF headers. Cross-check Kotlin DsfHeaderReader output."""

from __future__ import annotations

import struct
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class DsfProbe:
    total_file_size: int
    metadata_pointer: int
    channel_type: int
    channels: int
    sample_rate_hz: int
    bits_per_sample: int
    sample_count: int
    block_size_per_channel: int
    data_chunk_size: int
    data_payload_offset: int = 92

    @property
    def duration_sec(self) -> float:
        if self.sample_rate_hz <= 0:
            return 0.0
        return self.sample_count / self.sample_rate_hz

    @property
    def dsd_label(self) -> str | None:
        multiple = self.sample_rate_hz / 44_100.0
        for target, label in (
            (64.0, "DSD64"),
            (128.0, "DSD128"),
            (256.0, "DSD256"),
            (512.0, "DSD512"),
        ):
            if abs(multiple - target) < 1.0:
                return label
        return None

    def payload_byte_offset(self, sample_index: int) -> int:
        if self.bits_per_sample != 1:
            raise ValueError("only 1-bit DSF supported")
        safe = max(0, min(sample_index, self.sample_count))
        return (safe * self.channels) // 8

    def file_offset_for_ms(self, position_ms: int) -> int:
        sample_index = min(
            self.sample_count,
            (position_ms * self.sample_rate_hz) // 1_000,
        )
        return self.data_payload_offset + self.payload_byte_offset(sample_index)


def parse_dsf(path: Path) -> DsfProbe:
    with path.open("rb") as handle:
        dsd = handle.read(28)
        if dsd[:4] != b"DSD ":
            raise ValueError("missing DSD chunk")
        dsd_chunk_size = struct.unpack("<Q", dsd[4:12])[0]
        if dsd_chunk_size != 28:
            raise ValueError(f"unexpected DSD chunk size: {dsd_chunk_size}")
        total_file_size = struct.unpack("<Q", dsd[12:20])[0]
        metadata_pointer = struct.unpack("<Q", dsd[20:28])[0]

        fmt = handle.read(52)
        if fmt[:4] != b"fmt ":
            raise ValueError("missing fmt chunk")
        fmt_chunk_size = struct.unpack("<Q", fmt[4:12])[0]
        if fmt_chunk_size != 52:
            raise ValueError(f"unexpected fmt chunk size: {fmt_chunk_size}")
        format_version = struct.unpack("<I", fmt[12:16])[0]
        format_id = struct.unpack("<I", fmt[16:20])[0]
        if format_id != 0:
            raise ValueError(f"unsupported format id: {format_id}")
        channel_type = struct.unpack("<I", fmt[20:24])[0]
        channels = struct.unpack("<I", fmt[24:28])[0]
        sample_rate_hz = struct.unpack("<I", fmt[28:32])[0]
        bits_per_sample = struct.unpack("<I", fmt[32:36])[0]
        sample_count = struct.unpack("<Q", fmt[36:44])[0]
        block_size = struct.unpack("<I", fmt[44:48])[0]

        data_hdr = handle.read(12)
        if data_hdr[:4] != b"data":
            raise ValueError("missing data chunk")
        data_chunk_size = struct.unpack("<Q", data_hdr[4:12])[0]

    payload_end = 92 + (data_chunk_size - 12)
    checks = {
        "payload_end_matches_metadata": payload_end == metadata_pointer,
        "sample_bytes_lte_data": (sample_count * channels) // 8 <= data_chunk_size - 12,
        "file_size_matches_path": total_file_size == path.stat().st_size,
    }

    return DsfProbe(
        total_file_size=total_file_size,
        metadata_pointer=metadata_pointer,
        channel_type=channel_type,
        channels=channels,
        sample_rate_hz=sample_rate_hz,
        bits_per_sample=bits_per_sample,
        sample_count=sample_count,
        block_size_per_channel=block_size,
        data_chunk_size=data_chunk_size,
    ), checks


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"usage: {Path(argv[0]).name} <file.dsf>", file=sys.stderr)
        return 2
    path = Path(argv[1])
    probe, checks = parse_dsf(path)
    print(f"path: {path}")
    print(f"dsd: {probe.dsd_label or 'unknown'} @ {probe.sample_rate_hz} Hz")
    print(f"channels: {probe.channels} (type={probe.channel_type})")
    print(f"bits_per_sample: {probe.bits_per_sample}")
    print(f"sample_count: {probe.sample_count}")
    print(f"duration_sec: {probe.duration_sec:.3f}")
    print(f"data_chunk_size: {probe.data_chunk_size}")
    print(f"metadata_pointer: {probe.metadata_pointer}")
    print(f"seek 60s -> file offset {probe.file_offset_for_ms(60_000)}")
    for name, ok in checks.items():
        print(f"check {name}: {'OK' if ok else 'FAIL'}")
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
