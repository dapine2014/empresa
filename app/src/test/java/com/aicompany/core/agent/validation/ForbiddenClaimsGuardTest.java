package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ForbiddenClaimsGuardTest {

    private final ForbiddenClaimsGuard guard = new ForbiddenClaimsGuard();

    @Test
    void flagsAffirmativeExecutionClaims() {
        assertFalse(guard.violations(List.of("El juego funciona correctamente.")).isEmpty());
        assertFalse(guard.violations(List.of("El proyecto compila sin errores")).isEmpty());
        assertFalse(guard.violations(List.of("Todo pasa los tests.")).isEmpty());
    }

    @Test
    void allowsNegatedStatementsAboutWhatCannotBeVerified() {
        assertEquals(List.of(), guard.violations(List.of("No se puede afirmar que el juego funciona sin ejecutarlo.")));
        assertEquals(List.of(), guard.violations(List.of("Sin ejecución no sabemos si compila.")));
    }

    @Test
    void doesNotConfuseRelatedWords() {
        assertEquals(List.of(), guard.violations(List.of("La funcionalidad del HUD está separada de la lógica.")));
        assertEquals(List.of(), guard.violations(List.of("Falta un script de compilación.")));
    }

    @Test
    void checksEachSentenceSeparately() {
        var violations = guard.violations(List.of("No hay tests. El juego funciona."));
        assertEquals(1, violations.size());
    }
}
