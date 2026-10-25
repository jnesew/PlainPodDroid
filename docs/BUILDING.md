# Building PlainPodDroid

## Required tools

- JDK 17.
- Android SDK Platform 37.0.
- Android SDK Build Tools 36.0.0.
- A POSIX shell for `gradlew` (or `gradlew.bat` on Windows).
- Bash and `curl` only when using the optional toolchain bootstrap helper.

Android Studio and Google Play Services are not required. Point `ANDROID_HOME` or `ANDROID_SDK_ROOT` at the SDK; do not commit `local.properties`.

## Local SDK setup

Install the required JDK and SDK once and reuse them between local sessions.
Use the committed Gradle wrapper with a complete JDK 17. The optional Linux
bootstrap helper can provision the pinned Android tools when needed:

```sh
./tools/bootstrap-android-toolchain.sh
```

The script is idempotent and keeps downloads outside the repository in the
sibling `plainpoddroid-toolchains` directory. It downloads only the pinned
Gradle 9.6.0 distribution and Android Platform 37.0, Build Tools 36.0.0, and
Platform Tools 37.0.1 archives. Android archives are checked against both the
officially published SHA-1 during the initial trust bootstrap and their
recorded SHA-256 identity on every subsequent run; Gradle is checked against
its published SHA-256. An unexpected cached file or installation is moved into
a task-specific quarantine instead of being overwritten.

The helper creates a compiler runtime under `/tmp` from the installed Java 17
runtime, adding any missing compiler/linker launchers. With a complete local
JDK, use the Gradle wrapper directly; no compiler overlay is needed.

Set `PLAINPOD_TOOLCHAIN_ROOT` to choose another cache location. Downloaded
binaries are reusable caches; the script records the versions, URLs and
checksums needed to recreate them.

## Wrapper integrity

The committed Gradle 9.6.0 wrapper is pinned to the official distribution checksum:

- `gradle-9.6.0-bin.zip`: `bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01`
- `gradle-wrapper.jar`: `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`

The hashes are published at `https://services.gradle.org/distributions/`.

## Dependency verification

Ordinary builds use the committed checksums and locks, including the first
build on a new machine. Gradle downloads missing dependencies and verifies them
against that metadata; no metadata-writing flags are needed.

Only an intentionally reviewed dependency update needs the metadata-generation
command below. Start from a clean local branch with a recoverable local commit:

```sh
./gradlew --no-daemon \
  --write-verification-metadata sha256 \
  --write-locks \
  testDebugUnitTest lintDebug assembleDebug lintRelease assembleRelease
```

Review every new component, repository origin, checksum, and lock entry. Commit the reviewed `gradle/verification-metadata.xml` and lock files. Then repeat without metadata-writing flags so strict verification is exercised:

```sh
./gradlew --no-daemon \
  testDebugUnitTest lintDebug assembleDebug lintRelease assembleRelease
```

## Normal checks

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
./gradlew --no-daemon lintRelease assembleRelease
```

After dependencies have been downloaded and verified, add `--offline` to these
commands to build without accessing the dependency repositories.

The release build remains unsigned at
`app/build/outputs/apk/release/app-release-unsigned.apk`. The debug APK is
automatically debug-signed and uses a separate application ID; it cannot update
an installed release build.

The optional local helper `tools/sign-release-apk.sh --help` describes APK
preflight and signing with an existing external keystore. Its `--check-only`
mode checks the unsigned input without opening a key. No Gradle signing
configuration or CI key is required. Keep keys/passwords outside the checkout,
verify the expected certificate SHA-256 and retain the same signer for updates.
Signing an APK does not publish it.

## Executable catalog and UI binding tests

The host SQLite gate compiles current production `CatalogSql`, then executes
its statements using Python's standard-library SQLite. It requires Java 17 and
Python 3, with no extra packages:

```sh
python3 tools/tests/test_catalog_sql.py
./gradlew --no-daemon compileDebugAndroidTestJavaWithJavac
```

The frozen v9 schema is shared by host and Android migration tests. Android
instrumentation exercises the actual database adapter in isolated test databases
and inflates real drawer/row layouts with isolated callbacks. It covers route
restoration, drawer closure and background focus/accessibility as well as
distinct navigation/play controls. With a device/emulator attached, run:

```sh
./gradlew --no-daemon connectedDebugAndroidTest
```

Android tests use the already configured platform InstrumentationTestRunner and
the pinned SDK's `android.test.base` compile stubs. Platform test libraries are
declared only in the test APK; they add no Maven artifact or production runtime
dependency. Compilation and host SQLite execution do not count as Android test
execution. Keep device results separate.

## Source gate helper

`bash tools/verify-source-gates.sh` runs JVM tests and Android-test compilation
through the committed Gradle wrapper, using the same locks and verification as
ordinary builds. It accepts extra Gradle flags, including `--offline` once the
verified dependencies are cached. It requires a working Gradle/JDK/SDK setup.
This check does not execute Android tests or replace lint, APK inspection or
physical-device acceptance.

## Dependency inspection

```sh
./gradlew --no-daemon :app:dependencies
./gradlew --no-daemon :app:dependencyInsight \
  --dependency okhttp \
  --configuration debugRuntimeClasspath
```

Dependency graph changes require review of the policy in
[DEPENDENCY_POLICY.md](DEPENDENCY_POLICY.md), committed locks/checksums and the
runtime inventory/notices. Build provenance belongs in these public inputs.

## Local release tooling

tools/prepare-release.sh prepares an unsigned candidate from a clean committed
checkout. Its version comes from gradle.properties; add matching release notes
and a Fastlane changelog named by versionCode before committing a new version.
Use JDK 17 and the pinned SDK; no dependency metadata-writing flags are used.

    ./tools/prepare-release.sh --sdk /path/to/android-sdk --offline \
      --output /path/to/new-private-candidate

The output directory must not exist. Failed gates retain logs and an INCOMPLETE
marker; only a completed candidate has READY. Preparation runs host SQL/release
tool tests, listing validation, a clean JVM/Android-test/lint/debug/release build,
real SDK signing-helper preflight tests and actual APK inspection. It snapshots
the unsigned APK, R8 removal report, legal files, release notes and provenance.
The candidate directory includes local build logs and is private working data.

APK inspection covers package/version, SDKs, permissions/exports, backup,
cleartext/system trust/CT, native-code absence, exact legal bytes, ZIP alignment,
unsigned/signed state and the pinned Guava deserialization shrinkage boundary.
The standalone gate is:

    python3 tools/release_tools.py verify-apk \
      --apk app/build/outputs/apk/release/app-release-unsigned.apk \
      --usage app/build/outputs/mapping/release/usage.txt \
      --sdk /path/to/android-sdk

Use the existing signing helper's interactive mode to sign the candidate's
app-unsigned.apk with the approved external key. Once signed, the packet command
checks the trusted certificate, v2/v3 signatures and exact unsigned/signed payload
equality before creating a new output directory:

    ./tools/package-release.sh --candidate /path/to/private-candidate \
      --apk /path/to/signed.apk --cert-sha256 TRUSTED_CERTIFICATE_SHA256 \
      --sdk /path/to/android-sdk --output /path/to/new-release-packet

The packet contains PlainPodDroid-<versionName>.apk, SHA256SUMS, source/toolchain/
certificate provenance, notes, licenses and an exact-commit F-Droid build recipe.
Use the candidate's same clean source checkout. Preparation, interactive signing
and publication are separate operations.

## Fastlane and F-Droid metadata

The optional Fastlane command interface requires Ruby 3.4 and Bundler 4.0.3.
Fastlane 2.241.1 and its tooling dependencies are checksum-locked in Gemfile.lock;
they are not Android application dependencies. Install the locked graph locally:

    bundle config set --local path vendor/bundle
    BUNDLE_FROZEN=true bundle install
    bundle exec fastlane android metadata
    bundle exec fastlane android prepare output:/path/to/new-private-candidate \
      sdk:/path/to/android-sdk offline:true

The sign lane delegates to the interactive helper with explicit input, external
keystore, alias, trusted certificate and new output options. package delegates
to the packet command. screenshots prepares a synthetic debug emulator using
an explicit serial; see [screenshot instructions](screenshots/README.md).
The lanes perform local work and do not include a store-upload action. Usage
telemetry and the lane update check are disabled.

Fastlane/F-Droid share fastlane/metadata/android/en-US/ for descriptions, icon,
screenshots and version-code changelogs. The F-Droid metadata draft is
distribution/fdroid/io.github.jnesew.plainpoddroid.yml; preparation of a signed
packet generates a recipe with the exact source commit and real signing-key
fingerprint. A draft is not an F-Droid listing or approval. Developer-signature
distribution requires F-Droid's independent reproducibility verification; the
local pipeline does not establish that result.

References: [F-Droid listing assets](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/),
[F-Droid build metadata](https://f-droid.org/en/docs/Build_Metadata_Reference/),
[F-Droid reproducible builds](https://f-droid.org/docs/Reproducible_Builds/),
[Fastlane](https://docs.fastlane.tools/).
