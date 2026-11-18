# Privacy notes

## What Nexxa does

Nexxa v0.7.5 does not include a Nexxa-operated cloud account service, analytics endpoint, or public screen relay. The host and viewer exchange session data over the Tailscale network using TLS.

The application reads local system information required to operate, including:

- The Tailscale status and peer list exposed by the local Tailscale CLI.
- The host's Tailscale IPv4 address.
- Local display dimensions and screen content for an active sharing session.
- Local keyboard/mouse events when remote control is enabled.
- Clipboard text when clipboard synchronization is enabled.
- Audio from a supported recording endpoint when desktop-audio sharing is enabled.

Nexxa's on-screen Activity Console displays local operational messages. This codebase does not send those application logs to a Nexxa-operated server.

## What Tailscale does

Tailscale is a separate third-party network service. Its client may communicate with Tailscale coordination and relay infrastructure and may process account, device, IP, and connection metadata under its own policies. Nexxa does not control Tailscale's telemetry or retention behavior. Review <https://tailscale.com/privacy-policy/> and Tailscale's documentation before installing it.

## Local identity storage

The host's persistent certificate and DPAPI-protected password are stored under:

```text
%APPDATA%\NexxaRemote\
```

This folder is user-specific and must not be uploaded to GitHub or sent to another person.

## Invitation data

An invitation link includes the Tailscale host address, TCP port, SHA-256 certificate fingerprint, and temporary session access key. Anyone who obtains the link may attempt to connect until the host stops sharing. Share it privately and revoke it by stopping the session.

## Optional data channels

- Clipboard: plain text only, opt-in.
- Audio: opt-in, experimental, best-effort capture from a supported recording endpoint.
- Remote input: host-controlled and only available when enabled.
