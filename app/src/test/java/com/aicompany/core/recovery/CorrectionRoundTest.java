package com.aicompany.core.recovery;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrectionRoundTest {

    // Revisión 2026-10-06: las rondas guardadas antes de existir `failures` se leen con la lista vacía.
    @Test void anOldRoundWithoutFailuresReadsAsEmpty() {
        var json = "{\"round\":2,\"increment\":1,\"request\":\"r\",\"failing\":3,\"byFounder\":false}";
        var round = JsonMapper.builder().build().readValue(json, CorrectionRound.class);
        assertEquals(2, round.round());
        assertTrue(round.failures().isEmpty());
    }
}
