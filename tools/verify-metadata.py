#!/usr/bin/env python3
"""Check the shared public Fastlane/F-Droid listing using Python's standard library."""
import json
from pathlib import Path
import re
import struct
import sys
import zlib

ROOT = Path(__file__).resolve().parents[1]


def require(ok, message):
    if not ok:
        raise ValueError(message)


def png_size(path):
    data = path.read_bytes()
    require(data.startswith(b"\x89PNG\r\n\x1a\n"), f"Not PNG: {path}")
    offset, size, ended = 8, None, False
    while offset + 12 <= len(data):
        count = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        end = offset + count + 12
        require(end <= len(data), f"Truncated PNG: {path}")
        payload = data[offset + 8:offset + 8 + count]
        crc = struct.unpack_from(">I", data, offset + 8 + count)[0]
        require(zlib.crc32(kind + payload) == crc, f"PNG CRC mismatch: {path}")
        if kind == b"IHDR":
            require(size is None and count == 13, f"Invalid PNG header: {path}")
            size = struct.unpack_from(">II", payload)
        if kind == b"IEND":
            require(count == 0 and end == len(data), f"Invalid PNG trailer: {path}")
            ended = True
            break
        offset = end
    require(size and ended, f"Incomplete PNG: {path}")
    return size


def check(root=ROOT):
    listing = root / "fastlane/metadata/android/en-US"
    for file, limit in (("title.txt", 50), ("short_description.txt", 80), ("full_description.txt", 4000)):
        text = (listing / file).read_text().strip()
        require(0 < len(text) <= limit, f"Listing {file} must have 1..{limit} characters.")
        require("placeholder" not in text.lower(), f"Unfinished listing: {file}")
    changes = list((listing / "changelogs").glob("*.txt"))
    require(changes, "Missing version-code changelog.")
    for path in changes:
        require(path.stem.isdecimal() and len(path.read_text().strip()) <= 500,
                f"Invalid version-code changelog: {path.name}")
    code = re.search(r"(?m)^plainPodDroidVersionCode=(\d+)$", (root / "gradle.properties").read_text())[1]
    require((listing / "changelogs" / f"{code}.txt").is_file(), "Missing current version-code changelog.")
    images = listing / "images"
    require(png_size(images / "icon.png") == (512, 512), "Listing icon must be 512x512 PNG.")
    if (images / "featureGraphic.png").exists():
        require(png_size(images / "featureGraphic.png") == (1024, 500), "Feature graphic must be 1024x500.")
    screens = sorted((images / "phoneScreenshots").glob("*.png"))
    require(3 <= len(screens) <= 8, "Provide 3..8 original phone screenshots.")
    for path in screens:
        width, height = png_size(path)
        require(min(width, height) >= 320 and max(width, height) <= 3840 and
                max(width, height) <= 2 * min(width, height), f"Unsupported screenshot dimensions: {path.name}")
    stable = json.loads((root / "distribution/obtainium.json").read_text())[0]
    preview = json.loads((root / "distribution/obtainium-preview.json").read_text())[0]
    for profile, allowed, denied in (
            (stable, ["PlainPodDroid-1.0.0.apk"], ["PlainPodDroid-1.0.0-rc.1.apk", "app-debug.apk", "PlainPodDroid-1.0.0-unsigned.apk"]),
            (preview, ["PlainPodDroid-1.0.0.apk", "PlainPodDroid-1.0.0-rc.1.apk"], ["app-debug.apk", "app-debug-androidTest.apk"])):
        require(profile["id"] == "io.github.jnesew.plainpoddroid" and
                profile["url"] == "https://github.com/jnesew/PlainPodDroid", "Wrong Obtainium package/source.")
        settings = json.loads(profile["additionalSettings"])
        regex = settings["apkFilterRegEx"]
        require(all(re.fullmatch(regex, name) for name in allowed) and
                not any(re.fullmatch(regex, name) for name in denied), "Obtainium APK filter is incorrect.")
    require(not json.loads(stable["additionalSettings"])["includePrereleases"] and
            json.loads(preview["additionalSettings"])["includePrereleases"], "Stable/preview release policy changed.")
    recipe = (root / "distribution/fdroid/io.github.jnesew.plainpoddroid.yml").read_text()
    require("License: MIT\n" in recipe and "Builds: []\n" in recipe and
            "Disabled: Awaiting the first public release" in recipe,
            "Keep the unsubmitted F-Droid recipe explicitly pending release review.")
    return len(screens)


if __name__ == "__main__":
    try:
        print(f"Shared listing metadata passed ({check()} phone screenshots).")
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Metadata: {error}", file=sys.stderr)
        sys.exit(1)
