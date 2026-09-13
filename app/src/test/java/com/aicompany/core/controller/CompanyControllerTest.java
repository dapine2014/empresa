package com.aicompany.core.controller;

import com.aicompany.core.model.ChatRequest;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyControllerTest {

    @Test
    void startsMissionWhenChatContainsMissionInstruction() {
        var ceo = mock(CeoService.class);
        var memory = mock(CompanyMemoryService.class);
        var missions = mock(MissionService.class);
        var mission = new MissionResponse(
                "MISSION-42", MissionStatus.CREATED, 0,
                "Creada", "Misión recibida", Instant.parse("2026-09-12T00:00:00Z")
        );
        when(missions.start("MISSION-42", "Inicia mission-42 para investigar."))
                .thenReturn(mission);

        var response = new CompanyController(ceo, memory, missions)
                .chat(new ChatRequest("Inicia mission-42 para investigar."));

        assertEquals("CEO", response.agent());
        assertEquals(true, response.response().contains("MISSION-42"));
        verify(missions).start("MISSION-42", "Inicia mission-42 para investigar.");
    }

    @Test
    void sendsRegularChatToCeoWithoutStartingMission() {
        var ceo = mock(CeoService.class);
        var memory = mock(CompanyMemoryService.class);
        var missions = mock(MissionService.class);
        when(ceo.chat("¿Cuál es el siguiente paso?")).thenReturn("Validar demanda.");

        var response = new CompanyController(ceo, memory, missions)
                .chat(new ChatRequest("¿Cuál es el siguiente paso?"));

        assertEquals("Validar demanda.", response.response());
        verify(ceo).chat("¿Cuál es el siguiente paso?");
    }
}
