# Third-party notices

## Java platform

Nexxa's Java source uses standard Java platform APIs and does not require third-party Java libraries.

## Eclipse Temurin runtime

On first launch, the Windows bootstrapper downloads Eclipse Temurin JRE 25.0.5+8 (Windows x64) from the official Adoptium release and verifies the pinned SHA-256 before extraction. The runtime is not included in this Git repository. Temurin runtime files are distributed by Adoptium under their applicable OpenJDK licensing and notices, including GPLv2 with the Classpath Exception. Review the license and notices supplied by the runtime distribution and the official [Adoptium project](https://adoptium.net/).

## Oracle fallback runtime

If Temurin cannot be installed, the bootstrapper attempts to download the official Oracle JDK 25 Windows x64 archive and verifies it against the SHA-256 checksum published by Oracle. Oracle JDK is not included in this repository. Its use and redistribution are subject to Oracle's terms presented for that JDK release. Review Oracle's current [Java downloads and license information](https://www.oracle.com/java/technologies/downloads/) before redistributing the fallback runtime.

## Tailscale

Tailscale is a separate application installed and managed by the user. It is not bundled with Nexxa. Tailscale's software and services are governed by Tailscale's own terms and privacy policy: <https://tailscale.com/>
