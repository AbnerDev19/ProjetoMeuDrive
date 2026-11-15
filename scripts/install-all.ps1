param([string]$Apk = "app/build/outputs/apk/debug/app-debug.apk")
$ErrorActionPreference = "Stop"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw "Instale Android SDK Platform-Tools e adicione adb ao PATH." }
if (-not (Test-Path -LiteralPath $Apk)) { throw "APK não encontrado: $Apk" }
$Devices = @(& adb devices | Select-Object -Skip 1 | ForEach-Object {
    if ($_ -match '^(\S+)\s+device\s*$') { $Matches[1] }
})
if ($Devices.Count -eq 0) { throw "Nenhum celular autorizado. Conecte por USB, ative depuração USB e aceite a chave RSA na tela." }
$Failed = $false
foreach ($Serial in $Devices) {
    Write-Host "Instalando em $Serial…"
    & adb -s $Serial install -r $Apk
    if ($LASTEXITCODE -ne 0) { $Failed = $true }
}
if ($Failed) { exit 1 }
