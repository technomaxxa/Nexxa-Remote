# Security model and safe use

Nexxa is a beta remote desktop application. Remote desktop software can expose the entire interactive desktop of a computer. Do not use it with untrusted viewers or on systems where a mistake could cause harm.

## Current protections

- TLS 1.2/1.3 protects the Nexxa session transport.
- The viewer pins the host's SHA-256 certificate fingerprint from the invitation.
- The invitation contains a cryptographically random session access key.
- The host checks the supplied key using a constant-time comparison.
- The host must explicitly enable remote keyboard/mouse permission.
- Clipboard and audio are separately opt-in.
- The host can stop sharing to close the session and revoke the active invitation.
- The host identity private key remains on the host machine; its password is protected using Windows DPAPI for the current Windows user.

## Invitation handling

A `nexxa://` invitation contains connection coordinates, a certificate fingerprint, and a session access key. Treat the entire link like a password:

- Send it only to the intended viewer over a trusted channel.
- Do not put a real invitation in a public README, issue, screenshot, test fixture, or source-code comment.
- Stop sharing when the session is complete.
- Generate a fresh invitation for a later session.

## Administrator mode

Administrator mode uses the normal Windows UAC consent mechanism. It restarts Nexxa elevated only after local approval. An elevated host gives a trusted remote controller access to applications that the elevated process can control. Do not enable it unless you trust the viewer.

Nexxa does not bypass Windows secure-desktop UAC prompts, credential prompts, or other Windows security boundaries. The viewer cannot approve those prompts remotely through Nexxa.

## Host input priority

Windows low-level keyboard/mouse hooks are used to identify physical host input and avoid having the host and remote viewer fight over the same cursor. If these hooks fail, the app logs degraded arbitration. Do not assume automatic host priority is operating unless the host Activity Console reports that the hooks are ready.

## Network exposure

Use Nexxa over Tailscale. Do not expose the host port directly to the public Internet. Do not add public port forwarding. Keep Tailscale device approvals and account security enabled.

## Known security limitations

- This source has not received an independent security audit or formal penetration test.
- The current protocol is custom and should not be considered a substitute for a mature, independently reviewed remote desktop protocol.
- TLS certificate pinning and the session key do not eliminate endpoint compromise, malware, malicious viewers, or insecure user practices.
- The app supports one active viewer, but the invitation should still be treated as a bearer secret.
- Java AWT `Robot` and Windows input APIs operate within the permissions and desktop/session boundaries imposed by Windows.

## Reporting a vulnerability

Do not publish exploitable details, real invitation links, private IP addresses, certificates, or key material in a public issue. Use GitHub's private vulnerability reporting for this repository if it is enabled. Otherwise contact the maintainer privately through the GitHub profile and provide a minimal reproduction without secrets.
