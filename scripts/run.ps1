<#
.SYNOPSIS
    Runs WorkerDocMaker: reads the newest .csv in the input folder, writes the report (.docx and .pdf) next to it
    and sends the PDF on WhatsApp.
.DESCRIPTION
    The WhatsApp settings (whatsapp.properties) are looked up in the input folder first, then in the project
    folder. Without them the run stops with an explanation, unless -NoWhatsApp is given.
.PARAMETER InputFolder
    Folder holding the exported attendance CSVs. Default: C:\workerdocmaker
.PARAMETER NoWhatsApp
    Make the report and the PDF but don't send anything (for testing).
.PARAMETER Debug
    Also print every worker read, and the full stack trace of any error.
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\run.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\run.ps1 -InputFolder D:\reports -NoWhatsApp
#>
param(
    [string]$InputFolder = 'C:\workerdocmaker',
    [switch]$NoWhatsApp,
    [switch]$Debug
)
$ErrorActionPreference = 'Stop'

# A relative folder means relative to where the script was started, not to the project folder used below.
$InputFolder = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($InputFolder)

# The program also looks for whatsapp.properties in the working directory, so run it from the project folder.
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot

$jar = Join-Path $projectRoot 'build\libs\WorkerDocMaker.jar'
if (-not (Test-Path $jar)) {
    Write-Host "ERROR: $jar not found. Build it first: powershell -ExecutionPolicy Bypass -File scripts\build.ps1" -ForegroundColor Red
    exit 1
}

# Java: JAVA_HOME, else the newest JDK in ~\.jdks (IntelliJ's download folder), else java on the PATH.
if ($env:JAVA_HOME) {
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path $java)) {
        Write-Host "ERROR: JAVA_HOME is set to $env:JAVA_HOME, but there is no bin\java.exe there. Fix or remove JAVA_HOME." -ForegroundColor Red
        exit 1
    }
} else {
    $jdk = Get-ChildItem "$HOME\.jdks" -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($jdk) {
        $java = Join-Path $jdk.FullName 'bin\java.exe'
    } elseif (Get-Command java -ErrorAction SilentlyContinue) {
        $java = 'java'
    } else {
        Write-Host "ERROR: Java not found. Install JDK 21 or newer, or set JAVA_HOME." -ForegroundColor Red
        exit 1
    }
}

# Show the program's Hebrew output correctly in this console.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$programArgs = @($InputFolder)
if ($NoWhatsApp) { $programArgs += '--no-whatsapp' }
if ($Debug) { $programArgs += '--debug' }

# The program prints its own error messages; don't let PowerShell turn its error output into extra noise.
$ErrorActionPreference = 'Continue'
& $java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -jar $jar @programArgs
exit $LASTEXITCODE
