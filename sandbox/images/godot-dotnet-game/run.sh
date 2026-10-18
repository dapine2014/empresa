#!/bin/bash
# Pasos fijos de GODOT_DOTNET_GAME. El dominio y la aplicación son bibliotecas .NET puras; game/ es el proyecto Godot.
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
  build)   make_sln; checked dotnet build "$SLN" --no-restore -c Debug ;;
  test)
    make_sln
    rm -f /work/.forjai/playthroughs.json
    # `checked` reactiva errexit: los rc se capturan con || para que las partidas corran aunque fallen los tests unitarios.
    rc=0
    checked dotnet test "$SLN" --no-build -c Debug --logger "trx;LogFileName=results.trx" --results-directory /work/.forjai || rc=$?
    play=0
    # Spec 2026-10-14 Kael §2.3: las partidas simuladas de Vera sobre la escena principal real.
    # timeout: sin el ensamblado compilado el runner gira al 100% de CPU sin terminar (spike Task 0).
    if [ -n "$(find /work/game/playthroughs -name '*.cs' -print -quit 2>/dev/null)" ]; then
      mkdir -p /work/.forjai
      timeout 120 godot --headless --fixed-fps 60 --path /work/game res://forjai/Runner.tscn -- --out=/work/.forjai/playthroughs.json \
        > /tmp/play.log 2>&1 || play=$?
      cat /tmp/play.log
      # Si el juego cierra Godot o cambia de escena, el runner no llega a escribir: eso no puede pasar como verde.
      if [ ! -f /work/.forjai/playthroughs.json ]; then
        play=1
        echo "FORJAI: el runner de partidas no escribió resultados (¿el juego llamó Quit o cambió de escena?)"
      fi
    fi
    [ "$rc" -eq 0 ] && [ "$play" -eq 0 ] ;;
  smoke)
    [ -f /work/game/project.godot ] || { echo "Falta game/project.godot"; exit 1; }
    godot --headless --path /work/game --quit-after 300 > /tmp/godot.log 2>&1 || { cat /tmp/godot.log; exit 1; }
    cat /tmp/godot.log
    if grep -Eq "SCRIPT ERROR|ERROR:|Unhandled exception" /tmp/godot.log; then echo "Errores durante la ejecución"; exit 1; fi
    echo "300 frames sin errores" ;;
  package)
    # Entrega del producto: un target que falla escribe su FAIL y sigue con el siguiente.
    set +e
    OUT=/work/.forjai/package; mkdir -p "$OUT/status"
    status() { echo "$2" > "$OUT/status/$1"; }
    # Godot lee templates y editor settings de $HOME (tmpfs): enlaces a lo que trae la imagen.
    mkdir -p "$HOME/.local/share/godot/export_templates" "$HOME/.config/godot"
    ln -sfn /opt/godot-templates/4.3.stable.mono "$HOME/.local/share/godot/export_templates/4.3.stable.mono"
    cp /forjai/editor_settings-4.3.tres "$HOME/.config/godot/editor_settings-4.3.tres"
    cp /forjai/export_presets.cfg /work/game/export_presets.cfg
    # Godot exige la solución <assembly_name>.sln junto al proyecto para exportar C# (verificado en vivo).
    ASM=$(sed -n 's/^project\/assembly_name="\(.*\)"$/\1/p' /work/game/project.godot | head -n1)
    [ -n "$ASM" ] || ASM=$(basename "$(find /work/game -maxdepth 1 -name '*.csproj' | head -n1)" .csproj)
    if [ ! -f "/work/game/$ASM.sln" ]; then
      dotnet new sln -n "$ASM" -o /work/game >/dev/null && dotnet sln "/work/game/$ASM.sln" add "/work/game/$ASM.csproj" >/dev/null
      CREATED_SLN="/work/game/$ASM.sln"
    fi
    # macOS universal y Android exigen importar texturas ETC2/ASTC: solo en la copia de trabajo, no en el repo.
    if ! grep -q '^textures/vram_compression/import_etc2_astc=true' /work/game/project.godot; then
      if grep -q '^\[rendering\]' /work/game/project.godot; then
        sed -i 's/^\[rendering\]$/[rendering]\n\ntextures\/vram_compression\/import_etc2_astc=true/' /work/game/project.godot
      else
        printf '\n[rendering]\n\ntextures/vram_compression/import_etc2_astc=true\n' >> /work/game/project.godot
      fi
    fi
    make_sln; dotnet build "$SLN" --no-restore -c Release > /tmp/release.log 2>&1 || true
    IFS=',' read -ra TS <<< "${FORJAI_TARGETS:-}"
    for t in "${TS[@]}"; do
      case "$t" in
        windows)     dir=juego/windows; file=Juego.exe;         preset=Windows ;;
        macos)       dir=juego/macos;   file=Juego-macos.zip;   preset=macOS ;;
        linux)       dir=juego/linux;   file=Juego.x86_64;      preset=Linux ;;
        android-apk) dir=juego/android; file=Juego-debug.apk;   preset=AndroidApk ;;
        android-aab) dir=juego/android; file=Juego.aab;         preset=AndroidAab ;;
        *) status "$t" "FAIL: target desconocido"; continue ;;
      esac
      mkdir -p "$OUT/$dir"
      mode=--export-release; [ "$t" = android-apk ] && mode=--export-debug
      # El AAB se arma con Gradle: necesita la plantilla de build de Android instalada en el proyecto.
      extra=(); [ "$t" = android-aab ] && extra=(--install-android-build-template)
      log=/tmp/export-$t.log
      timeout 600 godot --headless --path /work/game "${extra[@]}" $mode "$preset" "$OUT/$dir/$file" > "$log" 2>&1
      rc=$?
      # Godot sale en 0 aunque el plugin de C# lance excepciones: el ejecutable quedaría sin los ensamblados.
      if [ "$rc" -eq 0 ] && [ -s "$OUT/$dir/$file" ] && ! grep -qE 'System\.[A-Za-z.]*Exception' "$log"; then
        status "$t" PASS
      elif grep -q 'services.gradle.org' "$log"; then
        status "$t" "FAIL: el AAB necesita Gradle y el sandbox no tiene red ni caché de Gradle"
      else
        # Motivo: las líneas de error sin trazas de pila ni el aviso genérico de Godot.
        why=$(grep -vE '^[[:space:]]+at[: ]|due to configuration errors|Project export for preset|C#/.NET is experimental|No project icon specified' "$log" \
          | grep -iE 'error|failed|cannot|exception|invalid|required|not installed|missing' | tail -3 | tr '\n' ' ' | cut -c1-280)
        status "$t" "FAIL: ${why:-la exportación terminó con código $rc}"
      fi
      # Un export fallido no deja un archivo a medias en la entrega.
      [ "$(cat "$OUT/status/$t")" = PASS ] || rm -f "$OUT/$dir/$file"
      echo "== export $t: $(cat "$OUT/status/$t")"
    done
    # La copia de los presets no forma parte del producto.
    rm -f /work/game/export_presets.cfg ${CREATED_SLN:-} ;;
  *) echo "paso desconocido: $1"; exit 2 ;;
esac
