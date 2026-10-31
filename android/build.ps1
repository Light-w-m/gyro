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
Run-Native (Join-Path $javaBin 'java.exe') @('-jar',(Join-Path $BuildToolsPath 'lib/apksigner.jar'),'sign','--ks',$KeyStore,'--ks-key-alias',$KeyAlias,'--ks-pass','env:AXIS_KEYSTORE_PASSWORD','--out',$OutputApk,$aligned)
Run-Native (Join-Path $javaBin 'java.exe') @('-jar',(Join-Path $BuildToolsPath 'lib/apksigner.jar'),'verify','--verbose',$OutputApk)
Write-Output "APK ready: $OutputApk"
