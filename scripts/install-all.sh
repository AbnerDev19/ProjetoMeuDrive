#!/usr/bin/env bash
set -euo pipefail
apk="${1:-app/build/outputs/apk/debug/app-debug.apk}"
command -v adb >/dev/null || { echo 'Instale Android SDK Platform-Tools e adicione adb ao PATH.' >&2; exit 1; }
[[ -f "$apk" ]] || { echo "APK não encontrado: $apk" >&2; exit 1; }
mapfile -t serials < <(adb devices | awk 'NR>1 && $2 == "device" {print $1}')
((${#serials[@]})) || { echo 'Nenhum celular autorizado. Conecte por USB, ative depuração USB e aceite a chave RSA na tela.' >&2; exit 1; }
failed=0
for serial in "${serials[@]}"; do
  echo "Instalando em $serial…"
  adb -s "$serial" install -r "$apk" || failed=1
done
exit "$failed"
