package com.aicompany.core.model;

/**
 * Spec 2026-11-01 front con diseño visual: la caja de un elemento capturado ({@code data-id} en una maqueta,
 * {@code flt-semantics-identifier} en la app), en píxeles CSS; {@code color} = fondo dominante #RRGGBB o null.
 */
public record Box(String id, int x, int y, int w, int h, String color) {
}
