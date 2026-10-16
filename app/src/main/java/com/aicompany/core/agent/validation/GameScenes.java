package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Juegos por niveles (spec 2026-10-16 contrato §4): las escenas del diseño de Kael que necesita cada incremento. */
public final class GameScenes {

    public static final String MAIN = "ESCENA-01";

    private static final String LEVEL = "LEVEL";

    /** Spec y diseño de juego aprobados de una misión, con el incremento que se construye. */
    public record Approved(RequirementsSpec spec, InteractionDesign.GameDesign game, int increment) {
    }

    private GameScenes() {
    }

    /** ESCENA-01 y las escenas de los niveles de los incrementos 1..N, en el orden de scenes y sin repetir. */
    public static List<InteractionDesign.Scene> upTo(InteractionDesign.GameDesign game, RequirementsSpec spec, int increment) {
        var levels = new HashSet<String>();
        for (int n = 1; n <= increment; n++) {
            spec.gameLevelsOf(n).forEach(l -> levels.add(l.id()));
        }
        var used = new HashSet<String>();
        used.add(MAIN);
        nn(game.levels()).stream().filter(l -> levels.contains(l.item())).forEach(l -> used.add(l.scene()));
        return nn(game.scenes()).stream().filter(s -> used.contains(s.id())).toList();
    }

    public static Optional<InteractionDesign.Scene> main(InteractionDesign.GameDesign game) {
        return nn(game.scenes()).stream().filter(s -> MAIN.equals(s.id())).findFirst();
    }

    /** Las escenas donde ocurre un nivel o escena del contrato exponen Level y StartLevel. */
    public static boolean hasLevels(InteractionDesign.GameDesign game, String sceneId) {
        return nn(game.levels()).stream().anyMatch(l -> Objects.equals(sceneId, l.scene()));
    }

    /** Los ítems LEVEL del contrato, en su orden: Level/StartLevel(n) usan 1..N sobre esta lista (SYSTEM y SCENE no se numeran). */
    public static List<ClientContract.Item> numberedLevels(RequirementsSpec spec) {
        return spec.contractItemsOrEmpty().stream().filter(i -> LEVEL.equals(i.kind())).toList();
    }

    /** El número (1..N) del nivel itemId para Level/StartLevel; 0 si no es un ítem LEVEL del contrato. */
    public static int levelNumber(RequirementsSpec spec, String itemId) {
        var levels = numberedLevels(spec);
        for (int i = 0; i < levels.size(); i++) {
            if (Objects.equals(levels.get(i).id(), itemId)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** La línea que dice qué número es cada nivel; vacía si el contrato no tiene niveles. */
    public static String levelNumbering(RequirementsSpec spec) {
        var levels = numberedLevels(spec);
        if (levels.isEmpty()) {
            return "";
        }
        var parts = new java.util.ArrayList<String>();
        for (int i = 0; i < levels.size(); i++) {
            parts.add((i + 1) + " = " + levels.get(i).id() + " " + levels.get(i).name());
        }
        return "Level y StartLevel usan 1..N: " + String.join(", ", parts) + " (solo los niveles del contrato, en su "
                + "orden; sus escenas y sistemas no se numeran).";
    }

    public static String scenePath(String script) {
        return "game/" + script + ".tscn";
    }

    public static String scriptPath(String script) {
        return "game/" + script + ".cs";
    }

    private static <T> List<T> nn(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }
}
