# PPodcast versioning

`gradle.properties` is the source of truth:

- `plainPodDroidVersionName` is the user-visible release version.
- `plainPodDroidVersionCode` is Android's monotonically increasing update code.

Choose the version once for the complete release cycle. Feature and bugfix
branches inherit that cycle's version; do not bump again when it is already set.
Normal builds and release preparation do not bump versions automatically.

## Release versions

Stable releases use `MAJOR.MINOR.PATCH`.

| Bump | Use |
| --- | --- |
| Patch | Compatible fixes, cleanup, build tooling or documentation |
| Minor | Compatible features or substantial functionality improvements |
| Major | Deliberate breaking changes, such as dropping Android support or requiring data resets |

A data-preserving schema migration alone is not a major change. Choose the
largest applicable bump for the complete set of changes. Increment that component
from the higher local/distributed stable version and reset lower components to
zero. For example, a feature after `1.2.3` targets `1.3.0`; a fix targets `1.2.4`.

Release candidates use the intended stable version followed by `-rc.N`, starting
at `-rc.1`. Further candidates for that target increase N. Moving from
`1.0.0-rc.1` to `1.0.0-rc.2` continues acceptance testing of the first stable
release; it does not declare it stable. Promotion removes the suffix and still
requires a higher Android code. If the intended stable target changes, start
that target's candidate sequence at `-rc.1`.

## Android update codes

Every newly distributed APK with changed contents needs a higher `versionCode`
for its application ID. Use one above the highest local or distributed code,
including previews. Never derive the code from the visible version, reuse a
distributed code for changed contents, or decrease it. Keep it below
2,100,000,000, as required by the project's release validator.

Debug builds inherit the code and add `-debug` to the version name. Their
`.debug` application ID is separate from the release app, so they install
alongside it. Increment the shared code before distributing changed device-test
APKs. Routine rebuilds during the same local development cycle need no bump.

## Notes and metadata

When opening a release cycle, update the properties together and add:

- A dated `CHANGELOG.md` section summarizing the complete release changes.
- `docs/release-notes/<versionName>.md`, headed `# PPodcast <versionName>`.
- `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, containing
  1–500 characters of user-facing release notes.
- Matching `CurrentVersion` and `CurrentVersionCode` in the F-Droid metadata
  template at `distribution/fdroid/io.github.jnesew.plainpoddroid.yml`.

Retain previous versions' notes. Describe changes and limitations honestly;
preparation, device acceptance and publication are separate states. Review
the date before distributing. Distribution uses `v<versionName>` tags and the
existing `PlainPodDroid-<versionName>.apk` filename convention. The visible app
name does not change the application ID, source repository or update identity.

Version edits do not authorize signing, tags, pushes or publication. Build and
verification instructions are in [BUILDING.md](BUILDING.md).
