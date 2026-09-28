package com.aicompany.core.service;

import com.aicompany.core.model.CatalogProduct;
import com.aicompany.core.model.ProductEvidence;

import java.util.ArrayList;
import java.util.List;

/**
 * Requisitos para "listo para vender" (spec catálogo 2026-09-28, decisión del fundador): los verifica Java, nunca el
 * modelo. Lista vacía = listo; cada ítem es una frase que ven el fundador (pantalla y chat) y el historial.
 */
public final class ProductReadiness {

    private ProductReadiness() {
    }

    public static List<String> missing(CatalogProduct p, ProductEvidence e) {
        var out = new ArrayList<String>();
        var hasTarget = p.targetCustomer() != null && !p.targetCustomer().isBlank();
        var hasPrice = p.priceOnRequest() || p.priceUsd() > 0;
        if (!hasTarget && !hasPrice) {
            out.add("Falta el cliente objetivo y el precio (o marcarlo a cotizar).");
        } else if (!hasTarget) {
            out.add("Falta el cliente objetivo.");
        } else if (!hasPrice) {
            out.add("Falta el precio (o marcarlo a cotizar).");
        }
        if (!e.demandWithWebEvidence()) {
            out.add("Falta una misión de discovery asociada con evidencia web de demanda.");
        }
        if ("SERVICE".equals(p.kind())) {
            if (p.delivery() == null || p.delivery().isBlank()) {
                out.add("Falta describir cómo se entrega el servicio.");
            }
        } else if (!e.buildVerified()) {
            out.add("Falta una misión de construcción asociada con su validación en VERIFIED.");
        }
        if (!p.priceOnRequest() && p.priceUsd() <= p.estimatedCostUsd()) {
            out.add("El margen estimado no es positivo: el precio debe ser mayor que el costo estimado por venta.");
        }
        return out;
    }
}
