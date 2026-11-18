Nexxa Remote v0.7.5 — higher-detail video streaming
- Raise High quality from 1600×900 / JPEG 0.59 / 15 FPS to up to 1920×1080 / JPEG 0.86 / 12 FPS.
- Add Ultra detail: up to 2560×1440 / JPEG 0.92 / 8 FPS for high-bandwidth connections and high-resolution host displays.
- Improve Balanced to up to 1600×900 / JPEG 0.66 / 18 FPS.
- Improve Low latency JPEG quality from 0.40 to 0.48 while retaining its 1024×576 / 20 FPS target.
- Replace nearest-neighbour downscaling with bicubic resampling to preserve fine UI text and diagonal edges when a host display exceeds the selected profile's maximum resolution.
- Increase the maximum accepted frame size from 4 MiB to 16 MiB so the viewer accepts larger high-detail frames.
- Add source-resolution and selected-preset diagnostics to the viewer metrics and host Activity Console.
- Add self-tests for native Full HD/1440p profile bounds and measured JPEG fidelity improvement over the v0.7.4 0.59 quality baseline.

Trade-offs
- Higher JPEG quality and larger frames consume more network bandwidth and CPU time. Actual FPS is measured and may fall below the profile target on a slow host or network.
- Video remains full-frame JPEG over TLS/TCP. Hardware H.264, dirty-region updates, and UDP transport are not included in this release.
- Both host and viewer should use v0.7.5 because the viewer's accepted frame-size limit has changed.

Nexxa Remote v0.7.4
- When a viewer authenticates, expand the Connect page to fill the application content area by hiding the branding header and Share/Connect tab strip.
- Keep the compact in-session toolbar, disconnect action, status and full-screen control visible.
- Restore the normal header, tabs and Connect setup panel after disconnect.
- Preserve aspect-ratio FIT rendering; the entire remote screen remains visible without crop or distortion.
- Added a self-test for FIT geometry and full-frame bounds.

Nexxa Remote v0.7.3
- Move Windows/Tailscale actions to a dedicated host-only tab with responsive setting tiles.
- Keep the host activity console below host tabs; viewer remains console-free.
- Clarify UAC restart handoff and require a fresh invitation after elevation.

Nexxa Remote v0.7.2
- Preserve existing host-approved remote-control permission across explicit UAC restart only.
- Rebuild Windows & Tailscale controls into responsive settings tiles.
- Use a custom Nexxa dialog for disabling Tailscale Windows auto-start.
- Add self-test coverage for permission restoration argument.

NEXXA REMOTE 0.7.1
==================

- Fixed the administrator confirmation window: the old native JOptionPane could grow to an unusably wide shape and clip its actions on some Windows display configurations.
- Replaced it with a fixed-size Nexxa-themed dialog, wrapped text, explicit high-contrast button colors, and a dedicated action row.
- The default action is Cancel; administrator elevation still requires the host to click the clearly labeled Enable administrator access button and approve Windows UAC.
- No remote-control permissions or elevation semantics changed.

Validation
----------
- Java source compiled with javac --release 21.
- Built-in local self-test passed.
- The Swing dialog requires confirmation on a physical Windows display at the user's scaling setting.

NEXXA REMOTE 0.7.0
==================

- Added host-only "Enable administrator access (UAC restart)" action.
- Uses the standard Windows UAC elevation flow; no UAC bypass or silent elevation.
- Detects the actual elevated process token and shows an explicit Administrator mode active status.
- Preserves Nexxa-owned Tailscale service/connection lifecycle across the restart.
- Stops the old host/viewer session on restart; a fresh invitation is required.
- Keeps the existing host-controlled remote keyboard/mouse permission separate from administrator mode.
- Documents that secure-desktop UAC and credential prompts require local approval.

Validation
----------
- Java source compiled with javac --release 21.
- Built-in local self-test passed.
- Windows UAC/elevated desktop behavior requires validation on a real Windows 10/11 machine.
