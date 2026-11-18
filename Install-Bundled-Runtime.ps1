$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = $PSScriptRoot
$runtime = Join-Path $root 'runtime'
$java = Join-Path $runtime 'bin\java.exe'
$javaw = Join-Path $runtime 'bin\javaw.exe'

function Get-JavaVersionText {
    param([Parameter(Mandatory = $true)][string]$JavaPath)

    if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) {
        throw "Java executable not found: $JavaPath"
    }

    # java -version writes to STDERR on purpose. Do not invoke it as a native
    # PowerShell command: Windows PowerShell 5.1 can promote that STDERR text
    # to a terminating NativeCommandError when ErrorActionPreference is Stop.
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $JavaPath
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        $combined = ($stdout + "`n" + $stderr).Trim()
        if ($process.ExitCode -ne 0) {
            throw "java -version returned exit code $($process.ExitCode): $combined"
        }
        if ($combined -notmatch '(?im)\b(?:openjdk|java)\s+(?:version\s+)?["'']?25(?:\.|\b)') {
            throw "The runtime is not Java 25. Detected: $combined"
        }
        return $combined
    }
    finally {
        $process.Dispose()
    }
}

function Install-VerifiedRuntime {
    param(
        [Parameter(Mandatory = $true)][string]$SourceName,
        [Parameter(Mandatory = $true)][string]$ArchiveUrl,
        [string]$ExpectedSha256,
        [string]$ChecksumUrl,
        [Parameter(Mandatory = $true)][string]$ArchiveName
    )

    $temp = Join-Path $env:TEMP ('NexxaJava25-' + [guid]::NewGuid().ToString('N'))
    $zip = Join-Path $temp $ArchiveName
    $extract = Join-Path $temp 'extract'
    New-Item -ItemType Directory -Path $temp -Force | Out-Null

    try {
        Write-Host "Downloading $SourceName..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $ArchiveUrl -OutFile $zip -UseBasicParsing -TimeoutSec 1800

        if ($ChecksumUrl) {
            Write-Host "Retrieving the official $SourceName SHA-256 checksum..." -ForegroundColor Cyan
            $checksumText = (Invoke-WebRequest -Uri $ChecksumUrl -UseBasicParsing -TimeoutSec 120).Content
            if ([string]$checksumText -notmatch '(?i)\b([a-f0-9]{64})\b') {
                throw "Could not parse the official SHA-256 checksum from $ChecksumUrl"
            }
            $ExpectedSha256 = $matches[1].ToUpperInvariant()
        }

        if ([string]::IsNullOrWhiteSpace($ExpectedSha256)) {
            throw "No trusted SHA-256 checksum was available for $SourceName. The archive will not be installed."
        }

        $actual = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToUpperInvariant()
        if ($actual -ne $ExpectedSha256.ToUpperInvariant()) {
            throw "$SourceName SHA-256 mismatch. Expected $ExpectedSha256 but received $actual. The archive was rejected."
        }
        Write-Host "$SourceName archive checksum verified." -ForegroundColor Green

        Expand-Archive -LiteralPath $zip -DestinationPath $extract -Force
        $javawFound = Get-ChildItem -LiteralPath $extract -Filter 'javaw.exe' -File -Recurse |
            Where-Object { $_.FullName -match '\\bin\\javaw\.exe$' } |
            Select-Object -First 1
        if (-not $javawFound) {
            throw "$SourceName archive did not contain bin\javaw.exe."
        }

        $runtimeRoot = Split-Path (Split-Path $javawFound.FullName -Parent) -Parent
        if (Test-Path -LiteralPath $runtime) {
            Remove-Item -LiteralPath $runtime -Recurse -Force
        }
        Move-Item -LiteralPath $runtimeRoot -Destination $runtime

        if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $javaw)) {
            throw "$SourceName extraction completed but java.exe/javaw.exe is missing."
        }

        $versionText = Get-JavaVersionText -JavaPath $java
        Write-Host "Verified private Java 25 runtime: $versionText" -ForegroundColor Green
        Write-Host 'No system-wide Java installation or PATH change was made.' -ForegroundColor Green
        return $true
    }
    catch {
        if (Test-Path -LiteralPath $runtime) {
            Remove-Item -LiteralPath $runtime -Recurse -Force -ErrorAction SilentlyContinue
        }
        throw
    }
    finally {
        if (Test-Path -LiteralPath $temp) {
            Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

# Reuse a valid private Java 25 runtime without downloading anything.
if ((Test-Path -LiteralPath $java) -and (Test-Path -LiteralPath $javaw)) {
    try {
        $existingVersion = Get-JavaVersionText -JavaPath $java
        Write-Host "Verified private Java 25 runtime is already available: $existingVersion" -ForegroundColor Green
        exit 0
    }
    catch {
        Write-Warning "The existing private runtime is incomplete or invalid: $($_.Exception.Message)"
        Remove-Item -LiteralPath $runtime -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# Windows PowerShell 5.1 may otherwise negotiate an older TLS default.
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }

# First choice: lightweight Eclipse Temurin JRE 25.0.5+8 (about 56 MB).
$temurinUrl = 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jre_x64_windows_hotspot_25.0.5_8.zip'
$temurinSha256 = 'DA78D54A2FCDBBA1E92D5E3F07DD30236F96297891C4A21A781C3F38F5C0BEAE'

try {
    Install-VerifiedRuntime `
        -SourceName 'Eclipse Temurin JRE 25.0.5+8' `
        -ArchiveUrl $temurinUrl `
        -ExpectedSha256 $temurinSha256 `
        -ArchiveName 'temurin25-jre.zip' | Out-Null
    exit 0
}
catch {
    Write-Warning "Temurin setup failed: $($_.Exception.Message)"
    Write-Host 'Trying the official Oracle Java 25 Windows x64 archive as a fallback...' -ForegroundColor Yellow
}

# Fallback: official Oracle JDK 25 Windows x64 ZIP (about 206 MB).
# Oracle publishes the matching SHA-256 file beside the ZIP over HTTPS.
$oracleUrl = 'https://download.oracle.com/java/25/latest/jdk-25_windows-x64_bin.zip'
$oracleChecksumUrl = 'https://download.oracle.com/java/25/latest/jdk-25_windows-x64_bin.zip.sha256'

try {
    Install-VerifiedRuntime `
        -SourceName 'Oracle JDK 25 Windows x64' `
        -ArchiveUrl $oracleUrl `
        -ChecksumUrl $oracleChecksumUrl `
        -ArchiveName 'oracle-jdk25-windows-x64.zip' | Out-Null
    Write-Host 'Oracle Java 25 fallback installed successfully.' -ForegroundColor Green
    exit 0
}
catch {
    $oracleError = $_.Exception.Message
    Write-Host ("Both Java runtime providers failed. Temurin could not be installed and Oracle fallback also failed: " + $oracleError) -ForegroundColor Red
    exit 1
}
