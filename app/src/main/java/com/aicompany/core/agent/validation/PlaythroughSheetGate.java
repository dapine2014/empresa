package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.model.StackProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Juegos por niveles (spec 2026-10-16 contrato §4): antes del sandbox, las partidas de Vera solo usan miembros que existen en
 * la escena (el Pong 3 usaba Main.Pelota, FinalScoreLabel y EstadoJuego, que no existían). Reconoce (X)main, main as X
 * y las variables asignadas con ellos; lo que no reconoce lo juzga el compilador en el sandbox.
 */
public final class PlaythroughSheetGate {

    /** Lo heredado de Node/Node2D que una partida puede usar sobre la escena. */
    public static final Set<String> GODOT_MEMBERS = Set.of("GetNode", "GetNodeOrNull", "GetChildren", "GetChild",
            "GetChildCount", "FindChild", "Position", "GlobalPosition", "Visible", "Name", "IsInsideTree", "GetTree",
            "Rotation", "Scale");

    private static final Pattern SCENE = Pattern.compile("\\bScene\\s*=>\\s*\"([^\"]*)\"");
    private static final Pattern CAST_ACCESS = Pattern.compile("\\(\\s*\\(\\s*([A-Za-z_]\\w*)\\s*\\)\\s*([A-Za-z_]\\w*)\\s*\\)\\s*\\.\\s*([A-Za-z_]\\w*)");
    private static final Pattern MAIN_CAST = Pattern.compile("\\(\\s*([A-Za-z_]\\w*)\\s*\\)\\s*main\\b|\\bmain\\s+as\\s+([A-Za-z_]\\w*)");
    private static final Pattern ALIAS = Pattern.compile("\\b(?:var|[A-Z]\\w*)\\s+([A-Za-z_]\\w*)\\s*=\\s*(?:\\(\\s*([A-Za-z_]\\w*)\\s*\\)\\s*main\\b|main\\s+as\\s+([A-Za-z_]\\w*))");

    private PlaythroughSheetGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files,
                                     Map<String, List<SceneSheet.Member>> contracts) {
        var errors = new LinkedHashSet<String>();
        var scenes = contracts.keySet().stream().sorted().collect(Collectors.joining(", "));
        for (var f : files.stream().filter(Objects::nonNull).toList()) {
            var path = f.path() == null ? "" : OwnedPaths.normalize(f.path());
            if (!path.contains(StackProfile.PLAYTHROUGHS_ROOT + "/") || f.content() == null) {
                continue;
            }
            var scene = SCENE.matcher(f.content());
            while (scene.find()) {
                if (!contracts.containsKey(scene.group(1))) {
                    errors.add(path + ": Scene \"" + scene.group(1) + "\" no es una escena del diseño (existen: " + scenes + ").");
                }
            }
            var code = SceneSheet.strip(f.content());
            var cast = MAIN_CAST.matcher(code);
            while (cast.find()) {
                var type = cast.group(1) != null ? cast.group(1) : cast.group(2);
                if (!contracts.containsKey(type)) {
                    errors.add(path + ": " + type + " no es una escena del diseño: main es una de " + scenes + ".");
                }
            }
            var aliases = new HashMap<String, String>();
            var alias = ALIAS.matcher(code);
            while (alias.find()) {
                var type = alias.group(2) != null ? alias.group(2) : alias.group(3);
                if (contracts.containsKey(type)) {
                    aliases.put(alias.group(1), type);
                }
            }
            var access = CAST_ACCESS.matcher(code);
            while (access.find()) {
                if (contracts.containsKey(access.group(1))) {
                    member(path, access.group(1), access.group(3), contracts, errors);
                }
            }
            aliases.forEach((variable, type) -> {
                var use = Pattern.compile("(?<![\\w.])" + Pattern.quote(variable) + "\\s*\\.\\s*([A-Za-z_]\\w*)").matcher(code);
                while (use.find()) {
                    member(path, type, use.group(1), contracts, errors);
                }
            });
        }
        return new ArrayList<>(errors);
    }

    private static void member(String path, String type, String name, Map<String, List<SceneSheet.Member>> contracts,
                               Set<String> errors) {
        var members = contracts.get(type);
        if (GODOT_MEMBERS.contains(name) || members.stream().anyMatch(m -> m.name().equals(name))) {
            return;
        }
        errors.add(path + ": " + type + "." + name + " no existe; existen: "
                + members.stream().map(SceneSheet.Member::name).collect(Collectors.joining(", ")) + ".");
    }
}
