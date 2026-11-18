# Testing

## Built-in self-test

Run from the repository root:

```powershell
java -Djava.awt.headless=true -jar NexxaRemote.jar --self-test
```

The self-test covers the platform-independent parts of the current implementation:

- Local TLS server/client handshake and pinned-certificate verification.
- Invitation creation and parsing.
- Viewer Fit rectangle bounds and aspect-ratio preservation.
- JPEG encoding, decoded dimensions, frame-size limits, and a relative image-fidelity comparison for the High quality profile.
- Tailscale IPv4-range validation and parsing of synthetic peer-status fixtures.
- Explicit UAC restart permission flag parsing.
- Host-input priority decision logic.

## What the self-test does not prove

A passing self-test is not an end-to-end validation of all Windows features. It does not prove:

- A connection between two physical PCs or two different Tailscale accounts.
- Correct behavior under all Windows Firewall policies, NATs, sleep/resume cycles, or network changes.
- Global low-level keyboard/mouse hook operation or input behavior across integrity levels.
- UAC behavior on every Windows account configuration or secure-desktop prompt.
- Actual desktop-audio capture availability or quality.
- Sustained FPS, latency, or bandwidth on a particular network.
- Security against a malicious local process or a compromised endpoint.

Test those behaviors manually on dedicated Windows machines before a release. Never use live invitation links or actual device addresses as test fixtures in committed source.
