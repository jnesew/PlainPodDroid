# PlainPodDroid threat model

Status: implementation baseline  
Version: `threat-model-0001`  
Date: 2026-08-26

## Security objective

PlainPodDroid must let a user subscribe to, download, and play ordinary audio podcast feeds without giving an untrusted publisher, network endpoint, local application, or build dependency an easy path to:

- execute active content;
- reach services on the user's device or private network;
- exhaust memory, storage, threads, battery, or database capacity;
- silently add subscriptions or trigger downloads/playback;
- disclose unrelated subscriptions, listening history, or tokenized feed URLs;
- persist data outside the app-private or user-selected storage boundary; or
- expand the app into an account, telemetry, discovery, advertising, or remote-control platform.

The app reduces risk; it cannot make an unpatched Android OS, compromised device, or vulnerable platform media decoder safe.

## Assets

| Asset | Required protection |
|---|---|
| Subscription URLs | Confidentiality where URLs contain private-feed tokens; integrity against silent replacement |
| Listening history and progress | App-private confidentiality and database integrity |
| Queue and downloaded episodes | Integrity, bounded storage use, predictable deletion |
| Device and LAN services | No access initiated from attacker-selected podcast URLs |
| Playback controls | Only deliberate user actions and trusted system media controllers |
| Application availability | Malformed inputs must fail locally without persistent crash loops or unbounded retry |
| Build and signing chain | Pinned inputs, verified dependencies, no committed signing secrets |

## Adversaries and untrusted inputs

1. A deliberately malicious feed publisher.
2. A compromised legitimate publisher, redirector, CDN, artwork host, or media host.
3. An on-path attacker limited by the platform TLS trust model.
4. A malicious local app sending intents or attempting to connect to the media session.
5. A malicious or accidentally pathological OPML file selected by the user.
6. A dependency or build-repository compromise.
7. Corrupt local state caused by interrupted writes or an app defect.
8. A user who pastes a mistyped, look-alike, or impersonating feed URL without realizing that feed metadata is self-asserted.

All feed XML, OPML, URLs, HTTP metadata, compressed bodies, text fragments, artwork, chapter documents, and media bytes are hostile regardless of whether they use HTTPS.

## Trust boundaries

| Boundary | Rule |
|---|---|
| User text/share intent → candidate subscription | Normalize and validate, fetch a bounded preview, then require confirmation |
| DNS/URL → network connection | HTTPS hostname only, public addresses only, manual bounded redirects |
| Network response → parser | Enforce compressed and expanded byte limits before semantic parsing |
| Parser → database | Store typed, length-bounded, normalized fields; use parameterized SQL only |
| Database → UI/notification | Render text, never active HTML; clamp externally visible metadata |
| URL → artwork/media decoder | Revalidate through the same network policy; restrict type/size before decode |
| External app → exported component | Narrow intent filters, size/type validation, and explicit user confirmation |
| Controller → media session | Same-app or trusted system controller; no attacker-defined custom commands |
| App → filesystem | App-private generated names; SAF only for explicit OPML import/export |
| Build files → executable APK | Fixed versions, dependency verification, locked graph, reviewed updates |

## Abuse cases and controls

| Abuse case | Primary controls | Required regression test |
|---|---|---|
| XML external entity reads a file or URL | Reject DOCTYPE; disable external general/parameter entities and external DTD loading; parser performs no I/O; legal numeric character references remain compatible | XXE and billion-laughs inputs fail closed while decimal/hex references parse as text |
| Huge or deeply nested feed causes OOM/stack exhaustion | Streaming SAX parser; byte, nesting, item, attribute, and field limits | Boundary and limit+1 fixtures |
| Gzip bomb bypasses Content-Length | Count compressed and expanded bytes; permitted encodings only; expansion-ratio cap | Small compressed bomb is rejected before parse completion |
| Redirect reaches router, loopback, or metadata service | Manual redirects; public-only DNS result set at every hop; no IP literals | Redirect and mixed-DNS private-address cases are rejected |
| DNS rebinding changes address after validation | One policy DNS resolver supplies the validated addresses used by OkHttp | Resolver test returns mixed/rebound addresses and fails closed |
| HTTP downgrade exposes tokenized URL or media | HTTPS-only URL type and redirect policy; cleartext disabled in manifest/network config | HTTP input and HTTPS→HTTP redirect rejected |
| Look-alike feed silently impersonates a show | Display canonical ASCII host and meaningful redirect destination; explicit confirmation; no directory authenticity claim | Share/paste flow cannot persist before confirmation |
| Feed URL leaks a token through logs or UI | No body logging; redact userinfo/query in diagnostics and ordinary UI; backup disabled | Redaction tests for query and path token patterns |
| Host sets cookies that affect another request | Cookie jar disabled; no ambient web cookies; no ambient authorization; only explicitly scoped Basic challenges | Response cookies are not returned on later calls |
| HTML show notes execute script or load remote content | No WebView; convert to bounded plain text; external page opens only by user action | Script/style/entity/link payload renders inert text |
| Malicious artwork exhausts bitmap memory | Bounded download, header probe, dimension/pixel caps, sampled decode, restricted raster formats | Oversized dimensions and body rejected |
| Media URL points to video/playlist/unbounded data | Progressive HTTP(S) audio only; MIME/extension policy; download and duration limits; no HLS/DASH/RTSP modules | Playlist, video type, oversized body, and endless stream fail safely |
| Remote filename traverses paths or overwrites data | Ignore remote filename; generated opaque local filename; atomic temp-to-final move | Traversal and collision names never influence local path |
| OPML adds thousands of feeds or private endpoints | Byte/item/nesting limits; validate every URL; preview summary; one explicit import confirmation | Oversized, nested, duplicate, and private-host OPML cases |
| Local app silently subscribes via share/deep link | Only `ACTION_SEND` text is accepted; candidate is shown but not saved; no custom URI scheme | Crafted intents cannot subscribe, download, or play without UI confirmation |
| Untrusted controller starts playback | Restrictive `MediaSession.Callback`; allow same UID and trusted system controllers only | Untrusted controller connection/custom command denied |
| Refresh failure creates battery/network loop | JobScheduler constraints, minimum interval, exponential backoff, one in-flight refresh per feed | Repeated failure does not bypass minimum delay |
| Database update is partially applied | Transactions, uniqueness constraints, foreign keys, idempotent upsert, migration tests | Interrupted refresh leaves previous valid feed state |
| Dependency changes without review | Exact versions, no dynamic/range versions, strict checksum verification and lock files | Clean build fails for altered/unlisted artifact |

## Security properties by lifecycle

### Subscription

Pasting or sharing a URL creates only an in-memory candidate. The app validates the canonical URL, fetches a bounded preview, displays the canonical origin and redirect result, and requires a separate confirmation to store it. Feed title, author, description, and artwork are labeled by context as publisher-supplied metadata, not proof of identity.

### Refresh

Only stored, previously validated feed URLs are eligible. Conditional requests may use bounded ETag and Last-Modified values. Redirects never rewrite the stored canonical subscription without explicit user approval. A refresh transaction either commits a valid bounded result or preserves the previous state with a redacted error.

### Rendering

Chapter documents add a tracking and resource-exhaustion surface. Fetching is
only triggered by an explicit publisher-contact action, never feed refresh,
details navigation, or playback. Every destination uses the shared HTTPS/public
DNS policy and bounded acquisition; both wire/expanded bytes, compression ratio,
redirects, schema, token/depth/chapter counts, and rendered rows have ceilings.
Chapter titles are inert plain text. Image metadata never triggers a request or
decode. Playing from a chapter and opening its external page are separate user
actions; playback supplies only the catalog episode ID, and download-only policy
remains centralized. Documents are view-lived memory, discarded on navigation,
failure, or closure; queued closed-view work cannot initiate a later request.
In-flight requests remain bounded by the existing call timeout. Enrichment
failure never stops existing playback or changes the audio source.

The app has no embedded browser. Feed text is normalized to inert plain text. The user may explicitly open the canonical episode/site URL in another installed application; PlainPodDroid does not fetch the resulting page.

### Playback and download

Streaming and downloading use the same public-network-only client as feed refresh. Media is progressive HTTPS audio; adaptive manifests and live streams are outside scope. Downloads use app-private storage, generated names, quotas, and atomic completion. Media3 and platform codecs remain a privileged attack surface, so accepted media types and resource bounds are deliberately narrow.

The activity connects a `MediaController` only while visible and sends only an
opaque internal media ID after an explicit Play action. It never gives Media3
a catalog URL. The service re-resolves bounded metadata from app-private
storage and chooses either a verified completed file or a policy-bound HTTPS
source. Episode title and details actions remain non-playing.

When the opt-in “Offline mode” setting is enabled, the playback boundary
accepts only a verified complete app-private file. UI checks provide a useful
message, but the Media3 service independently rejects remote or partial media
so queue advance, controller commands, and races cannot silently stream. This
reduces playback-timing disclosure. The shared audio download coordinator also
rejects transfers before contact and checks the policy during transfer/commit.
Interrupted partial media is discarded; automatic candidates are deferred without
consuming retries. Metadata actions (feed refresh, artwork, explicit chapters and Apple lookups)
are blocked by the same application-wide offline boundary. Waiting manual downloads are discarded on enabling
Offline mode; the durable manual queue is bounded to 100 opaque request/episode IDs.

The durable queue accepts only opaque internal media IDs. Queue operations are
local transactions and never start playback, streaming, or downloading. Reads
return bounded display metadata without source URLs, validate a contiguous
position set, and render in fixed-size pages; deleting an episode removes its
queue reference through the database foreign key.

### Deletion and export

Removing a subscription deletes its app-private episodes, queue references, progress, artwork, and downloads in a transaction or recoverable cleanup pass. OPML export contains feed URLs—including private tokens when present—only after an explicit user-selected destination; the confirmation warns that the file may contain access tokens.

Picker results must be bounded `content://` URIs with provider authority before
any import/export stream is opened. File and network URI results are rejected;
a document picker cannot borrow the app's privileges to overwrite private files.

## Privacy model

- No account, sync, analytics, telemetry, ads, crash reporting, remote configuration, or provider search.
- Each subscribed feed/media/artwork host necessarily learns the user's network address and request timing.
- The app sends a static product/version User-Agent without device model, installation ID, locale fingerprint, or subscription list.
- Release logs exclude response bodies, feed titles, full URLs, query strings, and playback history.
- Android cloud/ADB backup is disabled. Device-level file-based encryption and the Android application sandbox protect data at rest.

## Explicitly out of scope

- A rooted, physically compromised, or malicious OS/device administrator.
- Unknown vulnerabilities in Android, Media3, TLS providers, or platform media/image codecs.
- Publisher identity or copyright authenticity beyond the user-selected HTTPS origin.
- Anonymity from podcast hosts, CDNs, DNS resolvers, or the user's network provider.
- DRM, provider/platform account integrations, browser cookies, Digest/OAuth authentication, client certificates, Tor, proxy configuration, casting, LAN media, and remote playback.

## Review triggers

Reopen this model before adding any new exported component, parser, content type, network origin class, authentication mechanism, WebView/HTML renderer, media transport, cloud feature, SDK, native library, or permission. Dependency updates and target-SDK changes require checking their security and behavior-change notes.

## Accepted Basic login extension (2026-10-13)

Publisher usernames/passwords are a new confidential asset. They remain in memory
until explicit Subscribe and are then stored as AES-GCM ciphertext in a private
catalog child row, atomically with confirmation. The Keystore key is app-specific
and per feed; decrypt never creates a missing key. Original feed URLs bind the
authenticated ciphertext. Archive erases the login row; delete cascades it.
OPML and public metadata carry no Basic login data.

Credential authority follows the original subscription/media owner across every
redirect. Only the original feed folder and a user-approved additional audio
server/path receive credentials, after a bounded Basic 401 challenge. An
ambiguous audio owner fails closed; artwork, chapters and web pages never receive
this login. Same-host unrelated paths, cross-origin substitution, encoded path
traversal and proxy authentication cannot inherit credentials. Wrong saved
credentials suspend that scope; automatic audio downloads do not retry denial.
A late failure cannot suspend changed credentials. Preview cancellation revokes
its login authority, and retries recheck saved authority after a challenge. Key loss, tampering and
owner swapping report an update-login failure without revealing secrets.

Protocol/cipher tests and host SQLite execution cover these boundaries; Android
Keystore, transaction, restart/migration and view-state tests require execution
on a device. Full details: `FEED_AUTHENTICATION.md`.

## Authoritative implementation references

- Android network security configuration: https://developer.android.com/privacy-and-security/security-config
- Media3 background playback and service lifecycle: https://developer.android.com/media/media3/session/background-playback
- Media3 releases: https://developer.android.com/jetpack/androidx/releases/media3
- Gradle dependency verification: https://docs.gradle.org/current/userguide/dependency_verification.html
- Gradle dependency locking: https://docs.gradle.org/current/userguide/dependency_locking.html

### Complete offline boundary (2026-10-20)

Offline mode now extends the existing audio restriction to all app networking.
A shared HTTP interceptor tracks calls until their response bodies close,
revokes calls on a policy transition and rejects stale bodies even after a quick
return online. DNS checks deny resolution and discard answers obtained while
the policy changes. Client clones inherit this boundary. Automatic refresh and
download jobs are suppressed while offline; metadata/artwork/Apple/chapter paths
have no exception. Cached local content and OPML file operations remain usable.
