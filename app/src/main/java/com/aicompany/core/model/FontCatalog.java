package com.aicompany.core.model;

import java.util.List;
import java.util.Map;

/** Fuentes que la imagen del sandbox trae instaladas; el front solo puede elegir de aquí. */
public final class FontCatalog {

    public static final List<String> FAMILIES = List.of("Roboto", "Noto Sans", "Noto Serif");
    private static final String DIR = "/forjai/fonts/";
    private static final Map<String, String> PREFIX = Map.of("Roboto", "Roboto", "Noto Sans", "NotoSans", "Noto Serif", "NotoSerif");

    private FontCatalog() {
    }

    /** Rutas .ttf (regular y negrita) dentro de la imagen del sandbox; vacío si la familia no está en el catálogo. */
    public static List<String> files(String family) {
        var prefix = family == null ? null : PREFIX.get(family);
        return prefix == null ? List.of() : List.of(DIR + prefix + "-Regular.ttf", DIR + prefix + "-Bold.ttf");
    }
}
