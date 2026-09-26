#!/bin/bash
# Pasos fijos de DOTNET_APP (spec 2026-09-26 §2). Sin red: solo el feed precargado en /opt/nuget-packages.
set -euo pipefail
cd /work
mkdir -p .forjai
export NUGET_PACKAGES=/opt/nuget-packages
SLN=/tmp/Forjai.sln
make_sln() {
  dotnet new sln -n Forjai -o /tmp --force >/dev/null
  find /work -name '*.csproj' -not -path '*/.forjai/*' -print0 | xargs -0 -r dotnet sln "$SLN" add >/dev/null
}
case "$1" in
  restore) make_sln; dotnet restore "$SLN" --source /opt/nuget-packages ;;
  build)   make_sln; dotnet build "$SLN" --no-restore -c Release ;;
  test)    make_sln; dotnet test "$SLN" --no-build -c Release --logger "trx;LogFileName=results.trx" --results-directory /work/.forjai ;;
  smoke)
    API=$(find /work/src -maxdepth 2 -name '*.Api.csproj' | head -n1)
    [ -n "$API" ] || { echo "No hay proyecto src/*.Api"; exit 1; }
    dotnet run --no-build -c Release --project "$API" --urls http://127.0.0.1:5080 > /tmp/api.log 2>&1 &
    for i in $(seq 1 60); do
      if curl -fsS http://127.0.0.1:5080/health >/dev/null 2>&1; then echo "GET /health 200"; exit 0; fi
      sleep 1
    done
    cat /tmp/api.log; echo "La API no respondió GET /health en 60 s"; exit 1 ;;
  *) echo "paso desconocido: $1"; exit 2 ;;
esac
