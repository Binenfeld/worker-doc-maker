<#
.SYNOPSIS
    Runs WorkerDocMaker: reads the newest .csv in the input folder, writes the report next to it
    and sends it on WhatsApp (when whatsapp.properties exists in the project folder).
.PARAMETER InputFolder
    Folder holding the exported attendance CSVs. Default: C:\workerdocmaker
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\run.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\run.ps1 -InputFolder D:\reports
#>
param(
    [string]$InputFolder = 'C:\workerdocmaker'
)
$ErrorActionPreference = 'Stop'

# The program reads whatsapp.properties from the working directory, so run it from the project folder.
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot

$jar = Join-Path $projectRoot 'build\libs\WorkerDocMaker.jar'
if (-not (Test-Path $jar)) {
    throw "$jar not found. Run scripts\build.ps1 first."
}

# Java: JAVA_HOME, else the newest JDK in ~\.jdks (IntelliJ's download folder), else java on the PATH.
if ($env:JAVA_HOME) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $jdk = Get-ChildItem "$HOME\.jdks" -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    $java = if ($jdk) { Join-Path $jdk.FullName 'bin\java.exe' } else { 'java' }
}

# Show the program's Hebrew output correctly in this console.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

& $java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -jar $jar $InputFolder
exit $LASTEXITCODE
