# Networking and Tailscale setup

## Why Tailscale is required

Nexxa v0.7.5 does not include its own public rendezvous or relay service. It binds its host listener to the host's Tailscale IPv4 address. Tailscale supplies the private network path between the computers, including NAT traversal and its own encrypted relay fallback when a direct peer path is unavailable.

This design normally avoids:

- Publicly exposing Nexxa's listener.
- Router port-forwarding.
- Renting a Nexxa relay server.

It still requires the Tailscale client to be installed and authenticated on each computer.

## Same tailnet

1. Install Tailscale on both PCs.
2. Sign in to the same tailnet.
3. Confirm both devices show connected in Tailscale.
4. Start Nexxa on the host and start sharing.
5. Send the host-generated invitation to the viewer.

## Different Tailscale accounts

The host must share its device with the viewer account using Tailscale's device-sharing feature. The viewer must accept the share. Follow Tailscale's current documentation: <https://tailscale.com/kb/1084/sharing/>.

Nexxa cannot approve or administer a Tailscale device share on the user's behalf.

## Host address and port

The host page displays the Tailscale IPv4 address and the selected TCP port (default `24800`). The host binds to the Tailscale interface. The viewer connects using the `nexxa://` invitation rather than manually guessing an address.

Do not replace the host address with a public IP address. Do not configure public router port-forwarding for Nexxa v0.7.5.

## Windows Firewall

The host settings include an optional firewall helper. It requests administrator approval and creates a rule for Nexxa's TCP port from the Tailscale IPv4 range (`100.64.0.0/10`). Only enable this when needed. Review existing Windows Firewall policies before making changes.

## Tailscale lifecycle

Nexxa can manage the Tailscale Windows service according to its settings:

- The user can choose to change the Tailscale service startup type to Manual, with a Windows administrator prompt.
- Nexxa starts Tailscale when needed if it is not already running.
- The app tracks whether it started the Tailscale service/connection.
- On exit, it only stops resources it owns when the stop-on-exit option is enabled. A Tailscale connection that was already active before Nexxa opened is left unchanged.

Tailscale is an independent application and service. Its own control-plane and relay behavior is subject to Tailscale's product terms and privacy policy.
