package com.aicompany.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 2026-10-14 Kael §2.3: el runner deja la escena principal como escena actual y siembra cada partida. */
class GameRunnerFilesTest {

    @Test void theMainSceneIsTheCurrentSceneUnderTheTreeRoot() {
        assertTrue(GameRunnerFiles.RUNNER.contains("GetTree().Root.AddChild(main);"), GameRunnerFiles.RUNNER);
        assertTrue(GameRunnerFiles.RUNNER.contains("GetTree().CurrentScene = main;"), GameRunnerFiles.RUNNER);
        assertFalse(GameRunnerFiles.RUNNER.contains("                    AddChild(main);"), GameRunnerFiles.RUNNER);
        assertTrue(GameRunnerFiles.RUNNER.contains("main?.QueueFree();"), GameRunnerFiles.RUNNER);
        // En _Ready la raíz sigue "ocupada agregando hijos" (Godot rechaza el AddChild): primero se espera un frame.
        var ready = GameRunnerFiles.RUNNER.substring(GameRunnerFiles.RUNNER.indexOf("public override async void _Ready()"));
        assertTrue(ready.indexOf("await NextFrame();") < ready.indexOf("foreach (var type in types)"), ready);
    }

    @Test void everyPlaythroughStartsFromSeedZero() {
        var run = GameRunnerFiles.RUNNER.substring(GameRunnerFiles.RUNNER.indexOf("private async Task<Result> Run("));
        assertTrue(run.contains("GD.Seed(0);"), run);
        var ready = GameRunnerFiles.RUNNER.substring(0, GameRunnerFiles.RUNNER.indexOf("private async Task<Result> Run("));
        assertFalse(ready.contains("GD.Seed(0);"), ready);
    }

    @Test void theCSharpStringLiteralKeepsItsEscapedQuotes() {
        // El text block de Java convierte \" en ": el C# generado no compilaba (visto en la verificación manual).
        assertTrue(GameRunnerFiles.RUNNER.contains("\"la acción \\\"\" + s.Action + \"\\\" no está en [input]"),
                GameRunnerFiles.RUNNER);
    }

    // Juegos por niveles (spec 2026-10-16 contrato §4): una partida puede cargar otra escena y preparar el nivel.
    @Test void aPlaythroughCanChooseItsSceneAndPrepareTheLevel() {
        var p = GameRunnerFiles.PLAYTHROUGH;
        assertTrue(p.contains("public virtual string? Scene => null;"), p);
        assertTrue(p.contains("protected static Step Do(Action<Node> prepare) => new(StepKind.Do, null, 0, null, \"preparar\", prepare);"), p);
        assertTrue(p.contains("public enum StepKind { Press, Wait, Assert, Do }"), p);
        assertTrue(p.contains("Action<Node>? Prepare = null"), p);
        var run = GameRunnerFiles.RUNNER.substring(GameRunnerFiles.RUNNER.indexOf("private async Task<Result> Run("));
        assertTrue(run.contains("var scenePath = playthrough.Scene == null ? mainPath : \"res://\" + playthrough.Scene + \".tscn\";"), run);
        assertTrue(run.contains("ResourceLoader.Exists(scenePath)"), run);
        assertTrue(run.contains("case StepKind.Do:"), run);
        assertTrue(run.contains("s.Prepare!(main);"), run);
        // La instancia de la partida se crea antes de cargar la escena (decide cuál).
        assertTrue(run.indexOf("Activator.CreateInstance(type)") < run.indexOf("GD.Load<PackedScene>(scenePath)"), run);
        assertTrue(run.indexOf("GD.Seed(0);") < run.indexOf("GD.Load<PackedScene>(scenePath)"), run);
    }
}
