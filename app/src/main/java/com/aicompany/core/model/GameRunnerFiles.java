package com.aicompany.core.model;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;

import java.util.List;

/**
 * Spec 2026-10-14 Kael §2.3: el runner fijo de partidas simuladas (Forjai, nunca un agente). Vera escribe subclases de
 * Playthrough en game/playthroughs; el paso test del sandbox las corre en Godot headless sobre la escena principal real.
 */
public final class GameRunnerFiles {

    private GameRunnerFiles() {
    }

    static final String PLAYTHROUGH = """
            using System;
            using System.Collections.Generic;
            using Godot;

            namespace Forjai.Playthroughs;

            /// <summary>Partida simulada de un escenario: el nombre de la clase lleva el id (ESC_03_descripcion).</summary>
            public abstract class Playthrough
            {
                /// <param name="main">La instancia nueva de la escena principal (run/main_scene), ya en el árbol.</param>
                public abstract IEnumerable<Step> Steps(Node main);

                /// <summary>Mantiene apretada la acción de input durante frames (a 60 fps fijos).</summary>
                protected static Step Press(string action, int frames) => new(StepKind.Press, action, frames, null, null);

                protected static Step Wait(int frames) => new(StepKind.Wait, null, frames, null, null);

                protected static Step Assert(Func<bool> check, string description) =>
                    new(StepKind.Assert, null, 0, check, description);
            }

            public enum StepKind { Press, Wait, Assert }

            public sealed record Step(StepKind Kind, string? Action, int Frames, Func<bool>? Check, string? Description);
            """;

    static final String RUNNER = """
            using System;
            using System.Collections.Generic;
            using System.Linq;
            using System.Text.Json;
            using System.Threading.Tasks;
            using Godot;

            namespace Forjai.Playthroughs;

            /// <summary>Forjai: corre cada Playthrough sobre una escena principal nueva y escribe los resultados.</summary>
            public partial class PlaythroughRunner : Node
            {
                private const int MaxFrames = 3600;

                private sealed record Result(string name, string outcome, string failedStep, string message);

                private readonly List<Result> _results = new();

                public override async void _Ready()
                {
                    // En _Ready la raíz todavía está agregando hijos y rechaza el AddChild de la escena principal.
                    await NextFrame();
                    var mainPath = (string)ProjectSettings.GetSetting("application/run/main_scene");
                    var types = typeof(PlaythroughRunner).Assembly.GetTypes()
                        .Where(t => t.IsSubclassOf(typeof(Playthrough)) && !t.IsAbstract)
                        .OrderBy(t => t.Name, StringComparer.Ordinal);
                    foreach (var type in types)
                    {
                        _results.Add(await Run(type, mainPath));
                    }
                    var output = OS.GetCmdlineUserArgs().FirstOrDefault(a => a.StartsWith("--out="))?.Substring(6)
                                 ?? "/work/.forjai/playthroughs.json";
                    System.IO.File.WriteAllText(output, JsonSerializer.Serialize(_results));
                    GetTree().Quit(_results.All(r => r.outcome == "PASSED") ? 0 : 1);
                }

                private async Task<Result> Run(Type type, string mainPath)
                {
                    Node? main = null;
                    var step = 0;
                    var frames = 0;
                    try
                    {
                        // Cada partida arranca de la misma semilla, sin depender de las partidas anteriores.
                        GD.Seed(0);
                        main = GD.Load<PackedScene>(mainPath).Instantiate();
                        // Como en el juego real: la escena principal cuelga de la raíz y es la escena actual.
                        GetTree().Root.AddChild(main);
                        GetTree().CurrentScene = main;
                        await NextFrame();
                        var playthrough = (Playthrough)Activator.CreateInstance(type)!;
                        foreach (var s in playthrough.Steps(main))
                        {
                            step++;
                            switch (s.Kind)
                            {
                                case StepKind.Press:
                                    if (!InputMap.HasAction(s.Action))
                                    {
                                        return Fail(type, step, "la acción \\"" + s.Action + "\\" no está en [input] de project.godot");
                                    }
                                    Input.ParseInputEvent(new InputEventAction { Action = s.Action!, Pressed = true });
                                    for (var i = 0; i < s.Frames; i++, frames++) await NextFrame();
                                    Input.ParseInputEvent(new InputEventAction { Action = s.Action!, Pressed = false });
                                    break;
                                case StepKind.Wait:
                                    for (var i = 0; i < s.Frames; i++, frames++) await NextFrame();
                                    break;
                                case StepKind.Assert:
                                    if (!s.Check!())
                                    {
                                        return Fail(type, step, "falló la aserción: " + s.Description);
                                    }
                                    break;
                            }
                            if (frames > MaxFrames)
                            {
                                return Fail(type, step, "superó el tope de " + MaxFrames + " frames");
                            }
                        }
                        return new Result(type.Name, "PASSED", "", "");
                    }
                    catch (Exception ex)
                    {
                        return Fail(type, step, ex.GetType().Name + ": " + ex.Message);
                    }
                    finally
                    {
                        foreach (var action in InputMap.GetActions()) Input.ActionRelease(action);
                        main?.QueueFree();
                        await NextFrame();
                    }
                }

                private static Result Fail(Type type, int step, string message) =>
                    new(type.Name, "FAILED", "paso " + step, message);

                private SignalAwaiter NextFrame() => ToSignal(GetTree(), SceneTree.SignalName.PhysicsFrame);
            }
            """;

    static final String RUNNER_SCENE = """
            [gd_scene load_steps=2 format=3]

            [ext_resource type="Script" path="res://forjai/PlaythroughRunner.cs" id="1"]

            [node name="PlaythroughRunner" type="Node"]
            script = ExtResource("1")
            """;

    public static List<GeneratedFile> files() {
        return List.of(new GeneratedFile("game/forjai/Playthrough.cs", PLAYTHROUGH),
                new GeneratedFile("game/forjai/PlaythroughRunner.cs", RUNNER),
                new GeneratedFile("game/forjai/Runner.tscn", RUNNER_SCENE));
    }
}
