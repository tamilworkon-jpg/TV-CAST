# Builds both APKs inside Docker and copies them to .\out
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

docker build -t tvcast-builder "$root\docker"
if ($LASTEXITCODE) { throw "docker image build failed" }

$script = @'
set -e
if [ ! -f keystore/debug.keystore ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore keystore/debug.keystore -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
gradle assembleRelease --no-daemon --console=plain
'@ -replace "`r", ""

docker run --rm -v "$root\android:/project" -v tvcast-gradle:/root/.gradle tvcast-builder bash -c $script
if ($LASTEXITCODE) { throw "gradle build failed" }

New-Item -ItemType Directory -Force "$root\out" | Out-Null
Copy-Item "$root\android\phone\build\outputs\apk\release\phone-release.apk" "$root\out\TvCast-phone.apk" -Force
Copy-Item "$root\android\tv\build\outputs\apk\release\tv-release.apk" "$root\out\TvCast-tv.apk" -Force
Get-ChildItem "$root\out\*.apk" | Select-Object Name, @{n='MB'; e={[math]::Round($_.Length / 1MB, 1)}}
