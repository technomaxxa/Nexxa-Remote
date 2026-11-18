$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$installer = Join-Path $root 'Install-Bundled-Runtime.ps1'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $installer
if ($LASTEXITCODE -ne 0) { throw 'Runtime download/verification failed.' }
if (-not (Test-Path (Join-Path $root 'runtime\bin\javaw.exe'))) { throw 'Bundled runtime is missing.' }
$parent = Split-Path $root -Parent
$zip = Join-Path $parent 'NexxaRemote-v0.7.5-Windows-FULL.zip'
if (Test-Path $zip) { Remove-Item $zip -Force }
$items = Get-ChildItem -LiteralPath $root -Force | Where-Object { $_.Name -notin @('build', '.git') }
Compress-Archive -Path $items.FullName -DestinationPath $zip -CompressionLevel Optimal
Write-Host "Created offline full bundle: $zip" -ForegroundColor Green
Write-Host 'This archive includes Nexxa and the private Java 25 runtime. It does not need a system Java installation.'
