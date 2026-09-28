package com.aicompany.core.model;

import java.time.Instant;

/** Historial inmutable de un producto: quién (human, un agentId o system), qué campo, de qué a qué y por qué. */
public record ProductChange(String actor, String field, String from, String to, String reason, Instant at) {
}
