package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.ClientContract.Item;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Contrato (spec 2026-10-16 contrato §1): Java valida el contrato que levanta Aria. */
class ContractValidatorTest {

    static final ClientContract PONG = new ClientContract("Un Pong de 3 niveles.", "GAME",
            List.of(new Item("MOD-01", "SYSTEM", "Controles y puntaje", "dos paletas"),
                    new Item("MOD-02", "LEVEL", "Nivel 1", "pelota normal hasta 50 puntos"),
                    new Item("MOD-03", "LEVEL", "Nivel 2", "la pelota aumenta de velocidad"),
                    new Item("MOD-04", "LEVEL", "Nivel 3", "dos pelotas")),
            List.of("sonido"), true, "un nivel por épica");

    static final ClientContract FORM = new ClientContract("Un formulario de contacto.", "APP",
            List.of(new Item("MOD-01", "MODULE", "Contacto", "nombre, correo y mensaje")),
            List.of(), false, "una app pequeña de un solo módulo");

    @Test void aValidGameAndASmallAppPass() {
        assertEquals(List.of(), ContractValidator.validate(PONG));
        assertEquals(List.of(), ContractValidator.validate(FORM));
    }

    @Test void requiredFieldsAndExperience() {
        var errors = String.join("\n", ContractValidator.validate(new ClientContract(" ", "WEB", List.of(), List.of(),
                null, "")));
        assertTrue(errors.contains("summary"), errors);
        assertTrue(errors.contains("experience"), errors);
        assertTrue(errors.contains("items debe listar"), errors);
        assertTrue(errors.contains("useEpics"), errors);
        assertTrue(errors.contains("epicsReason"), errors);
    }

    @Test void idsGoInOrderAndKindsDependOnTheExperience() {
        var errors = String.join("\n", ContractValidator.validate(new ClientContract("x", "APP",
                List.of(new Item("MOD-02", "MODULE", "Ventas", "x"), new Item("MOD-01", "LEVEL", "Nivel", "x"),
                        new Item("MOD-03", "MODULE", " ", "x")), List.of(), true, "módulos")));
        assertTrue(errors.contains("El ítem 1 debe tener id MOD-01 (tiene MOD-02)"), errors);
        assertTrue(errors.contains("MOD-01: kind debe ser MODULE o SYSTEM en APP; recibido: \"LEVEL\""), errors);
        assertTrue(errors.contains("MOD-03 necesita name"), errors);
    }

    @Test void aGameNeedsALevelAndEpicsWhenItHasSeveralLevelsOrScenes() {
        var noLevel = String.join("\n", ContractValidator.validate(new ClientContract("x", "GAME",
                List.of(new Item("MOD-01", "SCENE", "Menú", "x")), List.of(), false, "uno")));
        assertTrue(noLevel.contains("al menos un ítem LEVEL"), noLevel);
        var noEpics = String.join("\n", ContractValidator.validate(new ClientContract(PONG.summary(), "GAME",
                PONG.items(), List.of(), false, "no hace falta")));
        assertTrue(noEpics.contains("useEpics: true"), noEpics);
        var oneLevel = new ClientContract("x", "GAME", List.of(new Item("MOD-01", "LEVEL", "Nivel", "x")), List.of(),
                false, "un solo nivel");
        assertEquals(List.of(), ContractValidator.validate(oneLevel));
    }

    @Test void atMostTenItems() {
        var items = new ArrayList<Item>();
        for (int i = 1; i <= 11; i++) {
            items.add(new Item("MOD-%02d".formatted(i), "MODULE", "M" + i, "x"));
        }
        var errors = ContractValidator.validate(new ClientContract("x", "APP", items, List.of(), true, "módulos"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("máximo 10")), errors.toString());
    }

    @Test void nullKindProducesErrorNotException() {
        var errors = String.join("\n", ContractValidator.validate(new ClientContract("x", "APP",
                List.of(new Item("MOD-01", null, "Módulo", "x")), List.of(), true, "módulos")));
        assertTrue(errors.contains("kind debe ser"), errors);
    }
}
