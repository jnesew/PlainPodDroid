# Private feeds and HTTPS Basic login

Implementation reference; device/live-provider acceptance remains pending.

Personal HTTPS RSS URLs with path/query access tokens remain ordinary feed URLs.
Synthetic publisher tests exercise the production feed/media adapters through
preview, confirmation, conditional refresh, download and streaming range requests.
Revoked tokens cannot replace saved episodes; refresh can replace an expired
signed audio URL without changing a GUID-based episode identity. These tests do
not claim live provider, socket/TLS, Android SQLite or decoder acceptance.

## Basic login boundary

Basic username/password login is opt-in per subscription. Only HTTPS public
origins remain eligible. The feed's original server and parent path form its
login scope; redirects outside that scope receive no credentials. The user may
explicitly approve one additional HTTPS audio address/path using the same login.
Otherwise audio can use only the feed scope. Cookies, OAuth, Digest, provider
SDKs, payment, client certificates and proprietary media remain excluded.

Send credentials only after a bounded HTTP 401 Basic challenge, at most once
per HTTP exchange, never preemptively or as URL userinfo. Support the server's
UTF-8 challenge and an ISO-8859-1 default without lossy character replacement.
Keep credentials out of artwork, chapter, website and OPML requests. Each audio
request resolves its subscription from the original catalog media URL; ambiguous
owners fail closed. Redirects keep that owner and recheck the approved scope.

Reject control characters, colon-containing usernames and oversized input.
Scope comparison must not permit encoded separators or decoded dot traversal. Retain existing DNS, TLS, redirect, byte, concurrency and per-attempt timeouts;
a Basic retry is bounded to one per hop, within the download transfer deadline.

## Credential lifecycle

Pending preview credentials are revocable and memory-only and never placed in instance state,
intents, URLs, diagnostics or public preview metadata. Cancel/close/input replacement revokes preview authority; an unstarted
preview makes no request. Saved credentials are rechecked after a challenge
before retrying, so removal during the initial request cannot authorize that
retry. Explicit Subscribe saves
credentials with the catalog transaction. Persist only authenticated ciphertext,
using an app-specific Android Keystore AES-GCM key and fresh nonce. Bind ciphertext
to the original subscription URL as associated data. Do not silently regenerate
an unavailable key while reading existing ciphertext; report that login must be
updated. An explicit save can recreate/replace only that feed’s key. Clearing
Login or archiving deletes its row transactionally, then removes its Keystore key
after commit (best effort during a Keystore outage).

Updating or removing Login in podcast settings resets authentication suspension.
A rejected stored login suspends further background attempts for the affected
feed/audio scope until the user updates it. Archives erase login data; deleting
a subscription cascades its encrypted row. OPML retains its existing private-URL
disclosure but never exports Basic usernames/passwords. No production signing
key, network permission, exported component or third-party dependency is added.

## Required verification

- Enforced path/query tokens and XML URL escaping across the private-feed flow.
- One Basic retry after a challenge; incorrect credentials and unsupported
  schemes stop; UTF-8/default encoding and malformed/oversized challenges.
- Scope, redirect and owner isolation, including same-host unrelated paths,
  cross-origin media, ambiguous owners, archives and revoked saved credentials.
- Range requests retain their method/range while authentication changes only
  the approved header; errors do not reveal tokens or credentials.
- Ciphertext integrity, randomized nonces, associated-data swapping, unavailable
  keys, fresh install, restart, atomic confirmation, migration and deletion.
- Device: login editing/cancellation, process recreation, background jobs,
  actual playback and Android Keystore. Live paid-provider compatibility requires
  a valid subscriber feed and is separate from synthetic acceptance.
