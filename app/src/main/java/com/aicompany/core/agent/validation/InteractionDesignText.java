package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.*;
import java.util.stream.Collectors;

/** Spec 2026-10-14 Kael §1.4: el diseño aprobado como texto (Java) para los prompts y el chat. */
public final class InteractionDesignText {

    private InteractionDesignText() {
    }

    public static Map<String, String> scenarioTargets(InteractionDesign design) {
        var out = new LinkedHashMap<String, String>();
        if (design.app() != null) {
            nn(design.app().scenarioScreens()).forEach(m -> out.put(m.scenario(), m.screen()));
        }
        if (design.game() != null) {
            nn(design.game().scenarioRules()).forEach(m -> out.put(m.scenario(), m.target()));
        }
        return out;
    }

    public static Set<String> scenariosOf(RequirementsSpec spec, int increment) {
        return spec.storiesOf(increment).stream().flatMap(s -> s.scenariosOrEmpty().stream())
                .map(RequirementsSpec.Scenario::id).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Pantallas de las HU del incremento (por sus flujos), en orden. */
    public static Set<String> screensOf(InteractionDesign design, RequirementsSpec spec, int increment) {
        var stories = spec.storiesOf(increment).stream().map(RequirementsSpec.UserStory::id).toList();
        return design.app() == null ? Set.of() : nn(design.app().flows()).stream()
                .filter(f -> stories.contains(f.story())).flatMap(f -> nn(f.screens()).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static String incrementBlock(InteractionDesign design, RequirementsSpec spec, int increment) {
        var scenarios = scenariosOf(spec, increment);
        var targets = scenarioTargets(design);
        if (design.app() != null) {
            var stories = spec.storiesOf(increment).stream().map(RequirementsSpec.UserStory::id).toList();
            var screens = screensOf(design, spec, increment);
            return """
                    DISEÑO DE INTERACCIÓN APROBADO (Kael): INCREMENTO %d
                    Pantallas (cada una lleva Key('PANT-NN') en su widget raíz; Forjai lo verifica):
                    %s
                    Flujos:
                    %s
                    Escenarios → pantalla: %s
                    """.formatted(increment,
                    nn(design.app().screens()).stream().filter(s -> screens.contains(s.id()))
                            .map(s -> "- " + s.id() + " " + s.name() + " (" + s.role() + "): " + s.purpose()
                                    + (nn(s.states()).isEmpty() ? "" : " — estados: " + String.join(", ", nn(s.states())))
                                    + " → Key('" + s.id() + "')")
                            .collect(Collectors.joining("\n")),
                    nn(design.app().flows()).stream().filter(f -> stories.contains(f.story()))
                            .map(f -> "- " + f.story() + ": " + String.join(" → ", nn(f.screens()))
                                    + (nn(f.actions()).isEmpty() ? "" : " (" + String.join("; ", nn(f.actions())) + ")"))
                            .collect(Collectors.joining("\n")),
                    scenarios.stream().map(id -> id + "→" + targets.get(id)).collect(Collectors.joining(", ")));
        }
        var game = design.game();
        // Van todas las reglas menos las que solo apuntan escenarios de OTROS incrementos: las sin escenario
        // (rebote en la pared) o con condición de control también las necesita quien programa este incremento.
        var ownTargets = new HashSet<String>();
        var otherTargets = new HashSet<String>();
        targets.forEach((scenario, target) -> (scenarios.contains(scenario) ? ownTargets : otherTargets).add(target));
        var winLose = new HashSet<String>(nn(game.win()));
        winLose.addAll(nn(game.lose()));
        var sceneBlock = "";
        if (!nn(game.scenes()).isEmpty()) {
            var scenes = GameScenes.upTo(game, spec, increment);
            var levels = spec.gameLevelsOf(increment).stream().map(ClientContract.Item::id).toList();
            var main = GameScenes.main(game).map(InteractionDesign.Scene::script).orElse("Main");
            sceneBlock = """
                    Escenas (Forjai ya creó cada game/<script>.tscn con el script en el nodo raíz; no las escribas):
                    %s
                    Niveles de este incremento (parámetros y regla para pasar al siguiente):
                    %s
                    Cada escena es public partial class <script> : Node2D en game/<script>.cs y crea por código, en _Ready, TODOS los nodos que usa (AddChild): la .tscn no tiene hijos, nunca los esperes ni uses GetNode de hijos de la escena.
                    Las escenas donde ocurre un nivel o escena del contrato exponen public int Level { get; } y public void StartLevel(int level) (StartLevel deja el nivel listo para jugar; las partidas simuladas lo usan para arrancar directo en un nivel).%s
                    game/project.godot declara run/main_scene="res://%s.tscn" (ESCENA-01).
                    """.formatted(
                    scenes.stream().map(s -> "- " + s.id() + " " + s.name() + " → " + GameScenes.scenePath(s.script())
                                    + " + " + GameScenes.scriptPath(s.script()) + " (clase " + s.script()
                                    + (GameScenes.MAIN.equals(s.id()) ? ", escena principal" : "") + "): " + s.purpose())
                            .collect(Collectors.joining("\n")),
                    nn(game.levels()).stream().filter(l -> levels.contains(l.item())).map(l -> level(spec, l))
                            .collect(Collectors.joining("\n")),
                    GameScenes.levelNumbering(spec).isEmpty() ? "" : "\n" + GameScenes.levelNumbering(spec),
                    main);
        }
        return """
                DISEÑO DE JUEGO APROBADO (Kael): INCREMENTO %d
                Estados: %s
                Transiciones: %s
                Entidades: %s
                Controles (acciones de input de Godot; decláralas en [input] de game/project.godot):
                %s
                Bloque [input] de game/project.godot (Java ya tradujo las teclas a sus códigos de Godot; cópialo tal cual):
                %s
                Reglas:
                %s
                Gana: %s · Pierde: %s
                Dificultad: %s
                Escenarios → regla o control: %s
                Sin assets externos: dibuja con nodos (ColorRect, Polygon2D, Label) o en _Draw().
                Lee el input SOLO con las acciones de [input] (Input.IsActionPressed / GetAxis), nunca con teclas directas.
                No uses GetTree().Quit(), ChangeSceneToFile ni ReloadCurrentScene, ni rutas /root/…: el reinicio tras game over se hace dentro de la escena.
                Expón en el script de la escena principal, como propiedades públicas, el estado que se verifica (puntaje, estado del loop).
                """.formatted(increment, String.join(", ", nn(game.states())),
                nn(game.transitions()).stream().map(t -> t.from() + "→" + t.to() + " (" + t.trigger() + ")")
                        .collect(Collectors.joining(", ")),
                nn(game.entities()).stream().map(e -> e.id() + " " + nn(e.properties()).stream()
                        .map(p -> p.name() + "=" + num(p.value())).collect(Collectors.joining(", ", "{", "}")))
                        .collect(Collectors.joining("; ")),
                nn(game.controls()).stream().map(c -> "- " + c.action() + " [" + c.key() + "]: " + c.effect())
                        .collect(Collectors.joining("\n")),
                GodotKeys.inputBlock(nn(game.controls())).strip(),
                nn(game.rules()).stream().filter(r -> winLose.contains(r.id()) || ownTargets.contains(r.id())
                                || !otherTargets.contains(r.id()))
                        .map(r -> "- " + r.id() + ": SI " + r.condition() + " ENTONCES " + r.effect())
                        .collect(Collectors.joining("\n")),
                String.join(", ", nn(game.win())), String.join(", ", nn(game.lose())),
                nn(game.difficulty()).stream().map(p -> p.name() + "=" + num(p.value())).collect(Collectors.joining(", ")),
                scenarios.stream().map(id -> id + "→" + targets.get(id)).collect(Collectors.joining(", "))) + sceneBlock;
    }

    private static String level(RequirementsSpec spec, InteractionDesign.LevelDesign l) {
        var rule = l.advanceRule() == null || l.advanceRule().isBlank() ? "" : " — pasa al siguiente con " + l.advanceRule().strip();
        var number = GameScenes.levelNumber(spec, l.item());
        return "- " + (number > 0 ? "Nivel " + number + " (" + l.item() + ")" : l.item()) + " en " + l.scene() + ": " + nn(l.parameters()).stream()
                .map(p -> p.name() + "=" + num(p.value())).collect(Collectors.joining(", ", "{", "}")) + rule;
    }

    public static String summary(InteractionDesign design) {
        if (design.app() != null) {
            return "Pantallas: " + nn(design.app().screens()).stream().map(s -> s.id() + " " + s.name())
                    .collect(Collectors.joining(", ")) + ". Flujos: " + nn(design.app().flows()).stream()
                    .map(f -> f.story() + " " + String.join("→", f.screens())).collect(Collectors.joining("; "))
                    + ". Riesgos (hipótesis): " + String.join("; ", nn(design.app().frictionRisks())) + ".";
        }
        var g = design.game();
        var scenes = nn(g.scenes()).isEmpty() ? "" : " Escenas: " + nn(g.scenes()).stream()
                .map(s -> s.id() + " " + s.name() + " (" + s.script() + ")").collect(Collectors.joining(", "))
                + ". Niveles: " + nn(g.levels()).stream().map(l -> l.item() + " en " + l.scene() + " "
                        + nn(l.parameters()).stream().map(p -> p.name() + "=" + num(p.value()))
                        .collect(Collectors.joining(", ", "{", "}"))
                        + (l.advanceRule() == null || l.advanceRule().isBlank() ? "" : " → " + l.advanceRule().strip()))
                .collect(Collectors.joining("; ")) + ".";
        return "Loop: " + String.join("→", nn(g.states())) + ". Controles: " + nn(g.controls()).stream()
                .map(c -> c.action() + " [" + c.key() + "]").collect(Collectors.joining(", ")) + ". Reglas: "
                + nn(g.rules()).stream().map(r -> r.id() + " " + r.effect()).collect(Collectors.joining("; "))
                + ". Pierde: " + String.join(", ", nn(g.lose())) + ". Balance (hipótesis): "
                + String.join("; ", nn(g.balanceHypotheses())) + "." + scenes;
    }

    /** Los modelos pueden dejar elementos nulos en las listas: se omiten. */
    private static <T> List<T> nn(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }

    private static String num(Double v) {
        return v == null ? "?" : v == Math.rint(v) ? String.valueOf(v.longValue()) : String.valueOf(v);
    }
}
