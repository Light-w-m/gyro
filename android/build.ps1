param(
    [Parameter(Mandatory=$true)][string]$JdkPath,
    [Parameter(Mandatory=$true)][string]$BuildToolsPath,
    [Parameter(Mandatory=$true)][string]$AndroidJar,
    [Parameter(Mandatory=$true)][string]$KeyStore,
    [Parameter(Mandatory=$true)][string]$KeyAlias,
    [Parameter(Mandatory=$true)][string]$OutputApk
)
$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
[xml]$manifest = Get-Content -LiteralPath (Join-Path $projectDir 'AndroidManifest.xml') -Raw -Encoding UTF8
$version = $manifest.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
$expectedName = "三轴角度-v$version.apk"
$OutputApk = [System.IO.Path]::GetFullPath($OutputApk)
if ([System.IO.Path]::GetFileName($OutputApk) -cne $expectedName) {
    throw "APK filename must be $expectedName"
}
$buildDir = Join-Path $projectDir 'build'
New-Item -ItemType Directory -Force -Path $buildDir,(Join-Path $buildDir 'classes'),(Join-Path $buildDir 'dex') | Out-Null
function Run-Native([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build command failed: $Program ($LASTEXITCODE)" }
}
$javaBin = Join-Path $JdkPath 'bin'
$aapt = Join-Path $BuildToolsPath 'aapt2.exe'
$resources = Join-Path $buildDir 'resources.zip'
$unsigned = Join-Path $buildDir 'unsigned.apk'
$aligned = Join-Path $buildDir 'aligned.apk'
$signed = Join-Path $buildDir 'signed.apk'
Run-Native $aapt @('compile','--dir',(Join-Path $projectDir 'res'),'-o',$resources)
Run-Native $aapt @('link','-I',$AndroidJar,'--manifest',(Join-Path $projectDir 'AndroidManifest.xml'),'-o',$unsigned,$resources)
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectDir 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
Run-Native (Join-Path $javaBin 'javac.exe') (@('--release','8','-encoding','UTF-8','-classpath',$AndroidJar,'-d',(Join-Path $buildDir 'classes')) + $sources)
$classesJar = Join-Path $buildDir 'classes.jar'
Run-Native (Join-Path $javaBin 'jar.exe') @('cf',$classesJar,'-C',(Join-Path $buildDir 'classes'),'.')
Run-Native (Join-Path $javaBin 'java.exe') @('-cp',(Join-Path $BuildToolsPath 'lib/d8.jar'),'com.android.tools.r8.D8','--release','--min-api','23','--lib',$AndroidJar,'--output',(Join-Path $buildDir 'dex'),$classesJar)
Run-Native (Join-Path $javaBin 'jar.exe') @('uf',$unsigned,'-C',(Join-Path $buildDir 'dex'),'classes.dex')
Run-Native (Join-Path $BuildToolsPath 'zipalign.exe') @('-f','4',$unsigned,$aligned)
# Set AXIS_KEYSTORE_PASSWORD only in the current shell; do not commit signing keys.
if (-not $env:AXIS_KEYSTORE_PASSWORD) { throw 'Set AXIS_KEYSTORE_PASSWORD to sign the APK.' }
Run-Native (Join-Path $javaBin 'java.exe') @('-jar',(Join-Path $BuildToolsPath 'lib/apksigner.jar'),'sign','--ks',$KeyStore,'--ks-key-alias',$KeyAlias,'--ks-pass','env:AXIS_KEYSTORE_PASSWORD','--v4-signing-enabled','false','--out',$signed,$aligned)
Run-Native (Join-Path $javaBin 'java.exe') @('-jar',(Join-Path $BuildToolsPath 'lib/apksigner.jar'),'verify','--verbose',$signed)
$outputDir = [System.IO.Path]::GetDirectoryName($OutputApk)
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
Move-Item -LiteralPath $signed -Destination $OutputApk -Force
# Prune only this app's release files, and only after the replacement is verified.
$releaseDir = [System.IO.Path]::GetFullPath((Join-Path $projectDir '../release'))
if ([string]::Equals($outputDir, $releaseDir, [System.StringComparison]::OrdinalIgnoreCase)) {
    $obsoleteFiles = @(Get-ChildItem -LiteralPath $releaseDir -File | Where-Object {
        ($_.Name -like '三轴角度-v*.apk' -and $_.FullName -ne $OutputApk) -or
        $_.Name -like '三轴角度-v*.apk.idsig' -or
        ($_.Name -like '验证结果-v*.txt' -and $_.Name -ne "验证结果-v$version.txt")
    })
    foreach ($obsolete in $obsoleteFiles) { Remove-Item -LiteralPath $obsolete.FullName }
    $digest = (Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256).Hash.ToLowerInvariant()
    "$digest  $expectedName" | Set-Content -LiteralPath (Join-Path $releaseDir 'SHA256SUMS.txt') -Encoding UTF8
}
Write-Output "APK ready: $OutputApk"
