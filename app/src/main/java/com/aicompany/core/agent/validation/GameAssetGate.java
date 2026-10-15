package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Spec 2026-10-14 Kael §2.2: el juego no usa assets externos (no hay arte ni audio en esta fase). */
public final class GameAssetGate {

    private static final Pattern ASSET = Pattern.compile(
            "(?i)[\\w./-]+\\.(png|jpe?g|svg|wav|ogg|mp3|ttf|otf|glb|gltf)\\b");

    private GameAssetGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files) {
        var errors = new ArrayList<String>();
        for (var f : files.stream().filter(Objects::nonNull).toList()) {
            var path = f.path() == null ? "" : f.path();
            // "game/" como segmento: en un plan multicomponente el juego vive bajo un prefijo (game-client/game/...).
            var inGame = path.startsWith("game/") || path.contains("/game/");
            if (!inGame || !(path.endsWith(".tscn") || path.endsWith(".tres") || path.endsWith(".cs"))) {
                continue;
            }
            var m = ASSET.matcher(f.content() == null ? "" : f.content());
            if (m.find()) {
                errors.add(path + " usa el asset externo \"" + m.group() + "\": no hay arte ni audio; dibuja con nodos "
                        + "(ColorRect, Polygon2D, Label) o en _Draw().");
            }
        }
        return errors;
    }
}
