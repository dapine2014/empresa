#!/bin/bash
# Construye las imágenes del sandbox con Podman sin root (una vez y al cambiar versiones).
set -euo pipefail
cd "$(dirname "$0")/images"
for profile in dotnet-app godot-dotnet-game flutter-web-app egress-proxy; do
  echo "== $profile"
  podman build -t "localhost/forjai-sandbox/$profile:1" "$profile"
done
podman images | grep forjai-sandbox
