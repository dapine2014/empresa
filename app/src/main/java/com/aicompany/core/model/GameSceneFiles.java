package com.aicompany.core.model;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.agent.validation.GameScenes;

/**
 * Juegos por niveles (spec 2026-10-16 contrato §4): la escena .tscn de cada escena del diseño la escribe Forjai. Verificado en
 * el Pong 3 (MISSION-KAEL-PONG-1792113455): Kael escribía Main.tscn a mano sin el script en el nodo raíz y las 9
 * partidas caían en el paso 0. Kael crea los nodos por código en _Ready.
 */
public final class GameSceneFiles {

    private GameSceneFiles() {
    }

    public static GeneratedFile skeleton(String script) {
        return new GeneratedFile(GameScenes.scenePath(script), """
                [gd_scene load_steps=2 format=3]

                [ext_resource type="Script" path="res://%s.cs" id="1"]

                [node name="%s" type="Node2D"]
                script = ExtResource("1")
                """.formatted(script, script));
    }
}
