package com.aicompany.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Juegos por niveles (spec 2026-10-16 contrato §4): la escena la escribe Forjai, con el script en el nodo raíz. */
class GameSceneFilesTest {

    @Test void theSkeletonIsAValidSceneWithTheScriptOnTheRootNode() {
        var file = GameSceneFiles.skeleton("Main");
        assertEquals("game/Main.tscn", file.path());
        assertEquals("""
                [gd_scene load_steps=2 format=3]

                [ext_resource type="Script" path="res://Main.cs" id="1"]

                [node name="Main" type="Node2D"]
                script = ExtResource("1")
                """, file.content());
    }
}
