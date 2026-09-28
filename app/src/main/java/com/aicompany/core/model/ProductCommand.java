package com.aicompany.core.model;

import java.util.List;

/** Crear o editar un producto (spec catálogo 2026-09-28). En una edición, null = no cambiar. */
public record ProductCommand(String name, String description, String kind, String targetCustomer, Double priceUsd,
                             Boolean priceOnRequest, Double estimatedCostUsd, String delivery, List<String> markets,
                             List<String> languages, String reason) {
}
