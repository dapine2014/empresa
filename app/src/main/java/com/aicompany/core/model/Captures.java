package com.aicompany.core.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Spec 2026-11-01 front con diseño visual: lo que devolvió una captura del sandbox. Clave {@code "<PANT-NN>-<phone|desktop>"}.
 */
public record Captures(Map<String, Path> pngByScreenSize, Map<String, List<Box>> boxesByScreenSize) {

    public static final List<String> SIZES = List.of("phone", "desktop");

    public Captures {
        pngByScreenSize = pngByScreenSize == null ? Map.of() : Map.copyOf(pngByScreenSize);
        boxesByScreenSize = boxesByScreenSize == null ? Map.of() : Map.copyOf(boxesByScreenSize);
    }

    /**
     * Revisión R8: el job da PASS aunque falle alguna pantalla; Java compara pantallas × tamaños esperados con los PNG
     * recibidos y devuelve las claves que faltan, en orden.
     */
    public List<String> missing(Collection<String> screens) {
        var out = new ArrayList<String>();
        for (var screen : screens) {
            for (var size : SIZES) {
                if (!pngByScreenSize.containsKey(screen + "-" + size)) {
                    out.add(screen + "-" + size);
                }
            }
        }
        return out;
    }
}
