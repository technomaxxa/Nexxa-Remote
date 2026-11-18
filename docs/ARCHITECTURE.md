# Architecture

Nexxa Remote v0.7.5 is a single-process Java Swing desktop application. The source is currently maintained in `NexxaRemote.java` and uses standard Java APIs; Windows integration is delegated to Windows PowerShell and Win32 APIs invoked by helper code.

## Runtime components

| Component | Responsibility |
|---|---|
| `MainWindow` | Main Swing window, host/viewer tabs, app shutdown, full-screen/connected layout |
| `TailscaleManager` | Locates `tailscale.exe`, reads `tailscale status`, lists peers, manages service lifecycle and optional firewall rule |
| `IdentityStore` | Creates/loads a persistent X.509 host identity and protects its password with Windows DPAPI |
| `HostPanel` | Host configuration, invitation, device list, activity console, permissions and settings |
| `HostServer` | TLS listener, session-key authentication, screen/input/clipboard/audio message handling |
| `ViewerPanel` | Parses invitations, pins the host certificate, connects, decodes frames, sends viewer input and optional clipboard/audio requests |
| `ScreenPanel` | Draws the newest decoded frame, fits it without cropping, and overlays the host cursor |
| `JpegEncoder` | Reuses a Java ImageIO JPEG writer and downsamples to the selected stream profile |
| `LocalInputMonitor` / `NexxaPhysicalInputHooks` | Uses Windows low-level keyboard/mouse hooks to detect physical host input and distinguish injected Nexxa input |
| `AudioLine` | Best-effort Java Sound recording/playback endpoint selection |

## Connection sequence

1. The Tailscale client establishes the private network independently of Nexxa.
2. Nexxa reads the host's assigned Tailscale IPv4 address.
3. The host loads its persistent certificate identity and opens an `SSLServerSocket` on the Tailscale address and configured TCP port.
4. The host generates a random access key and shares a `nexxa://connect?...` invitation containing the host address, port, certificate SHA-256 fingerprint, and access key.
5. The viewer parses the invitation, establishes TLS 1.2 or TLS 1.3, verifies the certificate fingerprint, and submits the access key.
6. The host compares the key in constant time and accepts one active viewer.
7. The host sends full-frame JPEG messages and cursor position. The viewer sends input events; clipboard and audio messages are sent only when requested and approved.

## Transport and protocol

- TCP transport over the Tailscale network.
- TLS 1.2/1.3 via `javax.net.ssl`.
- Host-certificate pinning by SHA-256 fingerprint.
- A protocol magic value (`NXR3`) precedes session setup.
- Message identifiers are declared by `MSG_*` constants near the top of `NexxaRemote.java`.
- The current protocol is version-coupled: run matching versions on host and viewer.

The host rejects a second concurrent viewer in this release. The current design is not a general-purpose multi-tenant relay or public connection broker.

## Screen path

`Robot.createScreenCapture` captures the host display. `JpegEncoder` downscales only when the selected profile's maximum dimensions require it, then encodes a JPEG frame. A host frame thread sends the newest frames with a time-based target cadence and skips missed periods rather than queuing an ever-growing backlog. The viewer keeps only the newest encoded frame pending for decode and paints it into `ScreenPanel` with aspect-ratio-preserving Fit geometry.

The current transport sends full JPEG frames over TCP. There is no H.264 codec integration, dirty-rectangle protocol, or UDP video data channel in v0.7.5.

## Input path

The viewer captures mouse/keyboard events while the remote screen has focus and sends normalized screen coordinates or key/button events. The host uses `java.awt.Robot` to inject input. On Windows, the optional low-level hook helper tracks physical host input and ignores injected events; local physical input temporarily suppresses remote input. If that hook cannot start, Nexxa logs that automatic arbitration is degraded rather than silently blocking the viewer forever.

## Persistent data

The host identity is stored under `%APPDATA%\NexxaRemote` for the current Windows account. Do not copy this directory into the source repository or share it with another person. It contains private identity material used to keep the host certificate stable across launches.
