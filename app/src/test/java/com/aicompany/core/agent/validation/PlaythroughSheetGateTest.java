package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Juegos por niveles (spec 2026-10-16 contrato §4): las partidas de Vera solo usan lo que existe en la escena. */
class PlaythroughSheetGateTest {

    static final Map<String, List<SceneSheet.Member>> CONTRACTS = Map.of(
            "Main", SceneSheet.extract("Main", List.of(SceneSheetTest.MAIN)),
            "Menu", SceneSheet.extract("Menu", List.of("public partial class Menu : Node2D { public bool Visible2; }")));

    private static List<String> check(String body) {
        return PlaythroughSheetGate.check(List.of(new GeneratedFile("game/playthroughs/ESC_01_x.cs", body)), CONTRACTS);
    }

    @Test void anUnknownMemberIsRejectedWithTheExistingOnes() {
        var errors = check("yield return Assert(() => ((Main)main).Pelotaa.Position.X > 0, \"x\");");
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("Main.Pelotaa no existe"), errors.get(0));
        assertTrue(errors.get(0).contains("Level, Puntaje"), errors.get(0));
    }

    @Test void validMembersCastsAliasesAndAsPass() {
        assertEquals(List.of(), check("""
                public override IEnumerable<Step> Steps(Node main) {
                    var juego = (Main)main;
                    Main otro = main as Main;
                    yield return Do(m => ((Main)m).StartLevel(2));
                    yield return Assert(() => juego.Level == 2 && otro.Puntaje == 0 && ((Main)main).Pelota != null, "ok");
                }
                """));
    }

    @Test void aliasesAreChecked() {
        var errors = check("var juego = main as Main;\nyield return Assert(() => juego.EstadoJuego == 1, \"x\");");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Main.EstadoJuego no existe")), errors.toString());
    }

    @Test void theSceneMustBeOneOfTheDesign() {
        var errors = check("public override string Scene => \"Creditos\";");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Creditos") && e.contains("Main, Menu")), errors.toString());
    }

    // Review Focus 4: lo heredado de Godot se permite; castear main a algo que no es escena, no.
    @Test void inheritedGodotMembersAreAllowedButMainCastToANonSceneIsNot() {
        assertEquals(List.of(), check("yield return Assert(() => ((Main)main).GetNode<Label>(\"L\") != null "
                + "&& ((Main)main).Position.X == 0, \"x\");"));
        var errors = check("var n = (Node2D)main;");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Node2D no es una escena del diseño")), errors.toString());
    }

    // Review Focus 5: un string con texto parecido a un acceso no dispara el gate.
    @Test void stringsDoNotCount() {
        assertEquals(List.of(), check("yield return Assert(() => true, \"((Main)main).Inexistente no se usa\");"));
    }

    @Test void filesOutsidePlaythroughsAreIgnored() {
        assertEquals(List.of(), PlaythroughSheetGate.check(List.of(new GeneratedFile("tests/X.cs",
                "var j = (Main)main; j.Nada = 1;")), CONTRACTS));
    }
}
