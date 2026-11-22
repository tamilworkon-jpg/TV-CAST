# Installs the TV app over Wi-Fi adb and starts it once (needed so it auto-starts on boot afterwards).
param([string]$TvIp = "192.168.0.104")
$ErrorActionPreference = 'Stop'
$adb = "$PSScriptRoot\tools\platform-tools\adb.exe"
$dev = "${TvIp}:5555"

& $adb connect $dev
& $adb -s $dev install -r "$PSScriptRoot\out\TvCast-tv.apk"
if ($LASTEXITCODE) { throw "install failed" }
& $adb -s $dev shell am start -n com.cleantv.tv/.MainActivity
