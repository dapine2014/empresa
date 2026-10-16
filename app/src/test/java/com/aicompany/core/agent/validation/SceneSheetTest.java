package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Juegos por niveles (spec 2026-10-16 contrato §4): Java lee los miembros públicos de la escena, sin modelo. */
class SceneSheetTest {

    static final String MAIN = """
            using Godot;
            // public int Viejo { get; set; }   (comentado: no cuenta)
            public partial class Main : Node2D
            {
                public int Level { get; private set; } = 1;
                public int Puntaje => _puntaje;
                public static readonly float VelocidadBase = 200f;
                public ColorRect Pelota;
                public Vector2 Direccion { get; set; }
                private int _puntaje;
                internal int Oculto;
                public override void _Ready() { var s = "public int Falso;"; }
                public void StartLevel(int level) { Level = level; }
                public bool EstaJugando() => true;
                /* public void Comentado() {} */
            }
            """;

    @Test void publicPropertiesFieldsAndMethodsAreExtracted() {
        var members = SceneSheet.extract("Main", List.of(MAIN));
        assertEquals(List.of("Level", "Puntaje", "VelocidadBase", "Pelota", "Direccion", "StartLevel", "EstaJugando"),
                members.stream().map(SceneSheet.Member::name).toList());
        assertEquals("int Level { get; }", SceneSheet.render(members.get(0)));
        assertEquals("void StartLevel(int level)", SceneSheet.render(members.get(5)));
        assertEquals("ColorRect Pelota", SceneSheet.render(members.get(3)));
        assertEquals("METHOD", members.get(6).kind());
    }

    // Review Focus 5: comentarios y strings no entran a la ficha.
    @Test void commentsAndStringsDoNotCount() {
        var names = SceneSheet.extract("Main", List.of(MAIN)).stream().map(SceneSheet.Member::name).toList();
        assertFalse(names.contains("Viejo"));
        assertFalse(names.contains("Falso"));
        assertFalse(names.contains("Comentado"));
        assertFalse(names.contains("_Ready"));
        assertFalse(names.contains("Oculto"));
    }

    @Test void partialClassesAcrossFilesAreMergedAndOtherClassesIgnored() {
        var other = "public partial class Main { public void Reiniciar() { } }\npublic class Ayuda { public int X; }";
        var names = SceneSheet.extract("Main", List.of(MAIN, other)).stream().map(SceneSheet.Member::name).toList();
        assertTrue(names.contains("Reiniciar"), names.toString());
        assertFalse(names.contains("X"), names.toString());
    }

    @Test void genericAndNullableTypesAreRead() {
        var src = "public partial class Main : Node2D { public List<ColorRect> Pelotas { get; } = new(); public int? Meta; }";
        var members = SceneSheet.extract("Main", List.of(src));
        assertEquals("List<ColorRect> Pelotas { get; }", SceneSheet.render(members.get(0)));
        assertEquals("int? Meta", SceneSheet.render(members.get(1)));
    }

    @Test void stripBlanksCommentsAndStrings() {
        var s = SceneSheet.strip("int a; // public int Viejo\n/* public int B */ var t = \"main.Pelota\";");
        assertFalse(s.contains("Viejo"));
        assertFalse(s.contains("B */"));
        assertFalse(s.contains("main.Pelota"));
    }
}
