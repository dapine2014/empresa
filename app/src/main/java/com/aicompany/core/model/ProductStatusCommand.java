package com.aicompany.core.model;

/** Cambio de estado desde el Command Center: status vacío = reanudar un producto pausado. */
public record ProductStatusCommand(String status, String reason) {
}
