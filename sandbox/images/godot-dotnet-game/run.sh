#!/bin/bash
# Pasos fijos de GODOT_DOTNET_GAME. El dominio y la aplicación son bibliotecas .NET puras; game/ es el proyecto Godot.
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
  # Un proyecto que MSBuild no puede cargar queda fuera de la solución y el build lo ignoraría: falla acá.
  find /work -name '*.csproj' -not -path '*/.forjai/*' -print0 | xargs -0 -r dotnet sln "$SLN" add > /tmp/sln.log 2>&1 || true
  if grep -qE "Invalid project|error MSB" /tmp/sln.log; then
    cat /tmp/sln.log; echo "FORJAI: hay proyectos que MSBuild no pudo cargar."; exit 1
  fi
}
case "$1" in
  restore) make_sln; checked dotnet restore "$SLN" --source /opt/nuget-packages ;;
  build)   make_sln; checked dotnet build "$SLN" --no-restore -c Debug ;;
  test)    make_sln; checked dotnet test "$SLN" --no-build -c Debug --logger "trx;LogFileName=results.trx" --results-directory /work/.forjai ;;
  smoke)
    [ -f /work/game/project.godot ] || { echo "Falta game/project.godot"; exit 1; }
    godot --headless --path /work/game --quit-after 300 > /tmp/godot.log 2>&1 || { cat /tmp/godot.log; exit 1; }
    cat /tmp/godot.log
    if grep -Eq "SCRIPT ERROR|ERROR:|Unhandled exception" /tmp/godot.log; then echo "Errores durante la ejecución"; exit 1; fi
    echo "300 frames sin errores" ;;
  *) echo "paso desconocido: $1"; exit 2 ;;
esac
