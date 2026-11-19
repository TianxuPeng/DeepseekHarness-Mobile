param(
    [string]$Out = (Join-Path $PSScriptRoot 'debug.keystore'),
    [string]$Alias = 'androiddebugkey',
    [string]$StorePass = 'android',
    [string]$KeyPass = 'android'
)

$ErrorActionPreference = 'Stop'
if (Test-Path $Out) {
    Write-Output "keystore already exists: $Out"
    exit 0
}

& keytool -genkeypair -keystore $Out -storepass $StorePass -keypass $KeyPass `
    -alias $Alias -keyalg RSA -keysize 2048 -validity 10000 `
    -dname "CN=Harness Debug,O=Harness,C=CN"
if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
Write-Output "created $Out"
