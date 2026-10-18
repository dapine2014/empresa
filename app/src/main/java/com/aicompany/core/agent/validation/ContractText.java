package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.stream.Collectors;

/** Contrato (spec 2026-10-16 contrato §1): el contrato como texto, armado por Java. */
public final class ContractText {

    private ContractText() {
    }

    public static String kindLabel(String kind) {
        if (kind == null) {
            return "";
        }
        return switch (kind) {
            case "MODULE" -> "Módulo";
            case "SYSTEM" -> "Sistema";
            case "LEVEL" -> "Nivel";
            case "SCENE" -> "Escena";
            default -> kind;
        };
    }

    private static String experienceLabel(RequirementsSpec.Experience experience) {
        if (experience == null) {
            return "sin tipo";
        }
        return switch (experience) {
            case APP -> "aplicación";
            case GAME -> "juego";
            case NONE -> "servicio sin interfaz";
        };
    }

    /** Lo que ve el fundador (mensaje de la espera, chat): un ítem por línea. */
    public static String summary(ClientContract c, int version) {
        var items = c.itemsOrEmpty().stream()
                .map(i -> i.id() + " [" + kindLabel(i.kind()) + "] " + i.name() + " — " + i.description())
                .collect(Collectors.joining("\n"));
        return "Contrato (versión " + version + ", " + experienceLabel(c.experienceOrNull()) + "): " + c.summary()
                + "\n" + items
                + "\nFuera de alcance: " + (c.outOfScopeOrEmpty().isEmpty() ? "(nada declarado)"
                : String.join("; ", c.outOfScopeOrEmpty()))
                + " · Épicas: " + (c.epics() ? "sí" : "no") + " (" + c.epicsReason() + ").";
    }

    /** Lo que recibe Aria al escribir las HU. */
    public static String forPrompt(ClientContract c) {
        return "Tipo: " + c.experience() + "\n" + c.summary() + "\n"
                + c.itemsOrEmpty().stream().map(i -> "- " + i.id() + " [" + kindLabel(i.kind()) + "] " + i.name() + ": "
                        + i.description()).collect(Collectors.joining("\n"))
                + "\nFuera de alcance: " + (c.outOfScopeOrEmpty().isEmpty() ? "(nada declarado)"
                : String.join("; ", c.outOfScopeOrEmpty()))
                + "\nÉpicas: " + (c.epics() ? "sí (cada ítem agrupa sus historias)" : "no") + "\n";
    }
}
