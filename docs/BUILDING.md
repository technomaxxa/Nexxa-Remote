# Building Nexxa Remote on Windows

## Prerequisites

- Windows 10/11 x64.
- JDK 21 or newer with `javac.exe` and `jar.exe` on `PATH`.
- PowerShell (Windows PowerShell 5.1 or newer).
- Git is optional for local building but required for cloning/contributing.

The application is compiled with Java 21 bytecode compatibility (`--release 21`). The runtime launcher provisions a private Java 25 runtime for normal use. The private runtime is not needed to compile if a JDK is installed.

## 1. Get the source

```powershell
git clone git@github.com:technomaxxa/Nexxa-Remote.git
cd Nexxa-Remote
```

Or download the source ZIP from GitHub and extract it.

## 2. Verify the JDK

```powershell
java -version
javac -version
jar --version
```

`javac` must be available. A JRE alone cannot compile the source.

## 3. Build the JAR

Double-click `Build Nexxa Remote.bat`, or run these commands from the repository root:

```powershell
if (Test-Path build) { Remove-Item build -Recurse -Force }
New-Item -ItemType Directory build | Out-Null
javac --release 21 -encoding UTF-8 -Xlint:all -d build NexxaRemote.java
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
jar --create --file NexxaRemote.jar --manifest manifest.mf -C build .
if ($LASTEXITCODE -ne 0) { throw 'jar packaging failed' }
```

The output is `NexxaRemote.jar` in the repository root.

## 4. Run the self-test

```powershell
java -Djava.awt.headless=true -jar NexxaRemote.jar --self-test
```

Or double-click `Run Self-Test.bat` after the private runtime has been provisioned. The self-test exercises certificate creation/pinning, invitation parsing, stream encoding, viewer fit geometry, Tailscale peer parsing, and input-priority decision logic. It does not replace testing the Windows hooks, UAC flow, screen capture, audio devices, or two-PC networking on real Windows machines.

## 5. Run the application

For development, if Java 25 is available:

```powershell
java -jar NexxaRemote.jar
```

For normal users, use `Start Nexxa Remote.bat`. It provisions and uses the private runtime in `runtime\` without changing the Windows PATH.

## 6. Create an offline package

After first-run runtime provisioning has completed, double-click `Build Offline Full Bundle.bat`. The generated ZIP includes the current application files and the private runtime. The `runtime/` directory is intentionally ignored by Git; do not commit it to the source repository.

## Troubleshooting

- **`javac` not found:** Install a JDK, not only a JRE, and reopen the terminal.
- **Runtime download fails:** Check connectivity to the official Temurin/Oracle download hosts, then run `Install-Bundled-Runtime.ps1` again.
- **Self-test fails:** Save the full console output and include the JDK version and Windows version in a bug report. Remove private IPs, invitations, usernames, and local paths before posting.
- **JAR manifest/version mismatch:** Rebuild using the included `manifest.mf` and `Build Nexxa Remote.bat`.
