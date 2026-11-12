param([string]$JdkPath = 'D:\java')
$ErrorActionPreference = 'Stop'
$out = Join-Path $PSScriptRoot 'test-build'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$sourceRoot = Join-Path $PSScriptRoot 'src/cn/local/axisview'
$sources = @((Join-Path $sourceRoot 'AngleMath.java'), (Join-Path $sourceRoot 'OrientationTracker.java'))
$tests = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests') -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkPath 'bin/javac.exe') --release 8 -encoding UTF-8 -d $out @sources @tests
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
foreach ($suite in @('AngleMathTest', 'OrientationTrackerTest')) {
    & (Join-Path $JdkPath 'bin/java.exe') -cp $out "cn.local.axisview.$suite"
    if ($LASTEXITCODE -ne 0) { throw "Test suite failed: $suite" }
}
