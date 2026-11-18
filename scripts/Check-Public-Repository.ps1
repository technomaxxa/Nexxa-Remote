$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$skip = @('.git', 'runtime', 'build', 'dist')
$files = Get-ChildItem -LiteralPath $root -Recurse -File | Where-Object {
    $relative = $_.FullName.Substring($root.Length).TrimStart('\', '/')
    $parts = $relative -split '[\\/]'
    -not ($parts | Where-Object { $_ -in $skip }) -and
    $_.Extension -in @('.java', '.md', '.txt', '.bat', '.ps1', '.yml', '.yaml', '.mf', '.svg')
}

$allowedSyntheticTailscaleAddresses = @('100.64.0.0', '100.64.0.1', '100.64.0.10', '100.64.0.11')
$patterns = [ordered]@{
    'RFC1918 IPv4 (review every match)' = '(?<![\d.])(?:10\.(?:\d{1,3}\.){2}\d{1,3}|192\.168\.(?:\d{1,3}\.){1}\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.(?:\d{1,3}\.)\d{1,3})(?![\d.])'
    'Tailscale CGNAT IPv4 (review every match)' = '(?<![\d.])100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.(?:\d{1,3}\.)\d{1,3}(?![\d.])'
    'User profile absolute path (review every match)' = '(?i)C:\\Users\\[^\\\s"'']+'
    'Invitation URI with a 32+ character hex key' = '(?i)nexxa://connect\?[^\s"'']*key=[a-f0-9]{32,}'
}

$findings = @()
foreach ($file in $files) {
    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadLines($file.FullName)) {
        $lineNumber++
        foreach ($entry in $patterns.GetEnumerator()) {
            foreach ($match in [regex]::Matches($line, $entry.Value)) {
                $value = $match.Value
                if ($entry.Key.StartsWith('Tailscale') -and $value -in $allowedSyntheticTailscaleAddresses) {
                    continue
                }
                $relative = $file.FullName.Substring($root.Length).TrimStart('\', '/')
                $findings += [pscustomobject]@{
                    File = $relative
                    Line = $lineNumber
                    Category = $entry.Key
                    Match = $value
                }
            }
        }
    }
}

if ($findings.Count -eq 0) {
    Write-Host 'No suspicious private-data patterns found. Synthetic test fixtures are allow-listed.' -ForegroundColor Green
    exit 0
}

Write-Host 'Potential private-data matches found. Review every match before publishing:' -ForegroundColor Yellow
$findings | Format-Table -AutoSize
exit 2
