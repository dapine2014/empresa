package com.aicompany.core.model;

/** Spec 2026-11-01 diseño visual §14: la decisión sobre una pantalla ({@code PANT-NN}); la nota es el porqué. */
public record DesignDecisionCommand(String screen, DesignDecision decision, String note) {
}
