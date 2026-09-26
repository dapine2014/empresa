#!/bin/bash
# Pasos fijos de FLUTTER_WEB_APP (solo web en esta ola).
set -euo pipefail
cd /work
mkdir -p .forjai
case "$1" in
  restore) flutter pub get --offline ;;
  build)   flutter build web --release ;;
  test)    flutter test --machine > /work/.forjai/results.json || true
           grep -q '"success":true' /work/.forjai/results.json ;;
  smoke)
    python3 -m http.server --directory /work/build/web 8080 --bind 127.0.0.1 > /tmp/http.log 2>&1 &
    sleep 2
    google-chrome --headless=new --no-sandbox --disable-gpu --enable-logging=stderr --v=0 \
      --virtual-time-budget=15000 --dump-dom http://127.0.0.1:8080/ > /tmp/dom.html 2> /tmp/chrome.log || true
    if grep -q "Uncaught" /tmp/chrome.log; then grep "Uncaught" /tmp/chrome.log; exit 1; fi
    if grep -Eq "flutter-view|flt-glass-pane" /tmp/dom.html; then echo "App Flutter presente en el DOM"; exit 0; fi
    echo "La app Flutter no apareció en el DOM"; exit 1 ;;
  *) echo "paso desconocido: $1"; exit 2 ;;
esac
