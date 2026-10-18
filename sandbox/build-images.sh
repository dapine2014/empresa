#!/bin/bash
# Construye las imágenes del sandbox con Podman sin root (una vez y al cambiar versiones).
set -euo pipefail
cd "$(dirname "$0")/images"
# Nunca reconstruir un tag existente: el runner desplegado usa el anterior hasta su redeploy.
echo "== egress-proxy"; podman build -t "localhost/forjai-sandbox/egress-proxy:1" egress-proxy
# Entrega del producto (spec 2026-10-17): paso package en todos los perfiles.
echo "== dotnet-app"; podman build -t "localhost/forjai-sandbox/dotnet-app:2" dotnet-app
echo "== flutter-web-app"; podman build -t "localhost/forjai-sandbox/flutter-web-app:2" flutter-web-app
echo "== godot-dotnet-game"; podman build -t "localhost/forjai-sandbox/godot-dotnet-game:3" godot-dotnet-game
podman images | grep forjai-sandbox
# Parte 3: caché de dependencias aprobadas (staging de FETCH, NuGet aprobado y pub sembrado desde la imagen).
mkdir -p ~/forjai-deps/staging ~/forjai-deps/nuget
mkdir -p ~/forjai-deps/pub
# Entrega del producto: el staging lo montan company-core y el runner; si lo creara Docker quedaría de root.
mkdir -p ~/entregas-forja/.staging
# Sin pisar lo ya aprobado: agrega a la caché lo que trae la imagen (p. ej. http 1.2.2, spec 2026-10-02 B1).
podman run --rm --userns=keep-id -v ~/forjai-deps/pub:/seed:z localhost/forjai-sandbox/flutter-web-app:2 \
  bash -c 'cp -an /opt/pub-cache/. /seed/'
