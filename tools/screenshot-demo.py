#!/usr/bin/env python3
"""Prepare a disposable debug emulator; capture its current real app view on request."""
import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io.github.jnesew.plainpoddroid.debug"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--theme", choices=("light", "dark"), default="light")
    parser.add_argument("--capture", help="Capture the current screen to a NEW PNG path; no UI navigation.")
    parser.add_argument("--offline", action="store_true", help="Use only verified cached Gradle dependencies.")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Use a disposable local emulator, never a physical device.")
    def adb(*arguments, binary=False):
        result = subprocess.run(["adb", "-s", args.serial, *arguments], check=True,
                                capture_output=True, text=not binary)
        return result.stdout
    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        parser.error("Selected device is not an emulator.")
    if args.capture:
        output = Path(args.capture)
        if output.suffix.lower() != ".png":
            parser.error("Capture output must be PNG.")
        # Exclusive output creation: never replace reviewed screenshots silently.
        with output.open("xb") as stream:
            stream.write(adb("exec-out", "screencap", "-p", binary=True))
        print(f"Captured actual emulator view: {output}. Visually review before use.")
        return
    command = ["./gradlew", "--no-daemon", "--no-configuration-cache",
               "-PplainPodScreenshotFixture=true", "assembleDebug", "assembleDebugAndroidTest"]
    if args.offline:
        command.append("--offline")
    subprocess.run(command, cwd=ROOT, check=True)
    for path in ("app/build/outputs/apk/debug/app-debug.apk",
                 "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"):
        adb("install", "-r", str(ROOT / path))
    adb("shell", "am", "force-stop", PACKAGE)
    result = adb("shell", "am", "instrument", "-w", "-e", "seedDemo", "true",
                 "-e", "theme", args.theme, PACKAGE + ".test/" +
                 "io.github.jnesew.plainpoddroid.ScreenshotFixtureRunner")
    if "Original synthetic library ready" not in result:
        raise RuntimeError(result)
    images = ROOT / "fastlane/metadata/android/en-US/images"
    icon = images / "icon.png"
    data = adb("exec-out", "run-as", PACKAGE, "cat", "files/screenshot-icon.png", binary=True)
    if icon.exists() and icon.read_bytes() != data:
        raise RuntimeError("Existing listing icon differs; review it before replacing.")
    if not icon.exists():
        icon.write_bytes(data)
    adb("shell", "wm", "size", "1080x1920")
    adb("shell", "wm", "density", "420")
    adb("shell", "am", "start", "-n", PACKAGE + "/io.github.jnesew.plainpoddroid.MainActivity")
    print(result.strip())
    print("Navigate the real UI, then use --capture NEW.png and visually review each image.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Screenshots: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError):
            print((error.stderr or error.stdout or "")[-4096:], file=sys.stderr)
        sys.exit(1)
