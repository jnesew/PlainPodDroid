# PlainPodDroid dependency policy

Status: mandatory  
Date: 2026-10-24

## Principle

Every runtime dependency is executable attack surface and a supply-chain relationship. A dependency is accepted only when it materially reduces a larger security or lifecycle risk than implementing the capability locally.

## Direct dependency allowlist

| Component | Pinned version | Purpose | Why retained |
|---|---:|---|---|
| Android Gradle Plugin | 9.4.0 | Android build tooling | Stable API-37-capable toolchain; includes current R8 fixes |
| Gradle wrapper | 9.6.0 | Reproducible build entry point | Required by AGP 9.4 and available for local/CI builds |
| AndroidX Annotation | 1.10.0, compile only | API-contract annotations checked by lint | Selected by the approved OkHttp graph; the direct compile-only declaration adds no packaged code |
| AndroidX Media3 ExoPlayer | 1.11.0 | Progressive audio playback | Avoids custom decoder/buffering/audio-focus lifecycle code |
| AndroidX Media3 Session | 1.11.0 | Background/system media integration | Correct media service, notification, and controller lifecycle |
| AndroidX Media3 OkHttp datasource | 1.11.0 | Route streaming through the same network policy | Prevents Media3 from bypassing URL/DNS restrictions |
| OkHttp | 5.5.1 | TLS HTTP client and policy DNS hook | Prevents a fragile custom HTTP/TLS stack and closes DNS-validation TOCTOU |
| Okio (existing transitive module, constrained) | 3.18.2 | Bounded I/O used by OkHttp | Pins the Base64 compatibility repair over the client’s default 3.18.1 |
| JUnit | 4.13.2, test only | Deterministic JVM tests | No production attack surface |

All Media3 modules use the same version. No dynamic versions, version ranges, snapshots, alphas, betas, release candidates, local Maven repositories, JitPack, or arbitrary repository URLs are allowed.

## Explicit non-dependencies

The initial implementation does not use Compose, Material Components, AppCompat, Fragment, Navigation, Room, WorkManager, Retrofit, Moshi/Gson/Jackson, Glide/Coil/Picasso, HTML parsers, feed-parser SDKs, crash reporters, logging interceptors, DI frameworks, reflection serializers, native codecs, or Google Play/Firebase libraries.

`androidx.profileinstaller:profileinstaller` is explicitly excluded. It is an optional startup-performance optimization pulled through Lifecycle; retaining its exported, `DUMP`-permission-gated receiver is not justified for this app.

Framework APIs cover UI, SQLite, scheduling, XML streaming, bitmap sampling, preferences, and SAF.

Catalog/row instrumentation uses the platform test runner already configured in
Gradle and compile-only `android.test.base` stubs from the pinned SDK Platform 37.0.
Only the test APK declares platform test libraries. Host SQLite tests use Python
3's standard library and compiled production SQL. These add no Maven artifact
or production dependency; strict locks/checksums remain authoritative.

## Packaged manifest surfaces

The packaged application components are:

- `MainActivity`, exported for the launcher and bounded `text/plain` shares.
- `PlaybackService`, exported for system media controls, with controller trust
  checks and media-item injection restricted to this application's UID.
- `AboutActivity`, the scheduled refresh, automatic/manual download and preparation
  job services, and the opt-in preparation boot/time receiver, all non-exported.
  Job services require the platform `BIND_JOB_SERVICE` permission; the receiver
  is disabled until the user enables its schedule.
- Media3's `BluetoothValidationActivity`, exported behind the platform-only
  `android.permission.BLUETOOTH_PRIVILEGED` permission and a narrow
  `content://media/internal/audio/media/` audio intent. Media3 documents it as
  an API 36–37 Bluetooth compatibility path; reassess it when the minimum SDK
  reaches 38 or the Media3 implementation changes.
- AndroidX Startup's `InitializationProvider`, non-exported and used only for
  OkHttp platform initialization.

`androidx.profileinstaller` and its receiver are absent. Android's manifest
merger adds an app-signature permission for dynamically registered,
non-exported receivers; it does not add an exported receiver.

## Build repositories

Only `google()`, `mavenCentral()`, and `gradlePluginPortal()` in plugin management are allowed. Project-level repositories are rejected with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`.

## Verification requirements

- Commit the Gradle wrapper JAR/scripts, distribution URL, and official distribution SHA-256.
- Generate and review `gradle/verification-metadata.xml` with SHA-256 checksums for plugins and dependencies.
- Enable strict dependency verification for ordinary builds.
- Activate dependency locking for resolvable configurations and commit lock state.
- Run dependency reports after every allowlist change and compare the transitive graph.
- CI/build documentation must support an offline build after one verified dependency population step.
- Do not suppress dependency verification for plugins or production artifacts.

## Update procedure

1. Read the upstream security and release notes from the official project.
2. Update one dependency family at a time on a dedicated branch.
3. Regenerate checksums/locks in the explicit metadata-writing mode; inspect every new artifact and origin.
4. Run unit, lint, debug, and release builds. Record device scenarios for the
   final integrated device pass during private development.
5. Include the reviewed graph, origins, checksum/lock changes, runtime inventory
   and verification results with the change. An unreviewed update or failing
   automated gate is incomplete; a deferred device pass does not waive the
   public-release acceptance gate.

Agent authorization and integration rules are consolidated in the root
`AGENTS.md`.

## References

- AGP 9.4 release/compatibility notes: https://developer.android.com/build/releases/agp-9-4-0-release-notes
- Media3 1.11.0 release notes: https://developer.android.com/jetpack/androidx/releases/media3
- Gradle dependency verification: https://docs.gradle.org/current/userguide/dependency_verification.html
- Gradle dependency locking: https://docs.gradle.org/current/userguide/dependency_locking.html
