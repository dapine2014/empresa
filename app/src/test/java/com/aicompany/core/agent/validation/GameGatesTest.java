package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GameGatesTest {

    @Test void externalAssetsAreRejectedAndShapesAreFine() {
        assertFalse(GameAssetGate.check(List.of(new GeneratedFile("game/Main.tscn",
                "[ext_resource type=\"Texture2D\" path=\"res://paleta.png\" id=\"2\"]"))).isEmpty());
        assertFalse(GameAssetGate.check(List.of(new GeneratedFile("game/Ball.cs",
                "var s = GD.Load<AudioStream>(\"res://pong.wav\");"))).isEmpty());
        assertEquals(List.of(), GameAssetGate.check(List.of(new GeneratedFile("game/Main.tscn",
                "[ext_resource type=\"Script\" path=\"res://Main.cs\" id=\"1\"]\n[node name=\"Paleta\" type=\"ColorRect\"]"))));
        assertEquals(List.of(), GameAssetGate.check(List.of(new GeneratedFile("src/Pong.Domain/Score.cs", "// sprite.png"))));
    }

    @Test void theMainSceneMustBeDeclaredAndExist() {
        var godot = new GeneratedFile("game/project.godot", "[application]\nrun/main_scene=\"res://Main.tscn\"\n");
        assertFalse(GameMainSceneGate.check(List.of(godot), Map.of()).isEmpty());                       // falta Main.tscn
        assertEquals(List.of(), GameMainSceneGate.check(List.of(godot, new GeneratedFile("game/Main.tscn", "x")), Map.of()));
        assertEquals(List.of(), GameMainSceneGate.check(List.of(godot), Map.of("game/Main.tscn", "x"))); // ya commiteada
        assertFalse(GameMainSceneGate.check(List.of(new GeneratedFile("game/project.godot", "[application]\n")), Map.of()).isEmpty());
        assertEquals(List.of(), GameMainSceneGate.check(List.of(new GeneratedFile("game/Ball.cs", "x")), Map.of()));
    }

    @Test void randomnessIsInjectedInTheRules() {
        var roots = List.of("src/Pong.Domain", "src/Pong.Application");
        assertFalse(InjectableRandomGate.check(List.of(new GeneratedFile("src/Pong.Domain/Ball.cs",
                "var r = new Random();")), roots).isEmpty());
        assertFalse(InjectableRandomGate.check(List.of(new GeneratedFile("src/Pong.Application/Serve.cs",
                "var a = GD.Randf();")), roots).isEmpty());
        assertEquals(List.of(), InjectableRandomGate.check(List.of(new GeneratedFile("src/Pong.Domain/Ball.cs",
                "public Ball(IRandom random) { _random = random; }")), roots));
        assertEquals(List.of(), InjectableRandomGate.check(List.of(new GeneratedFile("game/Main.cs",
                "var r = new Random();")), roots)); // la escena puede crear la semilla
    }

    // Review Focus 5
    @Test void reservedPathsInsideGameAreProtected() {
        var playthrough = new GeneratedFile("game/playthroughs/ESC_01_mueve.cs", "x");
        var runner = new GeneratedFile("game/forjai/PlaythroughRunner.cs", "x");
        assertFalse(ReservedGamePathsGate.check(List.of(playthrough), false).isEmpty());
        assertEquals(List.of(), ReservedGamePathsGate.check(List.of(playthrough), true));
        assertFalse(ReservedGamePathsGate.check(List.of(runner), true).isEmpty());
        assertFalse(ReservedGamePathsGate.check(List.of(runner), false).isEmpty());
    }

    @Test void gatesWorkUnderTheComponentPrefix() {
        assertFalse(GameAssetGate.check(List.of(new GeneratedFile("game-client/game/Main.tscn",
                "[ext_resource type=\"Texture2D\" path=\"res://paleta.png\" id=\"2\"]"))).isEmpty());
        var godot = new GeneratedFile("game-client/game/project.godot", "[application]\nrun/main_scene=\"res://Main.tscn\"\n");
        assertFalse(GameMainSceneGate.check(List.of(godot), Map.of()).isEmpty());
        assertEquals(List.of(), GameMainSceneGate.check(List.of(godot, new GeneratedFile("game-client/game/Main.tscn", "x")), Map.of()));
        assertEquals(List.of(), GameMainSceneGate.check(List.of(godot), Map.of("game-client/game/Main.tscn", "")));
        assertFalse(ReservedGamePathsGate.check(List.of(new GeneratedFile("game-client/game/forjai/R.cs", "x")), true).isEmpty());
        assertFalse(ReservedGamePathsGate.check(List.of(new GeneratedFile("game-client/game/playthroughs/E.cs", "x")), false).isEmpty());
        assertEquals(List.of(), ReservedGamePathsGate.check(List.of(new GeneratedFile("game-client/game/playthroughs/E.cs", "x")), true));
    }

    @Test void commentsAreIgnored() {
        assertEquals(List.of(), GameAssetGate.check(List.of(new GeneratedFile("game/Ball.cs",
                "// sprite.png\n/* pong.wav */ var x = 1;"))));
        assertFalse(GameAssetGate.check(List.of(new GeneratedFile("game/Ball.cs",
                "var s = GD.Load<Texture2D>(\"res://sprite.png\"); // carga"))).isEmpty());
        var roots = List.of("src/Pong.Domain");
        assertEquals(List.of(), InjectableRandomGate.check(List.of(new GeneratedFile("src/Pong.Domain/Ball.cs",
                "// no usar new Random()\n/* GD.Randf() */ var a = 1;")), roots));
    }

    @Test void godotRandomTypesAreRejectedAndThePrefixIsHonored() {
        var roots = List.of("game-client/src/Pong.Domain");
        assertFalse(InjectableRandomGate.check(List.of(new GeneratedFile("game-client/src/Pong.Domain/Ball.cs",
                "var r = new RandomNumberGenerator();")), roots).isEmpty());
        assertFalse(InjectableRandomGate.check(List.of(new GeneratedFile("game-client/src/Pong.Domain/Ball.cs",
                "var r = GD.Randfn(0, 1);")), roots).isEmpty());
        assertFalse(InjectableRandomGate.check(List.of(new GeneratedFile("game-client/src/Pong.Domain/Ball.cs",
                "var r = new Random();")), roots).isEmpty());
        assertEquals(List.of(), InjectableRandomGate.check(List.of(new GeneratedFile("src/Pong.Domain/Ball.cs",
                "var r = new Random();")), roots));
    }

    // Juegos por niveles (spec 2026-10-16 contrato §4).
    private static final List<GameSceneScriptsGate.SceneScript> SCENES = List.of(
            new GameSceneScriptsGate.SceneScript("ESCENA-01", "Main", "game/Main.cs", true));

    @Test void everySceneHasItsScriptWithItsClassAndLevelMembers() {
        var owned = List.of("game");
        assertTrue(GameSceneScriptsGate.check(List.of(), Map.of(), SCENES, owned).get(0).contains("Falta game/Main.cs"));
        var noLevels = GameSceneScriptsGate.check(List.of(new GeneratedFile("game/Main.cs",
                "public partial class Main : Node2D { }")), Map.of(), SCENES, owned);
        assertTrue(noLevels.stream().anyMatch(e -> e.contains("public int Level")), noLevels.toString());
        assertTrue(noLevels.stream().anyMatch(e -> e.contains("StartLevel")), noLevels.toString());
        var wrongClass = GameSceneScriptsGate.check(List.of(new GeneratedFile("game/Main.cs",
                "public class Juego : Node { }")), Map.of(), SCENES, owned);
        assertTrue(wrongClass.stream().anyMatch(e -> e.contains("public partial class Main : Node2D")), wrongClass.toString());
        var ok = "public partial class Main : Node2D { public int Level { get; private set; } = 1;\n"
                + "  public void StartLevel(int level) { Level = level; } }";
        assertEquals(List.of(), GameSceneScriptsGate.check(List.of(new GeneratedFile("game/Main.cs", ok)), Map.of(), SCENES, owned));
        // En una ronda, Kael no lo devuelve: vale el del repositorio.
        assertEquals(List.of(), GameSceneScriptsGate.check(List.of(), Map.of("game/Main.cs", ok), SCENES, owned));
        // Quien no es dueño de game/ no responde por las escenas.
        assertEquals(List.of(), GameSceneScriptsGate.check(List.of(), Map.of(), SCENES, List.of("src/Pong.Domain")));
    }

    @Test void theMainSceneMustBeTheOneOfTheDesign() {
        var godot = new GeneratedFile("game/project.godot", "[application]\nrun/main_scene=\"res://Juego.tscn\"\n");
        var errors = GameMainSceneGate.check(List.of(godot), Map.of("game/Juego.tscn", "", "game/Main.tscn", ""), "Main.tscn");
        assertTrue(errors.stream().anyMatch(e -> e.contains("res://Main.tscn")), errors.toString());
        assertEquals(List.of(), GameMainSceneGate.check(List.of(new GeneratedFile("game/project.godot",
                "[application]\nrun/main_scene=\"res://Main.tscn\"\n")), Map.of("game/Main.tscn", ""), "Main.tscn"));
    }

    @Test void theScenesOfForjaiAreReserved() {
        var errors = ReservedGamePathsGate.check(List.of(new GeneratedFile("game/Main.tscn", "x")), false,
                java.util.Set.of("game/Main.tscn"));
        assertEquals(List.of("game/Main.tscn es de Forjai: no lo escribas (tu escena crea sus nodos en _Ready)."), errors);
        assertEquals(List.of(), ReservedGamePathsGate.check(List.of(new GeneratedFile("game/Main.cs", "x")), false,
                java.util.Set.of("game/Main.tscn")));
    }
}
