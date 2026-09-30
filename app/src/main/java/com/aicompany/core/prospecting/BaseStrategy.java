package com.aicompany.core.prospecting;

import java.util.Arrays;
import java.util.List;

/** Spec búsqueda de prospectos §1: catálogo base fijo (el orden es el de prueba de las no usadas). */
public enum BaseStrategy {
    DIRECTORIES("Directorios del rubro",
            "Directorios, asociaciones y cámaras de empresas del rubro del cliente objetivo.",
            "directorio de empresas, asociación, miembros, listado de proveedores"),
    COMMUNITIES("Comunidades con el problema",
            "Foros, comunidades y grupos donde el cliente objetivo pide ayuda o sufre el problema que resuelve el producto.",
            "foro, comunidad, grupo, pregunta, recomendación, alguien sabe"),
    COMPETITOR_CUSTOMERS("Clientes de competidores",
            "Empresas que aparecen como clientes de competidores: casos de éxito, testimonios, reseñas.",
            "caso de éxito, testimonio, cliente de, reseña, review"),
    NICHE_LISTS("Listas del nicho",
            "Listas públicas del nicho: rankings, 'top N', listados de empresas o creadores.",
            "top, ranking, lista de, mejores, best");

    private final String label;
    private final String description;
    private final String hints;

    BaseStrategy(String label, String description, String hints) {
        this.label = label;
        this.description = description;
        this.hints = hints;
    }

    public StrategyOption option() {
        return new StrategyOption("BASE-" + name(), label, description, hints);
    }

    public static List<StrategyOption> options() {
        return Arrays.stream(values()).map(BaseStrategy::option).toList();
    }
}
