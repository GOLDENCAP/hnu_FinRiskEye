# Build the debug APK in one step.
# Usage:  .\build-debug.ps1
# NOTE: this file is intentionally ASCII-only. Windows PowerShell 5.1 reads
#       .ps1 files as ANSI unless they carry a BOM, which corrupts non-ASCII text.

$ErrorActionPreference = "Stop"

$ProjectRoot = $PSScriptRoot
$DevEnv      = Join-Path (Split-Path $ProjectRoot -Parent) "dev-env"
$JdkHome     = Join-Path $DevEnv "jdk\jdk-21"
$SdkRoot     = Join-Path $DevEnv "android-sdk"

if (-not (Test-Path $JdkHome)) { throw "JDK not found: $JdkHome" }
if (-not (Test-Path $SdkRoot)) { throw "Android SDK not found: $SdkRoot" }

$env:JAVA_HOME        = $JdkHome
$env:ANDROID_HOME     = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:Path             = "$JdkHome\bin;$SdkRoot\platform-tools;$env:Path"

Write-Host "JAVA_HOME = $env:JAVA_HOME"
Write-Host "SDK       = $env:ANDROID_SDK_ROOT"
Write-Host "Running: gradlew assembleDebug ..." -ForegroundColor Cyan

Push-Location $ProjectRoot
try {
    & ".\gradlew.bat" assembleDebug --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Build failed, exit code $LASTEXITCODE" }
}
finally {
    Pop-Location
}

$apk = Get-ChildItem -Path (Join-Path $ProjectRoot "app\build\outputs\apk") -Recurse -Filter *.apk -ErrorAction SilentlyContinue |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($apk) {
    Write-Host ""
    Write-Host "BUILD SUCCEEDED: $($apk.FullName)" -ForegroundColor Green
    Write-Host "Install to a phone:"
    Write-Host "  & `"$SdkRoot\platform-tools\adb.exe`" install -r `"$($apk.FullName)`""
}
else {
    Write-Host "Build finished but no APK was found. Check the log above." -ForegroundColor Yellow
}
