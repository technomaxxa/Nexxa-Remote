$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jar = Join-Path $root 'NexxaRemote.jar'
$javaw = Join-Path $root 'runtime\bin\javaw.exe'
if (-not (Test-Path $jar)) { throw "NexxaRemote.jar was not found beside this script." }
if (-not (Test-Path $javaw)) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'Install-Bundled-Runtime.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Could not provision Nexxa private Java runtime.' }
}
if (-not (Test-Path $javaw)) { throw 'Nexxa private javaw.exe was not found after runtime setup.' }
$schemeKey = 'HKCU:\Software\Classes\nexxa'
$commandKey = Join-Path $schemeKey 'shell\open\command'
New-Item -Path $schemeKey -Force | Out-Null
Set-Item -Path $schemeKey -Value 'URL:Nexxa Remote invitation protocol'
New-ItemProperty -Path $schemeKey -Name 'URL Protocol' -Value '' -PropertyType String -Force | Out-Null
New-Item -Path $commandKey -Force | Out-Null
$command = '"{0}" -Dfile.encoding=UTF-8 -jar "{1}" "%1"' -f $javaw, $jar
Set-Item -Path $commandKey -Value $command
Write-Host 'Registered nexxa:// invitation links using Nexxa private Java runtime.' -ForegroundColor Green
Write-Host "Command: $command"
