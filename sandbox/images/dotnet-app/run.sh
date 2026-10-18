#!/bin/bash
# Pasos fijos de DOTNET_APP (spec 2026-09-26 §2). Sin red: solo el feed precargado en /opt/nuget-packages.
set -euo pipefail
cd /work
mkdir -p .forjai
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
  restore) make_sln; checked dotnet restore "$SLN" ;;
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
  package)
    # Entrega del producto: el proyecto ejecutable publicado en app/, con forjai-entry.txt para el Dockerfile.
    set +e
    OUT=/work/.forjai/package; mkdir -p "$OUT/status"
    status() { echo "$1" > "$OUT/status/app"; echo "== package app: $1"; }
    projects() { find /work -name '*.csproj' -not -path '*/.forjai/*' -print0 | sort -z | xargs -0 -r grep -l "$@"; }
    CSPROJ=$(projects -E 'Sdk="Microsoft\.NET\.Sdk\.Web"' | head -n1)
    if [ -z "$CSPROJ" ]; then
      EXES=$(projects -iE '<OutputType>[[:space:]]*Exe[[:space:]]*</OutputType>')
      [ "$(printf '%s' "$EXES" | grep -c .)" -eq 1 ] && CSPROJ=$EXES
    fi
    if [ -z "$CSPROJ" ]; then status "FAIL: no hay proyecto ejecutable"; exit 0; fi
    ASM=$(sed -n 's/.*<AssemblyName>[[:space:]]*\([^<[:space:]]*\)[[:space:]]*<\/AssemblyName>.*/\1/p' "$CSPROJ" | head -n1)
    [ -n "$ASM" ] || ASM=$(basename "$CSPROJ" .csproj)
    rm -rf "$OUT/app"
    if ! dotnet publish "$CSPROJ" -c Release --no-restore -o "$OUT/app" > /tmp/publish.log 2>&1; then
      cat /tmp/publish.log
      status "FAIL: $(grep -E 'error' /tmp/publish.log | tail -3 | tr '\n' ' ' | cut -c1-280)"
    elif [ ! -f "$OUT/app/$ASM.dll" ]; then
      status "FAIL: dotnet publish no dejó $ASM.dll"
    else
      echo "$ASM.dll" > "$OUT/app/forjai-entry.txt"
      status PASS
    fi ;;
  *) echo "paso desconocido: $1"; exit 2 ;;
esac
