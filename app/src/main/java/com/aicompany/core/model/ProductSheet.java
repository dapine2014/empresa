package com.aicompany.core.model;

import java.util.List;

/** Ficha que completa Alex antes de construir (Java valida y la aplica con actor orchestrator). */
public record ProductSheet(String kind, String targetCustomer, List<String> markets, List<String> languages,
                           Double priceUsd, Boolean priceOnRequest, Double estimatedCostUsd, String delivery,
                           String name, String description) {
}
