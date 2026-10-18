package com.aicompany.core.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DeliveryPathsTest {

    DeliveryPaths paths = new DeliveryPaths("/data/entregas-forja", "~/entregas-forja", "/data/forjai-products",
            "~/forjai-products/");

    @Test
    void mapsBothContainerRootsToTheFoundersPaths() {
        assertEquals("~/entregas-forja/M-1/incremento-3.zip", paths.display("/data/entregas-forja/M-1/incremento-3.zip"));
        assertEquals("~/forjai-products/M-1", paths.display("/data/forjai-products/M-1"));
        assertEquals("/otra/ruta", paths.display("/otra/ruta"));
    }
}
