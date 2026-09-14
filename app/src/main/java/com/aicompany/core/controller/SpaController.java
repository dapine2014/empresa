package com.aicompany.core.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Command Center web: la SPA de React usa client-side routing
 * (react-router) para {@code /chat}, {@code /agents}, {@code /missions},
 * {@code /missions/{id}}, {@code /activity} — rutas que solo existen en
 * el navegador, no en el backend. Sin este forward, cargar (o refrescar)
 * cualquiera de esas URLs directamente devuelve 404 de Spring Boot (el
 * handler de recursos estáticos solo conoce {@code index.html}, no las
 * rutas de React) — reproducido en vivo antes de este fix.
 *
 * <p>Deliberadamente una lista explícita de rutas, no un comodín genérico
 * tipo {@code "/{path:[^.]*}"}: un comodín así también atrapa cualquier
 * {@code /api/**} mal escrito o inexistente (sin punto en el último
 * segmento) y lo reenvía a {@code index.html} con 200 en vez de 404 —
 * reproducido en vivo antes de este fix (`/api/company/no-existe`
 * devolvía el HTML de la SPA). Mantener esta lista sincronizada con las
 * rutas de {@code app/frontend/src/App.tsx}.
 */
@Controller
public class SpaController {

    @RequestMapping(value = {
            "/",
            "/chat",
            "/agents",
            "/missions",
            "/missions/{missionId}",
            "/activity"
    })
    public String forwardToSpa() {
        return "forward:/index.html";
    }
}
