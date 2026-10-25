# PlainPodDroid screenshots

These are unretouched emulator captures of the app. The library uses fictional
podcasts, original generated cover artwork and reserved example domains. No
publisher artwork, private feeds, credentials or personal library data are used.
The screenshot fixture is included only in the Android test APK.

| View | Screenshot |
|---|---|
| Podcasts | [Light library](../../fastlane/metadata/android/en-US/images/phoneScreenshots/01-podcasts.png) |
| Episodes | [Filters and episode actions](../../fastlane/metadata/android/en-US/images/phoneScreenshots/02-episodes.png) |
| Episode | [Playback controls and chapters](../../fastlane/metadata/android/en-US/images/phoneScreenshots/03-episode.png) |
| Queue | [Queue order and actions](../../fastlane/metadata/android/en-US/images/phoneScreenshots/04-queue.png) |
| Dark appearance | [Dark library](../../fastlane/metadata/android/en-US/images/phoneScreenshots/05-dark-theme.png) |
| Settings | [Offline controls](../../fastlane/metadata/android/en-US/images/phoneScreenshots/06-offline-settings.png) |

The shared listing assets live under
fastlane/metadata/android/en-US/images/. Fastlane and F-Droid both use this
directory. The 512-pixel listing icon is rendered from the app's existing launcher
drawable; it is not a new branding identity.

To reproduce, use JDK 17 and a disposable AOSP emulator, with the SDK/ADB available.
The helper checks the emulator/debug target, installs the debug/test APKs, and
refuses to seed a nonempty library unless it already owns the demo fixture:

    python3 tools/screenshot-demo.py --serial emulator-5580 --offline

It explicitly selects the test-only runner with
-PplainPodScreenshotFixture=true, seeds the demo without networking, selects
the light theme, and uses a 1080×1920 display at 420 dpi. --theme dark selects
the real dark appearance. Normal builds retain the ordinary platform test runner.

Navigate the actual app, wait for loading/scroll indicators to settle, then
capture each view to a new filename and visually inspect it:

    python3 tools/screenshot-demo.py --serial emulator-5580 --capture /path/to/new-shot.png

The helper leaves the emulator running for review. Use a dedicated emulator
rather than a personal development library. tools/verify-metadata.py checks
listing lengths, PNG integrity/dimensions, changelog naming and Obtainium filters.
