# Show this app's runtime logs from a connected phone/tablet.
#
# Usage:
#   .\logcat.ps1                  live view, only this project's own log tags
#   .\logcat.ps1 -All             live view, everything from the app process
#   .\logcat.ps1 -Dump            print the current buffer, then exit
#   .\logcat.ps1 -Dump -Save      also write the dump to app-log.txt
#   .\logcat.ps1 -Serial <id>     pick a specific device (see: adb devices)
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 as ANSI
# unless it has a BOM, which corrupts non-ASCII text.

param(
    [switch]$All,
    [switch]$Dump,
    [switch]$Save,
    [string]$Serial = ''
)

$ErrorActionPreference = 'Stop'

$ProjectRoot = $PSScriptRoot
$DevEnv      = Join-Path (Split-Path $ProjectRoot -Parent) 'dev-env'
$Adb         = Join-Path $DevEnv 'android-sdk\platform-tools\adb.exe'
$Package     = 'com.example.chapter07'
$SavePath    = Join-Path $ProjectRoot 'app-log.txt'

if (-not (Test-Path $Adb)) { throw "adb not found: $Adb" }

$serialArgs = @()
if ($Serial) { $serialArgs = @('-s', $Serial) }

$deviceList = & $Adb devices |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -match '\s(device|unauthorized|offline)$' }

if (-not $deviceList) {
    throw 'No device found. Connect a phone/tablet with USB debugging on.'
}
Write-Host 'Devices:' -ForegroundColor Cyan
$deviceList | ForEach-Object { Write-Host "  $_" }

if (-not ($deviceList | Where-Object { $_ -match '\sdevice$' })) {
    throw 'Device is connected but not authorized. Accept the USB debugging prompt on the device.'
}

# Log tags used by this project (keep in sync when new classes are added).
$tagList = @(
    'SignalClient',
    'CallActivity',
    'FraudDetectionAnalyzer',
    'FraudAudioTranscription',
    'FraudVideoAnalyzer',
    'VideoRecorder',
    'RemoteVideoRecorder',
    'LocalAudioRecorder',
    'RemoteAudioRecorder',
    'WebRtcAudioTrackReflector',
    'WebRtcAudioRecordSamplesBridge',
    'AndroidRuntime'
)

$appPid = ''
try { $appPid = ((& $Adb @serialArgs shell pidof $Package) -join '').Trim() } catch { }

Write-Host ''
if ($appPid) {
    Write-Host "App process pid = $appPid" -ForegroundColor Cyan
} else {
    Write-Host "App is not running yet (start it on the device first)." -ForegroundColor Yellow
}

$logArgs = @('logcat')
if ($Dump) { $logArgs += '-d' }
$logArgs += @('-v', 'time')

if ($All) {
    if (-not $appPid) { throw 'App not running, cannot filter by pid. Start the app first.' }
    $logArgs += "--pid=$appPid"
    Write-Host 'Mode: ALL logs from the app process (may be very noisy).' -ForegroundColor Cyan
} else {
    $logArgs += '-s'
    foreach ($t in $tagList) { $logArgs += "$t`:V" }
    $logArgs += '*:S'
    Write-Host 'Mode: filtered to this project''s own log tags.' -ForegroundColor Cyan
}

Write-Host 'Press Ctrl+C to stop the live view.' -ForegroundColor DarkGray
Write-Host ''

if ($Dump -and $Save) {
    & $Adb @serialArgs @logArgs 2>&1 | Tee-Object -FilePath $SavePath
    Write-Host ''
    Write-Host "Saved to $SavePath" -ForegroundColor Green
} elseif ($Dump) {
    & $Adb @serialArgs @logArgs 2>&1
} else {
    & $Adb @serialArgs @logArgs 2>&1
}
