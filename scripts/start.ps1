$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not (Test-Path 'config/env.ps1')) { & (Join-Path $PSScriptRoot 'init.ps1'); exit 1 }
. (Join-Path (Get-Location) 'config/env.ps1')
if (-not (Test-Path 'face-attendance.jar')) { throw 'Missing face-attendance.jar. Build with mvn clean package and copy target/face-attendance.jar here.' }
$java = 'java'
if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin/java.exe' }
& $java '-Xms256m' '-Xmx1024m' '-jar' 'face-attendance.jar' @args
exit $LASTEXITCODE
