package com.aicompany.core.model;

/** Lo que Neo4j sabe de las misiones de un producto: demanda con evidencia web y construcción verificada. */
public record ProductEvidence(boolean demandWithWebEvidence, boolean buildVerified) {
}
