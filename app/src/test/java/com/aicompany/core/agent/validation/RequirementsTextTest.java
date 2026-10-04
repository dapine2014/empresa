package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.agent.model.RequirementsSpec.*;
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
}
