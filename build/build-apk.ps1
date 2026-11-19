param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$JreHome = $env:JAVA_HOME,
    [string]$RuntimeDir = (Join-Path $PSScriptRoot '..\runtime'),
    [string]$AssetsDir = (Join-Path $PSScriptRoot '..\assets'),
    [string]$OutDir = (Join-Path $PSScriptRoot '..\out'),
    [string]$Keystore = (Join-Path $PSScriptRoot 'debug.keystore'),
    [string]$VersionCode = '2',
    [string]$VersionName = 'v0.2'
)

$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot -Parent
$androidDir = Join-Path $root 'android'

if (-not $AndroidSdk) { throw 'Set ANDROID_HOME or pass -AndroidSdk' }
if (-not (Test-Path $AndroidSdk)) { throw "Android SDK not found: $AndroidSdk" }
if ($JreHome) { $env:JAVA_HOME = $JreHome }

$buildTools = Get-ChildItem (Join-Path $AndroidSdk 'build-tools') -Directory |
    Sort-Object { [int]($_.Name -replace '\..*$', '') } -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'no build-tools installed' }
$bt = $buildTools.FullName

$platforms = Get-ChildItem (Join-Path $AndroidSdk 'platforms') -Directory |
    Sort-Object { [int]($_.Name -replace 'android-', '') } -Descending
$androidJar = Join-Path $platforms[0].FullName 'android.jar'
if (-not (Test-Path $androidJar)) { throw 'android.jar not found' }

foreach ($f in @('libnode.so', 'libz.so', 'libicuuc.so', 'libicudata.so')) {
    if (-not (Test-Path (Join-Path $RuntimeDir $f))) { throw "runtime file missing: $f (see docs/BUILD.md)" }
}
$engineZip = Join-Path $AssetsDir 'engine.zip'
if (-not (Test-Path $engineZip)) { throw "missing $engineZip (see docs/BUILD.md)" }
if (-not (Test-Path $Keystore)) { throw "missing keystore: $Keystore; run build/make-keystore.ps1" }

$work = Join-Path ([System.IO.Path]::GetTempPath()) ('harness-build-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null
Copy-Item (Join-Path $androidDir 'AndroidManifest.xml') $work
Copy-Item -Recurse (Join-Path $androidDir 'src') $work
Copy-Item -Recurse (Join-Path $androidDir 'res') $work

$out = Join-Path $work 'build'
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Output '== aapt2 compile =='
& "$bt\aapt2.exe" compile --dir "$work\res" -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Output '== aapt2 link =='
& "$bt\aapt2.exe" link -o "$out\app-unsigned.apk" -I $androidJar --manifest "$work\AndroidManifest.xml" -R "$out\res.zip" --min-sdk-version 26 --target-sdk-version 34 --version-code $VersionCode --version-name $VersionName --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Output '== javac =='
New-Item -ItemType Directory -Force -Path "$out\classes" | Out-Null
& javac -encoding UTF-8 -source 1.8 -target 1.8 -classpath $androidJar -d "$out\classes" "$work\src\dsh\mobile\MainActivity.java"
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Output '== d8 =='
New-Item -ItemType Directory -Force -Path "$out\dex" | Out-Null
$classes = (Get-ChildItem "$out\classes" -Recurse -Filter '*.class').FullName
& "$bt\d8.bat" --lib $androidJar --min-api 26 --output "$out\dex" $classes
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Output '== assemble =='
Copy-Item "$out\app-unsigned.apk" "$out\app-full.apk" -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\app-full.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", "classes.dex", [System.IO.Compression.CompressionLevel]::NoCompression) | Out-Null
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $engineZip, "assets/engine.zip", [System.IO.Compression.CompressionLevel]::NoCompression) | Out-Null
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $AssetsDir 'cacert.pem'), "assets/cacert.pem", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $AssetsDir 'fonts\texgyreheros.otf'), "assets/fonts/texgyreheros.otf", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null

$libs = @('libnode.so','libbash.so','libz.so','libcares.so','libsqlite3.so','libcrypto.so','libssl.so',
          'libicui18n.so','libicuuc.so','libicudata.so','libc++_shared.so','libandroid-support.so',
          'libreadline.so','libncursesw.so','libiconv.so')
foreach ($lib in $libs) {
    $path = Join-Path $RuntimeDir $lib
    if (-not (Test-Path $path)) { Write-Warning "skip missing $lib"; continue }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $path, "lib/arm64-v8a/$lib", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
}
$zip.Dispose()

Write-Output '== zipalign =='
& "$bt\zipalign.exe" -f 4 "$out\app-full.apk" "$out\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Output '== sign =='
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$apk = Join-Path $OutDir ("Harness-" + $VersionName + ".apk")
& "$bt\apksigner.bat" sign --ks $Keystore --ks-pass pass:android --key-pass pass:android --out $apk "$out\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& "$bt\apksigner.bat" verify $apk
Get-Item $apk | Select-Object Length, FullName
