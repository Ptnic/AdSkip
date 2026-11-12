$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Program Files\Java\jdk-18.0.2.1" }
$javac = Join-Path $javaHome "bin\javac.exe"
$java = Join-Path $javaHome "bin\java.exe"
$out = Join-Path $root ".toolchain\selftest-classes"
New-Item -ItemType Directory -Force -Path $out | Out-Null

& $javac -encoding UTF-8 -d $out `
    (Join-Path $root "app\src\main\java\com\adpuls\dnsfilter\DnsCodec.java") `
    (Join-Path $root "tools\DnsCodecSelfTest.java")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& $java -cp $out com.adship.adskip.DnsCodecSelfTest
exit $LASTEXITCODE
