package com.aicompany.core.event;

import com.aicompany.core.model.MissionEventView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Bitácora (spec 2026-10-17): Neo planifica sabiendo lo que pasó en el incremento anterior. */
class MissionEventDigestTest {

    private static Instant t(int minute) {
        return Instant.parse("2026-10-17T15:00:00Z").plusSeconds(minute * 60L);
    }

    private static MissionEventView e(int minute, String type, String agent, String summary, Integer round, Integer inc) {
        return new MissionEventView("e" + minute, type, t(minute), null, agent, null, summary, round, inc);
    }

    @Test void summarizesRoundsRepeatedFailuresTriageAndRepairs() {
        var events = new ArrayList<>(List.of(
                e(0, "EMPRESA_INCREMENT_STARTED", "human", "INCREMENT STARTED por human", 11, 2),
                e(1, "EMPRESA_REPAIR_REQUESTED", "Vera", "Errores de compilación devueltos a Vera: 4 en ESC_07.cs", 0, null),
                e(2, "EMPRESA_MISSION_CORRECTION_STARTED", "Forjai", "Corrección automática ronda 12", 12, null),
                e(3, "EMPRESA_REPAIR_REQUESTED", "Vera", "Errores de compilación devueltos a Vera: 4 en ESC_07.cs", 0, null),
                e(4, "EMPRESA_REPAIR_REQUESTED", "Kael", "Errores de compilación devueltos a Kael: 1 en Main.cs", 0, null),
                e(5, "EMPRESA_TEST_TRIAGE_COMPLETED", "Neo", "Triage de Neo: ESC_04_x → TEST_WRONG", null, null),
                e(6, "EMPRESA_SANDBOX_VERIFICATION_COMPLETED", "sandbox", "Sandbox PASS: 25 tests", -1, null),
                e(7, "EMPRESA_INCREMENT_STARTED", "human", "INCREMENT STARTED por human", 16, 3),
                e(8, "EMPRESA_REPAIR_REQUESTED", "Iris", "otra cosa del incremento 3", 0, null)));
        java.util.Collections.reverse(events);   // la bitácora llega del más nuevo al más viejo

        var digest = MissionEventDigest.digest(events, 2);

        assertTrue(digest.startsWith(MissionEventDigest.HEADER + "2 (Java):"), digest);
        assertTrue(digest.contains("Rondas de corrección: 1 (12)"), digest);
        assertTrue(digest.contains("Resultado final: Sandbox PASS: 25 tests"), digest);
        assertTrue(digest.contains("2× Errores de compilación devueltos a Vera: 4 en ESC_07.cs"), digest);
        assertFalse(digest.contains("1× "), digest);
        assertTrue(digest.contains("Triage de Neo: ESC_04_x → TEST_WRONG"), digest);
        assertTrue(digest.contains("Reparaciones por agente: Vera 2, Kael 1"), digest);
        assertFalse(digest.contains("incremento 3") || digest.contains("Iris"), digest);
    }

    @Test void emptyWithoutEventsOfThatIncrement() {
        assertEquals("", MissionEventDigest.digest(List.of(), 2));
        assertEquals("", MissionEventDigest.digest(List.of(e(0, "EMPRESA_INCREMENT_STARTED", "human", "x", 1, 3)), 2));
    }
}
