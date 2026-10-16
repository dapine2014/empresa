package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.agent.model.RequirementsSpec.*;
import com.aicompany.core.agent.model.ClientContract;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequirementsTextTest {

    private static final RequirementsSpec SPEC = new RequirementsSpec("CRM simple", List.of("Un cliente tiene varias oportunidades"),
            List.of("Reportes"), List.of(new Role("vendedor", "registra clientes")),
            List.of(new UserStory("HU-01", "vendedor", "registrar un cliente", "seguirlo", List.of("nombre obligatorio"),
                            List.of(), List.of(new Scenario("ESC-01", "alta válida", List.of("un vendedor"),
                                    List.of("registra un cliente con nombre"), List.of("el cliente queda guardado")))),
                    new UserStory("HU-02", "vendedor", "registrar una oportunidad", "venderle", List.of("monto > 0"),
                            List.of("HU-01"), List.of(new Scenario("ESC-02", "oportunidad", List.of("un cliente"),
                            List.of("crea una oportunidad"), List.of("queda abierta"))))),
            List.of(new Increment(1, "MVP: clientes", List.of("HU-01")), new Increment(2, "Oportunidades", List.of("HU-02"))));

    // Spec 2026-10-03 Aria §3: Neo y el equipo construyen solo el incremento; el resto queda explícitamente afuera.
    @Test
    void theIncrementBlockHasOnlyItsStoriesAndListsTheRestAsOut() {
        var block = RequirementsText.incrementBlock(SPEC, 1);
        assertTrue(block.contains("INCREMENTO 1 de 2"), block);
        assertTrue(block.contains("HU-01 (vendedor): quiero registrar un cliente para seguirlo"), block);
        assertTrue(block.contains("ESC-01 alta válida: DADO un vendedor CUANDO registra un cliente con nombre ENTONCES el cliente queda guardado"), block);
        assertFalse(block.contains("HU-02 (vendedor)"), block);
        assertTrue(block.contains("NO construyas en este incremento: HU-02"), block);
        assertTrue(block.contains("Reportes"), block);
    }

    @Test
    void theSummaryShowsRolesStoriesPerIncrementAssumptionsAndOutOfScope() {
        var summary = RequirementsText.summary(SPEC);
        assertTrue(summary.contains("Roles: vendedor"), summary);
        assertTrue(summary.contains("Incremento 1 (MVP: clientes): HU-01 registrar un cliente"), summary);
        assertTrue(summary.contains("Supuestos: Un cliente tiene varias oportunidades"), summary);
        assertTrue(summary.contains("Fuera de alcance: Reportes"), summary);
    }

    @Test
    void theIncrementBlockCarriesTheExperienceLine() {
        var game = new RequirementsSpec(SPEC.summary(), SPEC.assumptions(), SPEC.outOfScope(), SPEC.roles(),
                SPEC.userStories(), SPEC.increments(), "GAME");
        assertTrue(RequirementsText.incrementBlock(game, 1).contains("TIPO DE EXPERIENCIA: GAME"));
        assertTrue(RequirementsText.summary(game).contains("Tipo: GAME"));
        assertFalse(RequirementsText.incrementBlock(SPEC, 1).contains("TIPO DE EXPERIENCIA"));
    }

    private static final RequirementsSpec PONG = new RequirementsSpec("Pong", List.of(), List.of(),
            List.of(new Role("jugador", "juega")),
            List.of(new UserStory("HU-01", "jugador", "mover la paleta", "devolver", List.of("c"), List.of(),
                            List.of(new Scenario("ESC-01", "mueve", List.of("d"), List.of("c"), List.of("e"))), "MOD-01"),
                    new UserStory("HU-02", "jugador", "jugar hasta 50 puntos", "ganar", List.of("c"), List.of(),
                            List.of(new Scenario("ESC-02", "punto", List.of("d"), List.of("c"), List.of("e"))), "MOD-02"),
                    new UserStory("HU-03", "jugador", "que la pelota acelere", "un reto", List.of("c"), List.of(),
                            List.of(new Scenario("ESC-03", "rápida", List.of("d"), List.of("c"), List.of("e"))), "MOD-03")),
            List.of(new Increment(1, "Nivel 1 jugable", List.of("HU-01", "HU-02")),
                    new Increment(2, "Más velocidad", List.of("HU-03"))), "GAME",
            List.of(new ClientContract.Item("MOD-01", "SYSTEM", "Controles", "paletas"),
                    new ClientContract.Item("MOD-02", "LEVEL", "Nivel 1", "pelota normal hasta 50 puntos"),
                    new ClientContract.Item("MOD-03", "LEVEL", "Nivel 2", "la pelota aumenta de velocidad")), true);

    // Contrato (spec 2026-10-16 contrato §2): con épicas, el fundador y el equipo ven las HU agrupadas por ítem.
    @Test
    void theSummaryGroupsTheStoriesByEpic() {
        var summary = RequirementsText.summary(PONG);
        assertTrue(summary.contains("Incremento 1 (Nivel 1 jugable): Épica MOD-01 Controles — HU-01 mover la paleta"
                + " · Épica MOD-02 Nivel 1 — HU-02 jugar hasta 50 puntos"), summary);
        assertTrue(summary.contains("Incremento 2 (Más velocidad): Épica MOD-03 Nivel 2 — HU-03 que la pelota acelere"), summary);
    }

    @Test
    void theIncrementBlockListsItsEpics() {
        var block = RequirementsText.incrementBlock(PONG, 2);
        assertTrue(block.contains("Épicas de este incremento:\n- MOD-03 [Nivel] Nivel 2: la pelota aumenta de velocidad — HU-03"), block);
        assertFalse(block.contains("- MOD-02 [Nivel]"), block);
    }

    // Review Focus 3: sin épicas (app pequeña o misión vieja) los textos no cambian.
    @Test
    void withoutEpicsTheTextsDoNotChange() {
        assertFalse(RequirementsText.summary(SPEC).contains("Épica"), RequirementsText.summary(SPEC));
        assertFalse(RequirementsText.incrementBlock(SPEC, 1).contains("Épicas de este incremento"));
    }
}
