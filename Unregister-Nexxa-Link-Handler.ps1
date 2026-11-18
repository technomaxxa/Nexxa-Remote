$ErrorActionPreference = 'Stop'
$schemeKey = 'HKCU:\Software\Classes\nexxa'
if (Test-Path $schemeKey) {
    Remove-Item -Path $schemeKey -Recurse -Force
    Write-Host 'Removed the nexxa:// link association for this Windows user.' -ForegroundColor Green
} else {
    Write-Host 'No Nexxa link association was registered.'
}
