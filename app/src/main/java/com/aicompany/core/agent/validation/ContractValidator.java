package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Contrato (spec 2026-10-16 contrato §1): reglas de Java sobre el contrato de Aria (lista vacía = válido). */
public final class ContractValidator {

    public static final int MAX_ITEMS = 10;
    private static final List<String> APP_KINDS = List.of("MODULE", "SYSTEM");
    private static final List<String> GAME_KINDS = List.of("LEVEL", "SCENE", "SYSTEM");
    private static final Set<String> PLAYABLE = Set.of("LEVEL", "SCENE");

    private ContractValidator() {
    }

    public static List<String> validate(ClientContract contract) {
        if (contract == null) {
            return List.of("El contrato está vacío.");
        }
        var errors = new ArrayList<String>();
        if (contract.summary() == null || contract.summary().isBlank()) {
            errors.add("summary debe decir en 2 o 3 frases qué pide el cliente.");
        }
        var experience = contract.experienceOrNull();
        if (experience == null) {
            errors.add("experience debe ser APP (el producto tiene pantallas), GAME (es un juego) o NONE (solo API o "
                    + "servicio, sin ninguna interfaz); recibido: \"" + contract.experience() + "\".");
        }
        var items = contract.itemsOrEmpty();
        if (items.isEmpty()) {
            errors.add("items debe listar lo que pidió el cliente: módulos (MOD-01, MOD-02…) o, en un juego, sus niveles "
                    + "y escenas.");
        }
        if (items.size() > MAX_ITEMS) {
            errors.add("Hay " + items.size() + " ítems: máximo " + MAX_ITEMS + ".");
        }
        var kinds = experience == RequirementsSpec.Experience.GAME ? GAME_KINDS : APP_KINDS;
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var expected = "MOD-%02d".formatted(i + 1);
            if (!expected.equals(item.id())) {
                errors.add("El ítem " + (i + 1) + " debe tener id " + expected + " (tiene " + item.id()
                        + "): los ids van en orden.");
            }
            if (experience != null && !kinds.contains(item.kind())) {
                errors.add(item.id() + ": kind debe ser " + String.join(" o ", kinds) + " en " + experience.name()
                        + "; recibido: \"" + item.kind() + "\".");
            }
            if (item.name() == null || item.name().isBlank()) {
                errors.add(item.id() + " necesita name.");
            }
        }
        if (contract.useEpics() == null) {
            errors.add("useEpics debe ser true (lleva épicas) o false (no las necesita).");
        }
        if (contract.epicsReason() == null || contract.epicsReason().isBlank()) {
            errors.add("epicsReason debe explicar por qué lleva épicas o por qué no.");
        }
        if (experience == RequirementsSpec.Experience.GAME) {
            if (items.stream().noneMatch(i -> "LEVEL".equals(i.kind()))) {
                errors.add("Un juego necesita al menos un ítem LEVEL (un nivel jugable).");
            }
            var playable = items.stream().filter(i -> PLAYABLE.contains(i.kind())).count();
            if (playable >= 2 && !contract.epics()) {
                errors.add("Un juego con varios niveles o escenas lleva épicas (useEpics: true): cada nivel o escena es "
                        + "una épica.");
            }
        }
        return errors;
    }
}
