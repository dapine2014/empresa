#!/bin/bash
# Pasos fijos de DOTNET_APP (spec 2026-09-26 §2). Sin red: solo el feed precargado en /opt/nuget-packages.
set -euo pipefail
cd /work
mkdir -p .forjai
export NUGET_PACKAGES=/opt/nuget-packages
# NuGet lee la config de usuario de $HOME (tmpfs): feed offline, también para resolver SDKs de MSBuild.
mkdir -p "$HOME/.nuget/NuGet" && cp /forjai/NuGet.Config "$HOME/.nuget/NuGet/NuGet.Config"
SLN=/tmp/Forjai.sln
# MSBuild saltea un proyecto inválido (SDK no encontrado) y termina en 0: eso tiene que fallar el paso.
checked() {
  set +e
  "$@" 2>&1 | tee /tmp/step.log
  local rc=${PIPESTATUS[0]}
  set -e
  if grep -qE "Invalid project|error MSB4236" /tmp/step.log; then
    echo "FORJAI: hay proyectos que MSBuild no pudo cargar (ver arriba)."; exit 1
  fi
  return $rc
}
make_sln() {
  dotnet new sln -n Forjai -o /tmp --force >/dev/null
  # Verificado en vivo (MISSION-SANDBOX-VERIFY-2): sin proyectos, restore/build/test "pasaban" sin compilar nada.
  if [ -z "$(find /work -name '*.csproj' -not -path '*/.forjai/*' -print -quit)" ]; then
    echo "FORJAI: no hay ningún proyecto .csproj en el repositorio."; exit 1
  fi
  # Un proyecto que MSBuild no puede cargar queda fuera de la solución y el build lo ignoraría: falla acá.
  find /work -name '*.csproj' -not -path '*/.forjai/*' -print0 | xargs -0 -r dotnet sln "$SLN" add > /tmp/sln.log 2>&1 || true
  if grep -qE "Invalid project|error MSB" /tmp/sln.log; then
    cat /tmp/sln.log; echo "FORJAI: hay proyectos que MSBuild no pudo cargar."; exit 1
  fi
}
case "$1" in
  restore) make_sln; checked dotnet restore "$SLN" --source /opt/nuget-packages ;;
  build)   make_sln; checked dotnet build "$SLN" --no-restore -c Release ;;
  test)    make_sln; checked dotnet test "$SLN" --no-build -c Release --logger "trx;LogFileName=results.trx" --results-directory /work/.forjai ;;
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
