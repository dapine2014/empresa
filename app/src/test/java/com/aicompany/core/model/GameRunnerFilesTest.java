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
}
