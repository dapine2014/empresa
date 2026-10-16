package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Juegos por niveles (spec 2026-10-16 contrato §4): cada escena del incremento tiene su script con su clase; las que tienen
 * niveles exponen Level y StartLevel (las partidas de Vera los usan para arrancar en un nivel).
 */
public final class GameSceneScriptsGate {

    public record SceneScript(String id, String script, String path, boolean levels) {
    }

    private static final Pattern LEVEL = Pattern.compile("public\\s+int\\s+Level\\b");
    private static final Pattern START_LEVEL = Pattern.compile("public\\s+void\\s+StartLevel\\s*\\(\\s*int\\b");

    private GameSceneScriptsGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> returned, Map<String, String> existing,
                                     List<SceneScript> scenes, List<String> owned) {
        var errors = new ArrayList<String>();
        for (var scene : scenes) {
            if (!OwnedPaths.coveredByAny(owned, scene.path())) {
                continue;
            }
            var content = returned.stream().filter(Objects::nonNull)
                    .filter(f -> scene.path().equals(f.path())).map(DevelopmentResult.GeneratedFile::content)
                    .filter(Objects::nonNull).findFirst().orElse(existing.get(scene.path()));
            var declaration = "public partial class " + scene.script() + " : Node2D";
            if (content == null) {
                errors.add("Falta " + scene.path() + ": la escena " + scene.id() + " necesita su script con "
                        + declaration + " (Forjai ya creó la .tscn que lo carga).");
                continue;
            }
            var code = SceneSheet.strip(content);
            if (!Pattern.compile("\\bpartial\\s+class\\s+" + Pattern.quote(scene.script()) + "\\s*:\\s*(?:Godot\\.)?Node2D\\b")
                    .matcher(code).find()) {
                errors.add(scene.path() + " debe declarar " + declaration + " (la .tscn de Forjai lo carga en su nodo raíz).");
            }
            if (scene.levels() && !LEVEL.matcher(code).find()) {
                errors.add(scene.path() + ": la escena " + scene.id() + " tiene niveles y debe exponer public int Level { get; }.");
            }
            if (scene.levels() && !START_LEVEL.matcher(code).find()) {
                errors.add(scene.path() + ": la escena " + scene.id() + " tiene niveles y debe exponer "
                        + "public void StartLevel(int level).");
            }
        }
        return errors;
    }
}
