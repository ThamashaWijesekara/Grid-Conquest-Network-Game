$ErrorActionPreference = "Stop"

$configDirectory = Join-Path $PSScriptRoot "config"
$keyStore = Join-Path $configDirectory "grid-conquest.p12"
New-Item -ItemType Directory -Force $configDirectory | Out-Null

if (Test-Path $keyStore) {
    Write-Host "Keystore already exists: $keyStore"
    exit 0
}

& keytool -genkeypair `
    -alias grid-conquest `
    -keyalg RSA `
    -keysize 2048 `
    -validity 365 `
    -storetype PKCS12 `
    -keystore $keyStore `
    -storepass changeit `
    -keypass changeit `
    -dname "CN=localhost, OU=Class Project, O=Grid Conquest, L=Colombo, C=LK" `
    -ext "SAN=dns:localhost,ip:127.0.0.1"

Write-Host "Created classroom TLS keystore: $keyStore"
