package com.aicompany.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** La lista de rutas de la SPA es explícita (ver SpaController): cada pantalla nueva tiene que estar. */
class SpaControllerTest {

    @Test
    void theFinancePageIsServedByTheSpa() throws Exception {
        var mapping = SpaController.class.getMethod("forwardToSpa").getAnnotation(RequestMapping.class);

        assertTrue(List.of(mapping.value()).contains("/finanzas"), List.of(mapping.value()).toString());
        assertTrue(List.of(mapping.value()).contains("/dependencias"), List.of(mapping.value()).toString());
        assertTrue(List.of(mapping.value()).contains("/productos"), List.of(mapping.value()).toString());
    }
}
