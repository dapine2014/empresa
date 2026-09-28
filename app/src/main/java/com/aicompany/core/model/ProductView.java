package com.aicompany.core.model;

import java.util.List;

/** Producto con lo que le falta para venderse (vacío = cumple) y su historial. */
public record ProductView(CatalogProduct product, List<String> missing, List<ProductChange> history) {
}
