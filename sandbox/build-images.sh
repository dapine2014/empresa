#!/bin/bash
# Construye las imágenes del sandbox con Podman sin root (una vez y al cambiar versiones).
set -euo pipefail
cd "$(dirname "$0")/images"
for profile in dotnet-app flutter-web-app egress-proxy; do
  echo "== $profile"
  podman build -t "localhost/forjai-sandbox/$profile:1" "$profile"
done
echo "== godot-dotnet-game"; podman build -t "localhost/forjai-sandbox/godot-dotnet-game:2" godot-dotnet-game
podman images | grep forjai-sandbox
# Parte 3: caché de dependencias aprobadas (staging de FETCH, NuGet aprobado y pub sembrado desde la imagen).
mkdir -p ~/forjai-deps/staging ~/forjai-deps/nuget
mkdir -p ~/forjai-deps/pub
# Sin pisar lo ya aprobado: agrega a la caché lo que trae la imagen (p. ej. http 1.2.2, spec 2026-10-02 B1).
podman run --rm --userns=keep-id -v ~/forjai-deps/pub:/seed:z localhost/forjai-sandbox/flutter-web-app:1 \
  bash -c 'cp -an /opt/pub-cache/. /seed/'
