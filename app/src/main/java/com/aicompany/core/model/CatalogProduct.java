package com.aicompany.core.model;

import java.time.Instant;
import java.util.List;

/**
 * Producto o servicio del catálogo (spec 2026-09-28). kind SOFTWARE|SERVICE; markets/languages dónde se buscan clientes
 * (mundial por defecto); validatedBy = misiones de discovery que respaldan la demanda; builtBy = misiones que lo construyen.
 */
public record CatalogProduct(String id, String name, String description, String kind, String targetCustomer,
                             double priceUsd, boolean priceOnRequest, double estimatedCostUsd, String delivery,
                             List<String> markets, List<String> languages, CatalogStatus status,
                             CatalogStatus statusBeforePause, String createdBy, Instant createdAt, Instant updatedAt,
                             List<String> validatedBy, List<String> builtBy) {
}
