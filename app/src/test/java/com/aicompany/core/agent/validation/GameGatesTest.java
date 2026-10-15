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
}
