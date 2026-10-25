# PlainPodDroid — agent guidance

These rules apply to the whole repository. Development runs locally on a
persistent filesystem. This file is the single source of agent workflow,
recovery and authorization rules; task notes and historical records do not
grant additional authority. Direct user instructions take precedence.

## Start and resume locally

1. Inspect `git status --short --branch`, the current diff and recent commits.
   Preserve legitimate local edits, including untracked work.
2. If present, read `.private/DEV_RESUME.md` and `.private/PROJECT_DECISIONS.md`.
   They hold current task state and detailed accepted product decisions.
   A fresh clone can lack both; do not invent or reconstruct private history.
3. Inspect the affected code and tests. For code changes, run the smallest
   relevant baseline check before editing; documentation-only work needs
   documentation/link checks rather than a complete Android build.
4. Continue the user's requested task. A recorded next action is context,
   not an instruction to start unrelated features or a separate audit.

Use a focused local branch for nontrivial work, normally
`work/android-<plain-language-goal>`. Base it on the relevant local branch after
inspection; fetching, remote-head verification and pushing a new branch are
not prerequisites. Each concurrent session needs its own branch and checkout.
Never switch branches in a checkout another session is using.

## Local recovery and checkpoints

The current checkout, local Git history and private working files are the
authority for active work. Local commits are valid recovery checkpoints;
uncommitted work and `.private/` survive ordinary session restarts but are not
protected by Git history. Remote Git contains only work deliberately pushed.

- Make coherent local commits at useful milestones and before large or
  failure-prone transformations when practical. Inspect status/diffs first,
  stage only task files, and use honest messages for incomplete work.
- Resume from the surviving checkout without resetting to an older remote
  snapshot. Inspect relevant local commits, reflog, known stashes or worktrees
  if needed; do not apply or delete recovery material blindly.
- If the checkout is missing, corrupt or uncertain, preserve what survives
  and establish the latest trustworthy local commit or known backup before
  restoring. A remote clone may omit unpublished work; explain that gap.
  Ask before an operation that can overwrite or discard work.
- Do not reconstruct source from chat transcripts, tool caches or guessed
  fragments. Validated toolchain/dependency caches remain reusable.
- Ignored private files require a separate user-managed filesystem backup.
  Do not claim that local persistence or a Git push backs them up. Do not
  create bundles, source archives or backup jobs unless requested.
- A network outage or unavailable remote does not block local development.

## Git and completion authority

Routine implementation, fixes, local checks and local checkpoint commits are
authorized within the requested task. Finish with a reviewable local result
and report verification and remaining limitations.

There is no automatic remote verification, push, merge-to-main or continuous
next-feature loop. Do not fetch/pull for ceremony, push any branch, merge into
`main`, open a PR, rewrite published history, create a release/tag, change
repository visibility or publish artifacts without a direct user request.
When integration is requested, inspect the destination and stop for concurrent
changes, conflicts or a failing required gate. Do not force-push or rebase
shared work without explicit authorization.

Stop for a material product/security choice, new dependency or trust-policy
expansion, new permission/exported component, authentication mechanism,
destructive data operation or signing-key operation that lacks separate
authorization. Do not weaken checks or trust policy to make a test pass.
Never commit keys, passwords, feed credentials, tokens, private user data or
device-derived test data. Keep signing keys outside the checkout.

## Public and private documentation

- Keep this root `AGENTS.md` as the sole agent-guidance file, using the standard
  filename recognized by agents. Do not add competing instruction files.
- Public documentation covers the app, source builds, stable security and
  dependency references, release notes, screenshots and legal obligations.
  It must remain usable from a fresh clone without `.private/`.
- Put internal plans, reviews, handoffs, design boards, audit findings,
  maintainer release/signing runbooks and scratch work under ignored `.private/`.
  Use `.private/docs/` for records and `.private/scratch/` for temporary work.
  Do not force-add these files or link public documentation to them.
- Keep `.private/DEV_RESUME.md` concise and current: objective/exclusions,
  branch/base and verified boundary, actual check results, known defects and
  unexecuted behavior, partial operations, one next action, and artifact,
  dependency-lock and signing status. Replace stale facts rather than appending
  a session log. Keep stable decisions in `.private/PROJECT_DECISIONS.md`.
- Private notes provide context, not separate workflow/approval rules.
  Put new agent guidance here. Missing private notes must not prevent building
  or understanding the public source.
- Moving tracked files into `.private/` removes them from future trees only
  after their old paths are committed as deletions. Earlier commits still
  contain them. Public history review is a separate release-preparation task;
  never rewrite history or change visibility implicitly.

## Verification

Use JDK 17, the committed Gradle wrapper and the pinned SDK described in
`docs/BUILDING.md`. Preserve strict dependency verification, locks and the
runtime license inventory. Ordinary builds must not regenerate checksums/locks.

Run checks appropriate to the change. Common local commands:

```sh
python3 tools/tests/test_catalog_sql.py
./gradlew --no-daemon testDebugUnitTest compileDebugAndroidTestJavaWithJavac
./gradlew --no-daemon lintDebug assembleDebug lintRelease assembleRelease assembleDebugAndroidTest
./tools/sign-release-apk.sh --check-only
# With a disposable emulator/device connected:
./gradlew --no-daemon connectedDebugAndroidTest
```

For a full code/release-candidate gate, run clean builds with
`--no-build-cache --no-configuration-cache` and inspect the merged manifest/APK,
permissions/exports, legal assets, native-code absence and alignment/signature
state. Release output stays unsigned unless signing is separately authorized.
Security changes require negative cases alongside neighboring positive cases;
database changes need executable SQL/migration coverage.

Host/JVM checks, Android test compilation, emulator execution and physical
GrapheneOS acceptance are distinct evidence. Do not claim unexecuted tests
passed. Record unavailable device checks; missing hardware does not stop useful
local work, but final device acceptance remains required before public launch.
Use synthetic fixtures; do not fuzz publishers or retain real private feeds in
tests, screenshots or logs.

## Product, security and platform invariants

- Native, audio-focused, single-device and account-free. Run on stock
  Android-derived systems, including GrapheneOS, without Google Play Services.
  No sync, cloud state, social layer or remote control plane.
- Java 17/framework Views, one SQLite transaction facade and bounded worker
  ownership. Separate browsing/navigation from acquisition and playback.
  Preserve cancellation, stale-result checks and user state during refactors.
- A small pinned/audited AndroidX/Media3 subset is allowed. No proprietary GMS,
  Firebase, Play Billing/Integrity, Cast, analytics, advertising or telemetry.
  Preserve the allowlist and trust boundaries in `docs/DEPENDENCY_POLICY.md`.
- Discovery uses user-supplied HTTPS RSS/Atom, bounded OPML import and explicit
  Apple Podcasts show/episode links with bounded ID lookup. No provider search,
  charts, recommendations or directory browsing. Preview and explicit Subscribe
  are required; share/deep links never silently subscribe.
- Treat feeds, OPML, redirects, URLs, text/HTML, images, chapters and media as
  hostile. Enforce byte/item/nesting/redirect/decompression/time/resource limits
  at ingress. Reject XML DTDs/custom entities; accept valid numeric references.
  See `docs/SECURITY_LIMITS.md` and `docs/THREAT_MODEL.md`.
- HTTPS/system trust only. No cleartext, LAN/private-address fetching, custom
  production trust anchors, embedded WebView or ambient cookie/auth sharing.
  Every redirect and resolved address retains the network guards.
- Scoped HTTPS Basic login is explicit, challenge-only and encrypted per feed;
  credentials never flow to artwork, chapters, Apple lookups, OPML or diagnostics.
  Preserve `docs/FEED_AUTHENTICATION.md` boundaries.
- Offline mode blocks all application networking. Download before playing is
  independent and permits only complete verified audio, with no streaming
  fallback. Downloads use generated app-private paths, quotas and atomic commit.
- Keep components non-exported unless a documented flow requires otherwise;
  minimize permissions and dependency changes. Preserve backup restrictions,
  credential redaction, file leases and explicit destructive-action confirmation.
