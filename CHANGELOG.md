# Changelog

## 1.0.0-rc.2 — 2026-11-14 — prepared for device testing

The app is now named PPodcast, keeping the same application ID and user data.
Debug builds have a separate PPodcast Debug label and install alongside it.

Fixed saved-queue autoplay, including Download before playing, while preserving
Offline mode and sleep-timer limits. Audio focus now pauses speech for other
media and temporary interruptions, with resumption only when appropriate.

Simplified Add podcast, navigation icons and playback speed/sleep controls.
Separated Episode favorite/queue highlights and highlighted the current queue
item with a theme-appropriate accent. Ordinary Up next hints appear near the end;
action-required warnings remain immediate and empty queues stay quiet.

See [candidate release notes](docs/release-notes/1.0.0-rc.2.md) and the
[versioning scheme](docs/VERSIONING.md). Physical-device acceptance is pending.

## 1.0.0-rc.1 — prepared, not published

The first release candidate includes RSS and Apple-link subscriptions, OPML
migration, streaming and downloads, queue management, chapters, per-show speed,
sleep timer, favorites, new/in-progress views and local listening history.

Download before playing can acquire the whole episode before playback without
blocking feed updates or covers. An opt-in weekday/time schedule refreshes shows
and prepares automatic downloads, with network/charging constraints and a last
attempt summary. Background timing follows Android's scheduling limits.

Downloads accept audio whose served size differs from the RSS enclosure hint,
fixing failures with dynamically served audio, including Acast-hosted episodes.
Transfer completeness and storage limits still apply.

Downloads also accept feeds that publish a zero size hint and audio that reaches
its host through longer redirect chains. This fixes compatibility with several
popular US podcasts without requiring subscriptions to be added again.

Offline mode now stops all app network traffic, including metadata and artwork.
The application is MIT licensed and includes versioned third-party notices.
Settings and About use cleaner spacing and simpler copy. GitHub/Obtainium stable
and preview distribution profiles are prepared for signed release artifacts.

See [candidate release notes](docs/release-notes/1.0.0-rc.1.md).
