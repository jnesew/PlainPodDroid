# PlainPodDroid quantitative security limits

Status: mandatory implementation defaults  
Version: `limits-0005`
Date: 2026-10-24

These are fail-closed ceilings, not UI recommendations. A limit change is security-sensitive and requires tests and a decision-record update.

## URL and origin policy

| Property | Limit/policy |
|---|---|
| URL length | 4,096 UTF-16 code units before parsing |
| Scheme | `https` only, case-insensitive on input and canonicalized lowercase |
| Host | DNS hostname required; canonical ASCII IDNA form; maximum 253 characters |
| IP literals | Rejected, including IPv4-in-IPv6 and unusual numeric encodings |
| User information | Rejected; no `user:password@host` |
| Port | Absent or 443 only |
| Fragment | Removed before network use/storage |
| Redirects | Media: maximum 12; feed/artwork/chapter: maximum 5; HTTPS→HTTPS only; every destination fully revalidated |
| DNS result | Every returned address must be globally routable; any loopback, private, link-local, multicast, unspecified, documentation, benchmarking, reserved, or carrier-grade NAT address rejects the host |
| Cookies | Disabled |
| Authorization | Explicit per-subscription HTTPS Basic only after a bounded challenge; one retry per hop; original feed folder plus one user-approved audio scope; no ambient forwarding |
| User-Agent | Static `PlainPodDroid/<version>` only |

Query/path tokens in HTTPS private-feed URLs are permitted, stored app-private, and redacted from ordinary display and diagnostics.

IPv6 DNS classification rejects both documentation blocks (`2001:db8::/32` and
`3fff::/20`). The IETF protocol-assignment block `2001::/23` is non-public by
default; its globally reachable anycast, AMT, AS112 and DET allocations retain
their existing eligibility. ORCHID, benchmarking and transition ranges remain
excluded. Classification follows the
[IANA special-purpose registry](https://www.iana.org/assignments/iana-ipv6-special-registry/).

## Offline mode

The existing Offline mode preference now denies all app HTTP and DNS activity,
not only audio. A shared client guard rejects requests before downstream work,
tracks calls through response-body lifetime and revokes active calls on policy
changes. DNS checks both before and after resolution. Streaming clients inherit
the same guard; no transport has an offline exception. Enabling Offline mode
cancels automatic jobs; late callbacks cannot schedule them while it remains on.
Cached artwork and local media/metadata remain available. Explicit OPML file
import/export is local. Offline cancellation does not penalize a feed’s scheduled
failure/backoff state. Existing malformed-preference fail-closed behavior remains.

## Basic login

Username/password inputs are limited to 512 UTF-16 units each, with no controls,
unpaired surrogates or username colon. Audio grants use one canonical HTTPS
address ending in `/`, without query/userinfo. Encoded separator/backslash paths, double encoding
and decoded dot traversal are excluded from credential scopes. Challenges have at
most four headers, 4,096 total characters and a 256-character realm; only Basic
and its optional UTF-8 charset are supported. One credential retry per hop is
allowed; each HTTP attempt retains existing timeouts, and downloads retain their
existing overall transfer deadline. Rejected stored credentials are suspended.
Ciphertext is versioned AES-GCM with a fresh 12-byte nonce, 128-bit tag, original
feed URL associated data and a 16 KiB storage cap. See `FEED_AUTHENTICATION.md`.

## HTTP execution

| Property | Feed/artwork/chapter | Media stream/download |
|---|---:|---:|
| Connect timeout | 10 seconds | 10 seconds |
| Response-header timeout | 15 seconds | 20 seconds |
| Read-idle timeout | 20 seconds | 30 seconds |
| Whole call timeout | 30 seconds | None for active playback; download watchdog applies |
| Concurrent calls | 4 total; at most 2 feed parses | 2 transfers; playback has priority |
| Redirects | 5 | 12 |
| Retry | No automatic retry for non-idempotent operations; at most 1 transport retry for GET | At most 1 resume/reconnect when byte-range semantics are valid |
| Automatic refresh floor | 15 minutes per feed | Not applicable |
| Failure backoff | Exponential, 15 minutes to 24 hours with jitter | User action or scheduled policy |

Only `identity` and `gzip` content encodings are accepted for XML/JSON/text resources. Brotli and nested/multiple encodings are excluded initially.

The pinned HTTP client limits each decoded HTTP/2 header block to 256 KiB before
transport-specific header validation. This also bounds HPACK expansion; ordinary
transport field/count limits still apply.

## XML feed

| Property | Limit |
|---|---:|
| Downloaded body (gzip or identity) | 32 MiB |
| Expanded body | 32 MiB |
| Expansion ratio | 20:1 after the first 4 KiB compressed |
| Nesting depth | 64 elements |
| Attributes | 64 per element |
| Attribute value | 4,096 characters |
| Episodes accepted per response | 10,000 |
| Aggregate text | 24 × 1,024 × 1,024 UTF-16 characters |
| Feed/show title | 512 characters |
| Episode title | 1,024 characters |
| Author | 256 characters |
| Identifier/GUID | 2,048 characters |
| Description/content before plain-text conversion | 512 KiB per item |
| Stored/displayed plain notes | 128 KiB per item |
| Categories | 64 items, 128 characters each |
| Enclosures per episode considered | 8; select one eligible audio enclosure |
| XML entity policy | DTDs, declarations, external/parameter/custom named entities: zero; valid decimal/hex character references and the five predefined XML entities are accepted |

The parser stops after the item ceiling rather than retaining a partial attacker-selected tail. A feed with malformed structure fails as a unit; the prior valid database state remains.

## Apple Podcasts link lookup

Explicit Preview accepts modern HTTPS `podcasts.apple.com` show/episode links.
Only the path ID and two-letter storefront code enter a fixed
`https://itunes.apple.com/lookup` request; query tracking is discarded. Episode
links resolve their containing show and are labeled before Subscribe. A lookup
uses no credentials, cookies, referrer, API key, retry, redirect, regional fallback
or HTML scraping. The lookup uses existing public-DNS/system-trust and metadata
concurrency/time limits. It requests identity encoding and accepts JSON or the
endpoint’s JavaScript MIME labels, parsed strictly as JSON with no execution.

| Property | Limit |
|---|---:|
| Downloaded JSON | 256 KiB; identity only |
| JSON nesting | 16 levels |
| JSON values and keys | 5,000 tokens |
| JSON key / string / number | 128 / 4,096 / 64 characters |
| Results | Exactly one matching podcast, or an explicit no-public-feed error |

Duplicate keys, mismatched IDs, malformed UTF-8/JSON, unsafe feed URLs and
unsupported responses fail closed. The RSS URL then goes through the ordinary
feed acquirer and confirmation. Preview shows Apple provenance, RSS source/final
hosts and an explicit selectable full-address dialog. Only the resolved original
RSS URL is stored; refresh, OPML and episode IDs use it directly. Cancelling or
superseding resolution prevents the next feed request when the lookup returns.

## OPML

| Property | Limit |
|---|---:|
| Input bytes | 2 MiB |
| Nesting depth | 16 outlines |
| Total outline elements | 10,000 |
| Candidate feed URLs | 5,000 before deduplication |
| Imported subscriptions | 1,000 total app subscriptions |
| Label/title | 512 characters |

Every candidate URL passes the ordinary URL/origin policy. Import shows accepted, duplicate, and rejected counts before commit. It never fetches feeds until the user confirms.

## Artwork

| Property | Limit |
|---|---:|
| Encoded response | 20 MiB |
| Accepted formats | JPEG, PNG, static WebP |
| Source dimension | 4,096 px on either axis |
| Source pixels | 16 megapixels |
| Decoded target | At most 1,024 × 1,024, sampled before allocation |
| Cached thumbnail | 2 MiB PNG maximum, plus 16-byte cache header |
| Disk cache | 64 MiB including headers, at most 512 entries; least-recently-used |
| Memory cache | 16 MiB of decoded thumbnails; shared across UI views |

SVG, GIF animation, remote fonts, data URLs, and inline XML artwork are rejected. Failed artwork never prevents feed/episode use.

The user authorized the 20 MiB encoded cap on 2026-10-09. Dimension, pixel,
redirect, public-DNS, timeout, format and expansion limits remain unchanged.
The UI samples covers to at most 512px and persists only decoded/re-encoded PNG
thumbnails under app-private cache storage. SHA-256 filenames include target
size; URLs/tokens are not stored. Writes use a temporary file and rename. Every
disk read rechecks file length/header and image geometry before decoding.
Cached images are fresh for seven days; stale images stay visible during refresh.
Temporary failures back off from 30 seconds to one hour; rejected payloads wait
24 hours. Retry timers run only while the activity is visible. Cache deletion by
the OS, corruption or write failure does not prevent ordinary fetching.

## Podcasting 2.0 JSON chapters

| Property | Limit |
|---|---:|
| Response | 512 KiB |
| Chapters | 1,000 |
| Visible chapter rows | 200 per page; all 1,000 reachable; hidden `toc:false` entries omitted |
| Title | 256 characters |
| URL | 4,096 characters and ordinary HTTPS policy |
| Image URL | Ordinary HTTPS/artwork policy |
| JSON nesting | 16 levels |
| JSON tokens | 20,000 |
| Number token | 64 characters |

Chapter timestamps round to the nearest millisecond. Very small exponents are
handled without constructing scale-sized decimal powers, so a short numeric
token cannot trigger an unbounded allocation or computation during rounding.

The feed reference requires the exact `application/json+chapters` MIME token.
An explicit document request requires the same response MIME type, optionally
with one UTF-8 charset parameter. Only identity or gzip encoding is supported.
Compressed and expanded documents each have the 512 KiB ceiling; gzip expansion
is limited to 20:1 after the first 4 KiB expanded. Requests use the existing
metadata/global concurrency, timeout, public-DNS, HTTPS, and five-redirect limits.

Chapter retrieval occurs only after “Load chapters from publisher”; opening feed,
details, or playback never fetches them. Documents remain in memory for the
details view. Titles are plain text; chapter art is never fetched or decoded.
A local fallback reads only already acquired, bounded plain notes (128 KiB).
It accepts one list of at least three strictly increasing MM:SS/HH:MM:SS entries,
up to 1,000 chapters with 256-character titles, within media limits and known
feed duration. Malformed times, duplicate/descending entries and ambiguous lists
are rejected. Older flattened notes require an explicit Outline/Chapters/Timestamps
heading. Loaded structured data takes priority, including an empty chapter list.
Paragraphs/line breaks remain inert plain text; no HTML renderer is used.

Seek notches and scrub previews do not contact publishers or execute commands.
The native chapter sheet pages/scrolls through all visible entries. Current-item
selection uses a guarded absolute seek and preserves play/pause; another episode
requires explicit Play from here, subject to the existing opaque-ID/privacy rules.
External pages remain separate validated actions. A publisher-load failure leaves
local outline navigation and playback usable. No remote VTT, HTML, or
executable chapter payload is supported in v1.

## Media

| Property | Limit/policy |
|---|---|
| Transport | Progressive HTTPS only |
| Eligible MIME | `audio/*`; `application/octet-stream` only with a recognized audio extension |
| Recognized extensions for generic MIME | `.mp3`, `.m4a`, `.aac`, `.ogg`, `.oga`, `.opus`, `.flac`, `.wav` |
| Explicitly excluded | HLS, DASH, SmoothStreaming, RTSP, live streams, video, MIDI, arbitrary playlists |
| Single completed download | 2 GiB |
| Streaming response body, including unknown length | 2 GiB; excess aborts the response before exposure |
| Durable manual download requests | 100 total waiting/active/failed opaque IDs; typed failures and attempt ownership; FIFO jobs |
| Active download wall time | 24 hours; 30-second read-idle timeout still applies |
| Reported/parsed duration | 48 hours |
| Default download quota | 2 GiB |
| Configurable quota range | 512 MiB–20 GiB |
| Temporary file age | 24 hours before cleanup |
| App-private download directory entries | 400,000 controlled files maximum per scan |

Remote filenames and Content-Disposition values never become filesystem paths. Local names are opaque database IDs plus a controlled suffix. Downloads are written to a temporary app-private file, synced/closed, then atomically renamed and recorded in a transaction. Startup cleanup removes only expired controlled temporary files; unexpected names, symbolic links, or an entry count above the scan ceiling fail media storage closed.

RSS enclosure length is advisory: a host may serve a different representation
after transcoding or inserting ads. A download must match the actual HTTP
Content-Length when present; malformed, zero, duplicate or excessive values are
rejected. Without that header, completion requires a nonempty body and clean EOF.
Both paths enforce the actual-byte quota, single-download cap, timeout, response
type and redirect policy before committing a complete file.

A zero RSS enclosure length means the publisher did not provide a usable size;
it is accepted as an advisory hint, including in existing stored rows. Keep that
stored value for the catalog completion compare-and-set; never use it as the
actual completed byte count. HTTP Content-Length zero and empty bodies remain
invalid. Audio may traverse up to 12 redirects to accommodate publisher and
measurement chains, with loop detection and the same URL/DNS/TLS/auth checks at
every hop. Streaming and downloads share this media ceiling.

## Database and stored state

| Property | Limit |
|---|---:|
| Subscriptions | 1,000 |
| Episodes retained per subscription | 10,000 metadata records |
| Global episode records | 100,000 |
| Catalog browse page | 50 episodes by default; 200 maximum |
| Episodes rendered by one browse screen | 200 |
| Catalog browse offset | 0–10,000 episodes per subscription |
| Queue entries | 2,000 |
| Queue entries rendered per page | 25 |
| ETag/Last-Modified | 512 characters each |
| Redacted error text | 1,024 characters |
| Local search query | 256 characters |

SQLite foreign keys are enabled. URL, GUID, and composite feed/episode identities have uniqueness constraints. All values use bound parameters. Database mutations that combine feed and episode state use a transaction.

Queue storage contains only opaque internal media IDs, a contiguous position,
and an insertion timestamp. Queue reads join only bounded display metadata and
never expose feed or media URLs. Append, play-next, remove, and reorder validate
the full bounded position set before and after one transaction. The UI renders
25 entries per page rather than constructing up to 2,000 episode views at once.

## UI, intents, and IPC

| Property | Limit/policy |
|---|---|
| Shared text accepted | 4,096 characters; `text/plain` only |
| Silent subscription | Never |
| OPML access | User-initiated SAF content URI with provider authority, at most 4,096 characters; file/network URI results are rejected |
| Rendered remote text | Plain text; no WebView or HTML-capable widget |
| External link | Explicit tap, revalidated HTTPS URL, dispatched outside the app |
| Notification title | 256 characters |
| Notification subtitle | 512 characters |
| Media controller | Same UID/package or system-trusted controller only |
| Custom session commands | None from external controllers in v1 |

## Refresh and retention defaults

- Automatic refresh defaults to every 6 hours; choices are off, 1, 3, 6, 12, or 24 hours.
- A manual refresh may bypass the schedule but not the one-in-flight rule or transport limits.
- Completed status is set when at least 95% has played or at most 2 minutes remain, whichever happens first after at least 5 minutes of playback.
- Playback progress writes are coalesced to at most once every 15 seconds and immediately on pause/stop/episode change.
- Feed refresh retains user state and downloaded/queued episodes even when an item disappears from the latest feed.
- Unplayed, queued, downloaded, or favorited records are never automatically removed solely because of feed age.

## Download before playing

A separate audio-only preference resolves eligible episodes to an internal
`plainpoddroid-download:episode-<64 lowercase hex>` identifier. It contains no
publisher URL or file path. The loader waits on the existing serial download
lane and opens only a verified complete private file, preserving byte offsets
and reader leases. Cancellation revokes queued/active preparation; no autoplay
intent is persisted. Offline mode still blocks all network acquisition.

## Scheduled preparation

One opt-in weekday/local-time schedule uses a persisted, non-exported platform
job. The non-exported boot/time/package receiver is disabled until opt-in.
No exact alarm, deadline override, notification permission or foreground fetch
service is added. Jobs contain only due time, configuration revision, timezone
and effective network constraint. No URL, credential, title or playback intent
enters scheduler extras or the source-free last-run status.

An occurrence may start/continue at most two hours after its local due time.
DST gaps move forward, overlaps run only once, and timezone/clock changes
reconcile the next occurrence. Completed/missed occurrences use a watermark so
clock rollback cannot replay older work. Retired attempt IDs cannot update a
continuation's progress. Missing constraints pause work; failed durable state
writes suppress further networking. Reboot/app restart preserves the current
eligible occurrence, progress and its reserved transfer budget.
Offline pauses retain the scheduled due time, including unstarted work, so
returning online can resume within the same window without replaying a completed
occurrence or removing the continuation delay.

Preparation respects Offline mode, charging, its own unmetered preference and
the automatic refresh/download network preferences. Both DNS and sockets use
only the system-selected job network, with no default-route fallback. Existing
HTTPS/public-address/system-trust/login-scope guards and shared call ceilings
remain in force. Losing/replacing that network revokes the attempt and bodies.

Refreshes share ordinary per-subscription ownership, retain failure backoff and
skip healthy feeds refreshed within 15 minutes. Automatic downloads share the
serial transfer lane and yield to waiting playback, in batches of at most three.
An occurrence reserves at most 24 transfer attempts, including failed or
interrupted attempts; reservations are saved before acquisition. Continuations
wait at least 15 minutes and must fit within the two-hour window. Per-episode
retry limits and the existing storage quota still apply. At most eight unique
available show covers per batch enter the shared 64 MiB disposable cache, as
validated 128px and 512px PNG thumbnails; fresh covers are not fetched again.
Optional artwork failures never invalidate downloaded audio. Android may defer
jobs; preparation never starts playback.
