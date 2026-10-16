package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Juegos por niveles (spec 2026-10-16 contrato §4): las escenas del diseño de Kael que necesita cada incremento. */
public final class GameScenes {

    public static final String MAIN = "ESCENA-01";

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
