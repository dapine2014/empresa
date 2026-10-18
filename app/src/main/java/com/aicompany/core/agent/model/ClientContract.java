package com.aicompany.core.agent.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Contrato (spec 2026-10-16 contrato §1): la solicitud del cliente que Aria levanta antes de escribir historias (hoy el
 * cliente es el fundador). Sin detalle: el detalle va en las HU, que salen de aquí. Cada ítem es un módulo, un sistema
 * base o, en un juego, un nivel o una escena; con useEpics cada ítem es una épica.
 */
public record ClientContract(String summary, String experience, List<Item> items,
                             @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> outOfScope,
                             Boolean useEpics, String epicsReason, Delivery delivery) {

    public static final List<String> PLATFORMS = List.of("WINDOWS", "MACOS", "LINUX", "ANDROID", "WEB", "DOCKER");

    public record Item(String id, String kind, String name, String description) {
    }

    /** Plataformas que el cliente pidió expresamente (opcional; sin él, se entregan las del perfil). */
    public record Delivery(List<String> platforms) {
        public List<String> platformsOrEmpty() {
            return platforms == null ? List.of() : platforms.stream().filter(Objects::nonNull).toList();
        }
    }

    public ClientContract(String summary, String experience, List<Item> items, List<String> outOfScope,
                          Boolean useEpics, String epicsReason) {
        this(summary, experience, items, outOfScope, useEpics, epicsReason, null);
    }

    public ClientContract {
        items = items == null ? List.of() : items;
        outOfScope = outOfScope == null ? List.of() : outOfScope;
    }

    public List<Item> itemsOrEmpty() {
        return items == null ? List.of() : items.stream().filter(Objects::nonNull).toList();
    }

    public List<String> outOfScopeOrEmpty() {
        return outOfScope == null ? List.of() : outOfScope.stream().filter(Objects::nonNull).toList();
    }

    public boolean epics() {
        return Boolean.TRUE.equals(useEpics);
    }

    public Delivery deliveryOrNull() {
        return delivery;
    }

    public RequirementsSpec.Experience experienceOrNull() {
        return RequirementsSpec.Experience.of(experience).orElse(null);
    }

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "summary", Map.of("type", "string"),
                    "experience", Map.of("type", "string", "enum", List.of("APP", "GAME", "NONE")),
                    "items", Map.of("type", "array", "items", Map.of("type", "object",
                            "properties", Map.of("id", Map.of("type", "string"),
                                    "kind", Map.of("type", "string", "enum", List.of("MODULE", "SYSTEM", "LEVEL", "SCENE")),
                                    "name", Map.of("type", "string"), "description", Map.of("type", "string")),
                            "required", List.of("id", "kind", "name", "description"))),
                    "outOfScope", Map.of("type", "array", "items", Map.of("type", "string")),
                    "useEpics", Map.of("type", "boolean"),
                    "epicsReason", Map.of("type", "string"),
                    "delivery", Map.of("type", "object", "properties", Map.of("platforms",
                            Map.of("type", "array", "items", Map.of("type", "string", "enum", PLATFORMS))))),
            "required", List.of("summary", "experience", "items", "outOfScope", "useEpics", "epicsReason"));
}
