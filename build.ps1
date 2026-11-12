param(
    [ValidateSet("debug", "release")]
    [string]$Mode = "debug"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $env:JAVA_HOME) {
    $candidate = "C:\Program Files\Java\jdk-18.0.2.1"
    if (Test-Path $candidate) {
        $env:JAVA_HOME = $candidate
    } else {
        throw "JAVA_HOME is not set. Install JDK 17+ and set JAVA_HOME."
    }
}

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    if (Test-Path (Join-Path $root "local.properties")) {
        Write-Host "Using SDK from local.properties."
    } else {
        throw "ANDROID_HOME is not set and local.properties is missing."
    }
}

Push-Location $root
try {
    & .\gradlew.bat "assemble$($Mode)" --no-daemon
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    $apk = Join-Path $root "app\build\outputs\apk\$Mode\app-$Mode.apk"
    Write-Host "APK: $apk"
} finally {
    Pop-Location
}
