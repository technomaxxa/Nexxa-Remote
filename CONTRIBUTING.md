# Contributing

Thank you for considering a contribution to Nexxa Remote.

## Before opening an issue

- Check existing issues for duplicates.
- Include the Nexxa version, Windows version/build, Java runtime provider/version, Tailscale version, and a concise reproduction.
- Remove all private information: Tailscale IPs, LAN IPs, usernames, hostnames, invitation URLs, session keys, certificate fingerprints, screenshots with personal information, and files from `%APPDATA%\NexxaRemote`.
- For security vulnerabilities, do not use a public issue. Follow [SECURITY.md](SECURITY.md).

## Development setup

1. Install JDK 21 or newer.
2. Clone the repository.
3. Run `Build Nexxa Remote.bat`.
4. Run `Run Self-Test.bat` or execute the JAR with `--self-test`.
5. Test Windows-specific changes on real Windows 10/11 devices.

## Pull requests

- Keep changes focused and explain why they are needed.
- Update `README.md` or the relevant file under `docs/` when behavior changes.
- Add or update self-tests for platform-independent logic.
- Do not commit runtime downloads, generated build folders, local identity files, logs, or invitation links.
- Keep host and viewer protocol changes compatible within a release, or clearly document when both sides must update.
- Never add telemetry, remote endpoints, or automatic data collection without documenting it and obtaining appropriate user consent.

## Code style

- Use standard Java formatting and descriptive names.
- Keep Swing UI changes on the Event Dispatch Thread.
- Avoid blocking network, disk, or PowerShell operations on the UI thread.
- Treat all remote protocol input as untrusted and validate lengths before allocation or processing.
- Do not weaken TLS, certificate pinning, host permission checks, or invitation-key validation.
