#!/bin/bash
# Pasos fijos de GODOT_DOTNET_GAME. El dominio y la aplicación son bibliotecas .NET puras; game/ es el proyecto Godot.
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
  build)   make_sln; dotnet build "$SLN" --no-restore -c Debug ;;
  test)    make_sln; dotnet test "$SLN" --no-build -c Debug --logger "trx;LogFileName=results.trx" --results-directory /work/.forjai ;;
  smoke)
    [ -f /work/game/project.godot ] || { echo "Falta game/project.godot"; exit 1; }
    godot --headless --path /work/game --quit-after 300 > /tmp/godot.log 2>&1 || { cat /tmp/godot.log; exit 1; }
    cat /tmp/godot.log
    if grep -Eq "SCRIPT ERROR|ERROR:|Unhandled exception" /tmp/godot.log; then echo "Errores durante la ejecución"; exit 1; fi
    echo "300 frames sin errores" ;;
  *) echo "paso desconocido: $1"; exit 2 ;;
esac
