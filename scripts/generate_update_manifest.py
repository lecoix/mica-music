#!/usr/bin/env python3
"""Generate the public update manifest from the app's release metadata."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from urllib.parse import urlparse


def read_release_version(gradle_file: Path) -> tuple[str, int]:
    source = gradle_file.read_text(encoding="utf-8")
    version_name_match = re.search(
        r"^\s*versionName\s*=\s*\"([^\"]+)\"",
        source,
        re.MULTILINE,
    )
    version_code_match = re.search(
        r"^\s*versionCode\s*=\s*(\d+)",
        source,
        re.MULTILINE,
    )
    if version_name_match is None or version_code_match is None:
        raise ValueError(f"Could not read versionName/versionCode from {gradle_file}")
    return version_name_match.group(1), int(version_code_match.group(1))


def validate_download_url(value: str, field_name: str, *, required: bool) -> str:
    value = value.strip()
    if not value and not required:
        return ""
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.netloc:
        raise ValueError(f"{field_name} must be an HTTPS URL")
    return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gradle-file", type=Path, required=True)
    parser.add_argument("--changelog-file", type=Path, required=True)
    parser.add_argument("--domestic-download-url", default="")
    parser.add_argument("--github-download-url", required=True)
    parser.add_argument("--tag", default="")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    version_name, version_code = read_release_version(args.gradle_file)
    if args.tag and args.tag != f"v{version_name}":
        raise ValueError(
            f"Release tag {args.tag!r} does not match app version v{version_name}"
        )

    changelog = args.changelog_file.read_text(encoding="utf-8").strip()
    if not changelog:
        changelog = "此版本暂无更新日志。"

    manifest = {
        "versionName": version_name,
        "versionCode": version_code,
        "changelog": changelog,
        "domesticUrl": validate_download_url(
            args.domestic_download_url,
            "domestic download URL",
            required=False,
        ),
        "githubUrl": validate_download_url(
            args.github_download_url,
            "GitHub download URL",
            required=True,
        ),
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
