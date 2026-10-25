# Security reporting

Please keep vulnerability details, private feed links, passwords, tokens and
personal library data out of public issues and pull requests.

Use the repository's [private vulnerability reporting form](https://github.com/jnesew/PlainPodDroid/security/advisories/new)
when it is available. If private reporting is unavailable, open an
[issue](https://github.com/jnesew/PlainPodDroid/issues) containing only a request
for a private reporting channel. Wait for that channel before sharing details.

A private report should include:

- The affected release version or exact source commit, and Android version.
- Steps to reproduce using synthetic feeds or other non-sensitive fixtures.
- The expected behavior, observed behavior and potential impact.

The first signed release is being prepared. For development builds, include the
source commit so the maintainer can identify the affected code.

The public [threat model](docs/THREAT_MODEL.md),
[input and resource limits](docs/SECURITY_LIMITS.md), and
[feed authentication boundaries](docs/FEED_AUTHENTICATION.md) describe the app's
security expectations.
