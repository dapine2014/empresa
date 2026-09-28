package com.aicompany.core.service;

import com.aicompany.core.model.CatalogProduct;
import com.aicompany.core.model.CatalogStatus;
import com.aicompany.core.model.ProductEvidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec catálogo (2026-09-28): los 4 requisitos para "listo para vender", verificados en Java. */
class ProductReadinessTest {

    private static CatalogProduct product(String kind, String target, double price, boolean onRequest, double cost, String delivery) {
        return new CatalogProduct("P1", "Landing", "d", kind, target, price, onRequest, cost, delivery, List.of("WORLDWIDE"),
                List.of("en", "es"), CatalogStatus.IN_CONSTRUCTION, null, "human", Instant.now(), Instant.now(), List.of(), List.of());
    }

    @Test
    void aCompleteSoftwareProductIsReady() {
        assertEquals(List.of(), ProductReadiness.missing(product("SOFTWARE", "Pymes", 120, false, 10, null),
                new ProductEvidence(true, true)));
    }

    @Test
    void eachMissingRequirementIsListed() {
        var missing = ProductReadiness.missing(product("SOFTWARE", " ", 0, false, 0, null), new ProductEvidence(false, false));
        assertEquals(4, missing.size(), missing.toString());
        assertTrue(missing.get(0).contains("cliente objetivo") && missing.get(0).contains("precio"), missing.get(0));
        assertTrue(missing.stream().anyMatch(m -> m.contains("evidencia web")));
        assertTrue(missing.stream().anyMatch(m -> m.contains("VERIFIED")));
        assertTrue(missing.stream().anyMatch(m -> m.contains("margen")));
    }

    @Test
    void aServiceNeedsDeliveryInsteadOfAVerifiedBuild() {
        assertEquals(List.of(), ProductReadiness.missing(product("SERVICE", "Pymes", 50, false, 5, "Llamada + informe"),
                new ProductEvidence(true, false)));
        assertTrue(ProductReadiness.missing(product("SERVICE", "Pymes", 50, false, 5, ""), new ProductEvidence(true, true))
                .stream().anyMatch(m -> m.contains("cómo se entrega")));
    }

    @Test
    void priceOnRequestSatisfiesPriceAndMargin() {
        assertEquals(List.of(), ProductReadiness.missing(product("SOFTWARE", "Pymes", 0, true, 30, null),
                new ProductEvidence(true, true)));
    }

    @Test
    void aZeroOrNegativeMarginIsNotReady() {
        assertTrue(ProductReadiness.missing(product("SOFTWARE", "Pymes", 10, false, 10, null), new ProductEvidence(true, true))
                .stream().anyMatch(m -> m.contains("margen")));
    }
}
