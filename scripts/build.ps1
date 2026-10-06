<#
.SYNOPSIS
    Builds WorkerDocMaker into build\libs\WorkerDocMaker.jar.
.DESCRIPTION
    Gradle needs a JDK. Uses JAVA_HOME when it is set, otherwise the newest JDK in ~\.jdks
    (where IntelliJ downloads JDKs).
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\build.ps1
#>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot

if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "$HOME\.jdks" -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if (-not $jdk) {
        throw "No JDK found. Install JDK 21 or newer, or set JAVA_HOME."
    }
    $env:JAVA_HOME = $jdk.FullName
}
Write-Host "Using JDK $env:JAVA_HOME"

& .\gradlew.bat clean jar --quiet
if ($LASTEXITCODE -ne 0) {
    throw "Build failed (exit code $LASTEXITCODE)."
}
Write-Host "Built $projectRoot\build\libs\WorkerDocMaker.jar"
