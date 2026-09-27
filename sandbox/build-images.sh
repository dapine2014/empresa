#!/bin/bash
# Construye las imágenes del sandbox con Podman sin root (una vez y al cambiar versiones).
set -euo pipefail
cd "$(dirname "$0")/images"
for profile in dotnet-app godot-dotnet-game flutter-web-app egress-proxy; do
  echo "== $profile"
  podman build -t "localhost/forjai-sandbox/$profile:1" "$profile"
done
podman images | grep forjai-sandbox
# Parte 3: caché de dependencias aprobadas (staging de FETCH, NuGet aprobado y pub sembrado desde la imagen).
mkdir -p ~/forjai-deps/staging ~/forjai-deps/nuget
if [ ! -d ~/forjai-deps/pub/hosted ]; then
  mkdir -p ~/forjai-deps/pub
  podman run --rm --userns=keep-id -v ~/forjai-deps/pub:/seed:z localhost/forjai-sandbox/flutter-web-app:1 \
    bash -c 'cp -a /opt/pub-cache/. /seed/'
fi
