package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Contrato (spec 2026-10-16 contrato §1): lo que ve el fundador y lo que recibe Aria. */
class ContractTextTest {

    @Test void theSummaryShowsEveryItemWithItsKind() {
        var text = ContractText.summary(ContractValidatorTest.PONG, 1);
        assertTrue(text.startsWith("Contrato (versión 1, juego): Un Pong de 3 niveles."), text);
        assertTrue(text.contains("\nMOD-01 [Sistema] Controles y puntaje — dos paletas"), text);
        assertTrue(text.contains("\nMOD-03 [Nivel] Nivel 2 — la pelota aumenta de velocidad"), text);
        assertTrue(text.contains("\nFuera de alcance: sonido · Épicas: sí (un nivel por épica)."), text);
    }

    @Test void aSmallAppSaysItHasNoEpics() {
        var text = ContractText.summary(ContractValidatorTest.FORM, 2);
        assertTrue(text.startsWith("Contrato (versión 2, aplicación):"), text);
        assertTrue(text.contains("Fuera de alcance: (nada declarado) · Épicas: no (una app pequeña de un solo módulo)."), text);
    }

    @Test void thePromptListsTheItems() {
        var text = ContractText.forPrompt(ContractValidatorTest.PONG);
        assertTrue(text.contains("- MOD-02 [Nivel] Nivel 1: pelota normal hasta 50 puntos"), text);
        assertTrue(text.contains("Épicas: sí"), text);
        assertTrue(text.contains("Tipo: GAME"), text);
    }
}
