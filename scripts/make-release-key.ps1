# Creates the release signing key for GrabLine for Android.
# Run it yourself: keytool asks for the passwords, and nothing here stores them.
# Keep the .jks file and its passwords safe and backed up OUTSIDE the repository:
# losing them means users cannot update to builds signed with a new key.
param(
    [string]$Out = "$HOME\grabline-release.jks",
    [string]$Alias = "grabline"
)
if (Test-Path $Out) { Write-Error "$Out already exists; not overwriting it."; exit 1 }
keytool -genkeypair -v -keystore $Out -alias $Alias -keyalg RSA -keysize 4096 -validity 10000 `
    -dname "CN=GrabLine, O=GrabLine"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host ""
Write-Host "Created $Out (alias '$Alias')."
Write-Host "Certificate SHA-256 (publish this so users can verify the APK):"
keytool -list -v -keystore $Out -alias $Alias | Select-String "SHA256:"
Write-Host ""
Write-Host "For GitHub releases, add these repository secrets (docs/RELEASING.md):"
Write-Host "  RELEASE_KEYSTORE_BASE64  = [Convert]::ToBase64String([IO.File]::ReadAllBytes('$Out'))"
Write-Host "  RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS ($Alias), RELEASE_KEY_PASSWORD"
