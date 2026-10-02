package com.aicompany.core.model;

/** Spec 2026-10-02 B1 §2: una ruta real del backend (método HTTP + plantilla normalizada). */
public record HttpRoute(String method, String template) {
}
