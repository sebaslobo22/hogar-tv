#!/usr/bin/env bash
# Instala Hogar en un Google TV / Android TV de tu red y lo deja configurado.
#
# Uso:  ./scripts/instalar.sh [IP_DEL_TV] [ruta/al/hogar.apk]
#
# Sin IP, busca televisores en la red (Bonjour/mDNS). Sin APK, usa la compilación local
# (app/build/outputs/apk/release/app-release.apk).
#
# Qué hace: conecta por depuración de red, revisa que el TV sea compatible, instala la app y activa
# los permisos que la propia app necesita (servicio de accesibilidad para el botón Inicio y el puntero,
# lector de notificaciones para "Reproduciendo ahora", superposición para el receptor AirPlay).
# Qué NO hace: no rootea, no desactiva apps de fábrica, no cambia la escala ni el firmware.
# Para deshacerlo todo:  adb uninstall live.hogar
set -euo pipefail

PKG=live.hogar
A11Y="$PKG/live.hogar.launcher.HomeButtonService"
LISTENER="$PKG/live.hogar.nowplaying.MediaListener"
APK="${2:-app/build/outputs/apk/release/app-release.apk}"

command -v adb >/dev/null || { echo "Falta adb (Android platform-tools)."; exit 1; }
[ -f "$APK" ] || { echo "No encuentro el APK: $APK (compila con ./gradlew assembleRelease o descárgalo de Releases)."; exit 1; }

find_tv() {
  # Google TV anuncia _googlecast._tcp; tomamos la primera IP que responda en el puerto 5555 (depuración).
  if command -v dns-sd >/dev/null; then
    for name in $( (dns-sd -B _googlecast._tcp local. & sleep 4; kill $! 2>/dev/null) | awk '/Add/ {print $7}' | sort -u); do
      host=$( (dns-sd -L "$name" _googlecast._tcp local. & sleep 2; kill $! 2>/dev/null) | grep -oE 'reached at [^:]+' | awk '{print $3}' | head -1)
      ip=$(dscacheutil -q host -a name "$host" 2>/dev/null | awk '/ip_address/ {print $2; exit}')
      [ -n "${ip:-}" ] && nc -z -G 2 "$ip" 5555 2>/dev/null && { echo "$ip"; return; }
    done
  fi
}

IP="${1:-$(find_tv || true)}"
[ -n "$IP" ] || { echo "No encontré un TV con depuración de red activa. Pasa la IP: ./scripts/instalar.sh 192.168.x.x"; exit 1; }
export ANDROID_SERIAL="$IP:5555"

echo "→ Conectando con $ANDROID_SERIAL (acepta el aviso en la pantalla del TV)…"
adb connect "$ANDROID_SERIAL" >/dev/null || true
for _ in $(seq 1 30); do adb get-state 2>/dev/null | grep -q device && break; sleep 2; done
adb get-state | grep -q device || { echo "El TV no autorizó la conexión."; exit 1; }

SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
ABI=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')
MODEL="$(adb shell getprop ro.product.manufacturer | tr -d '\r') $(adb shell getprop ro.product.model | tr -d '\r')"
echo "→ TV: $MODEL · Android API $SDK · $ABI"
[ "$SDK" -ge 29 ] || { echo "Hogar necesita Android 10 (API 29) o superior."; exit 1; }

echo "→ Instalando…"
adb install -r "$APK"

echo "→ Activando el botón Inicio y el modo puntero (servicio de accesibilidad de Hogar)…"
current=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$current" in
  *"$A11Y"*) ;;
  null|"") adb shell settings put secure enabled_accessibility_services "$A11Y" ;;
  *) adb shell settings put secure enabled_accessibility_services "$current:$A11Y" ;;
esac
adb shell settings put secure accessibility_enabled 1

echo "→ Permitiendo 'Reproduciendo ahora' (lector de notificaciones de medios)…"
adb shell cmd notification allow_listener "$LISTENER" || echo "  (actívalo a mano en Ajustes si tu TV no lo permite por adb)"

echo "→ Permitiendo que el visor lea el nivel del audio (fondo que late con la música)…"
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO || true

echo "→ Permitiendo que el receptor AirPlay abra la pantalla completa…"
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true

adb shell am start -n "$PKG/live.hogar.app.MainActivity" >/dev/null
echo "✓ Listo. Pulsa Inicio en el control: abre Hogar. Abre 'Este TV' para ver qué soporta tu televisor."
echo "  Nota: si alguna vez 'fuerzas la detención' de Hogar, Android apaga su servicio; Hogar te mostrará un botón para reactivarlo."
