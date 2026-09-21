package com.aicompany.core.controller;

import com.aicompany.core.model.ActivityItem;
import com.aicompany.core.model.AgentModelCommand;
import com.aicompany.core.model.AgentStatusResponse;
import com.aicompany.core.model.ChatRequest;
import com.aicompany.core.model.SettingsCommand;
import com.aicompany.core.service.ActivityMemoryService;
import com.aicompany.core.service.ChatIntentRouter;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyControllerTest {

    private final CompanyMemoryService memory = mock(CompanyMemoryService.class);
    private final MissionMemoryService missionMemory = mock(MissionMemoryService.class);
    private final ActivityMemoryService activityMemory = mock(ActivityMemoryService.class);
    private final ChatIntentRouter router = mock(ChatIntentRouter.class);

    private final CompanyController controller =
            new CompanyController(memory, missionMemory, activityMemory, router);

    @Test
    void chatDelegatesEntirelyToTheIntentRouter() {
        when(router.route("¿Cuál es el siguiente paso?")).thenReturn("Validar demanda.");

        var response = controller.chat(new ChatRequest("¿Cuál es el siguiente paso?"));

        assertEquals("CEO", response.agent());
        assertEquals("Validar demanda.", response.response());
        verify(router).route("¿Cuál es el siguiente paso?");
    }

    @Test
    void agentStatusDelegatesToMissionMemoryService() {
        var statuses = List.of(new AgentStatusResponse("sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados", "WORKING", "MISSION-1", "MARKET_DISCOVERY", "RUNNING", Instant.now()));
        when(missionMemory.latestTaskPerAgent()).thenReturn(statuses);

        assertEquals(statuses, controller.agentStatus());
    }

    @Test
    void activityDelegatesToActivityMemoryServiceWithDefaultLimit() {
        var items = List.of(new ActivityItem("MISSION", "MISSION-1", null, "desc", Instant.now()));
        when(activityMemory.recent(50)).thenReturn(items);

        assertEquals(items, controller.activity(50));
        verify(activityMemory).recent(50);
    }

    @Test
    void settingsReturnsTheConfiguredAlertAndSystemEmails() {
        when(memory.alertEmail()).thenReturn("dapine@gmail.com");
        when(memory.systemEmail()).thenReturn("ai-company@gmail.com");

        var response = controller.settings();

        assertEquals("dapine@gmail.com", response.alertEmail());
        assertEquals("ai-company@gmail.com", response.systemEmail());
    }

    @Test
    void updateSettingsPersistsTheNewAlertEmail() {
        var response = controller.updateSettings(
                new SettingsCommand("nuevo@ejemplo.com", null, null));

        verify(memory).setAlertEmail("nuevo@ejemplo.com");
        assertEquals("nuevo@ejemplo.com", response.alertEmail());
    }

    @Test
    void updateSettingsPersistsTheSystemEmailAndPasswordWhenProvided() {
        controller.updateSettings(
                new SettingsCommand("dapine@gmail.com", "ai-company@gmail.com", "app-password-secreta"));

        verify(memory).setSystemEmail("ai-company@gmail.com");
        verify(memory).setMailPassword("app-password-secreta");
    }

    @Test
    void updateSettingsDoesNotOverwriteTheSystemEmailOrPasswordWhenOmitted() {
        controller.updateSettings(new SettingsCommand("dapine@gmail.com", null, ""));

        verify(memory, never()).setSystemEmail(any());
        verify(memory, never()).setMailPassword(any());
    }

    @Test
    void updateAgentModelDelegatesToCompanyMemoryService() {

        var response = controller.updateAgentModel("engineering", new AgentModelCommand("llama3:8b"));

        verify(memory).setAgentModel("engineering", "llama3:8b");
        assertEquals("llama3:8b", response.model());
    }
}
