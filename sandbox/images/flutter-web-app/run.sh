#!/bin/bash
# Pasos fijos de FLUTTER_WEB_APP (solo web en esta ola).
set -euo pipefail
cd /work
mkdir -p .forjai
case "$1" in
  restore) flutter pub get --offline ;;
  # Sin red: CanvasKit empaquetado, no desde el CDN. --no-pub: las dependencias ya las resolvió restore y cada paso es
  # un contenedor nuevo (la caché pub en overlay se descarta); verificado en vivo con Flutter 3.41, el pub get implícito
  # intentaba descargar y colgaba el paso hasta el timeout. --no-wasm-dry-run: solo web JS (ahorra una compilación).
  build)   flutter build web --release --no-web-resources-cdn --no-pub --no-wasm-dry-run ;;
  test)    flutter test --no-pub --machine > /work/.forjai/results.json || true
           # Diagnóstico 360: los tests que fallan, en el formato de dotnet test (Java los lee de la salida).
           python3 /forjai/failing_tests.py /work/.forjai/results.json || true
           grep -q '"success":true' /work/.forjai/results.json ;;
  smoke)
    python3 -m http.server --directory /work/build/web 8080 --bind 127.0.0.1 > /tmp/http.log 2>&1 &
    sleep 2
    google-chrome --headless=new --no-sandbox --disable-gpu --disable-dev-shm-usage --enable-logging=stderr --v=0 \
      --virtual-time-budget=15000 --dump-dom http://127.0.0.1:8080/ > /tmp/dom.html 2> /tmp/chrome.log || true
    if grep -q "Uncaught" /tmp/chrome.log; then grep "Uncaught" /tmp/chrome.log; exit 1; fi
    if grep -Eq "flutter-view|flt-glass-pane" /tmp/dom.html; then echo "App Flutter presente en el DOM"; exit 0; fi
    echo "La app Flutter no apareció en el DOM"; exit 1 ;;
  package)
    # Entrega del producto: la web compilada queda en app/ (el Dockerfile de la entrega copia app/ a nginx).
    # `flutter build web` no tiene --offline: sin red alcanza con los mismos flags del paso build.
    set +e
    OUT=/work/.forjai/package; mkdir -p "$OUT/status"
    if flutter build web --release --no-web-resources-cdn --no-pub --no-wasm-dry-run > /tmp/package-web.log 2>&1 \
       && [ -f /work/build/web/index.html ]; then
      rm -rf "$OUT/app" && cp -r /work/build/web "$OUT/app" && echo PASS > "$OUT/status/web"
    else
      echo "FAIL: $(tail -3 /tmp/package-web.log | tr '\n' ' ' | cut -c1-280)" > "$OUT/status/web"
    fi
    cat /tmp/package-web.log; echo "== package web: $(cat "$OUT/status/web")" ;;
  *) echo "paso desconocido: $1"; exit 2 ;;
esac
