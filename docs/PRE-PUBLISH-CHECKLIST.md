# Public repository checklist

Run through this checklist before pushing a commit or creating a release.

- [ ] Review `git status` and inspect every staged file.
- [ ] Run `scripts/Check-Public-Repository.ps1` on Windows; review all matches rather than assuming every IP literal is private. Synthetic test addresses and the Tailscale CGNAT range may be intentional.
- [ ] Search for invitation links beginning `nexxa://`, passwords, access keys, certificate/private-key files, DPAPI files, and `%APPDATA%\NexxaRemote` contents.
- [ ] Confirm no `runtime/`, build output, user logs, screenshots, local configuration, or downloaded runtime archives are staged.
- [ ] Confirm the test fixture uses synthetic names and addresses, not actual Tailscale/LAN devices.
- [ ] Compile with `Build Nexxa Remote.bat` and run the built-in self-test.
- [ ] Review `README.md`, `SECURITY.md`, `THIRD-PARTY-NOTICES.md`, and `LICENSE`.
- [ ] Confirm runtime-provider URLs and license references are still correct before publishing an updated runtime bootstrapper.
- [ ] Do not publish a real invitation URL in the README or issue templates.
