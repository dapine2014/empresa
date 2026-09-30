package com.aicompany.core.prospecting;

/** Una estrategia de búsqueda de prospectos: del catálogo base o aprobada por el fundador. */
public record StrategyOption(String id, String name, String description, String searchHints) {
}
