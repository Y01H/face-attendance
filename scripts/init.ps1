$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (Test-Path 'config/env.ps1') { Write-Host 'config/env.ps1 already exists; keeping it.'; exit 0 }
function New-Secret([int]$Length) {
    $bytes = New-Object byte[] $Length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}
$text = [IO.File]::ReadAllText((Join-Path (Get-Location) 'config/env.ps1.example'))
$text = $text.Replace('GENERATE_ADMIN_PASSWORD', (New-Secret 24)).Replace('GENERATE_FEATURE_KEY', (New-Secret 32)).Replace('GENERATE_LOCAL_PASSWORD', (New-Secret 24))
[IO.File]::WriteAllText((Join-Path (Get-Location) 'config/env.ps1'), $text, (New-Object Text.UTF8Encoding $true))
Write-Host 'Created config/env.ps1. Edit CLOUD_DB settings before starting.'
Write-Host 'Admin password and encryption key are in that file. Keep a secure backup.'
