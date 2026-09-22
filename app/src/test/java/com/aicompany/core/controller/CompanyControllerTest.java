package com.aicompany.core.controller;

import com.aicompany.core.model.ActivityItem;
import com.aicompany.core.model.AgentModelCommand;
import com.aicompany.core.model.AgentStatusResponse;
import com.aicompany.core.model.ChatRequest;
import com.aicompany.core.model.PolicyCommand;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.PolicySnapshot;
import com.aicompany.core.model.PromptCommand;
import com.aicompany.core.model.PromptSnapshot;
import com.aicompany.core.model.PromptVersionContent;
import com.aicompany.core.model.PromptVersionSummary;
import com.aicompany.core.model.SettingsCommand;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import com.aicompany.core.service.ActivityMemoryService;
import com.aicompany.core.service.ChatIntentRouter;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.CompanyPolicyService;
import com.aicompany.core.service.PromptMemoryService;
import com.aicompany.core.service.TeamMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyControllerTest {

    private final CompanyMemoryService memory = mock(CompanyMemoryService.class);
    private final MissionMemoryService missionMemory = mock(MissionMemoryService.class);
    private final ActivityMemoryService activityMemory = mock(ActivityMemoryService.class);
    private final ChatIntentRouter router = mock(ChatIntentRouter.class);
    private final TeamMemoryService teamMemory = mock(TeamMemoryService.class);
    private final PromptMemoryService promptMemory = mock(PromptMemoryService.class);
    private final CompanyPolicyService companyPolicyService = mock(CompanyPolicyService.class);

    private final CompanyController controller =
            new CompanyController(memory, missionMemory, activityMemory, router, teamMemory, promptMemory, companyPolicyService);

    @Test
    void teamsEndpointDelegatesEntirelyToTeamMemoryServiceSnapshotAll() {
        var engineering = new TeamSnapshot("TEAM-ENGINEERING", "Engineering Team", "ACTIVE", "engineering",
                List.of(new TeamMemberInfo("engineering", "Neo", "Cloud Architect & Lead Backend",
                        "CLOUD_ARCHITECT_LEAD_BACKEND", List.of("AWS"), "qwen3:8b")));
        when(teamMemory.snapshotAll()).thenReturn(List.of(engineering));

        var response = controller.teams();

        assertEquals(List.of(engineering), response);
    }

    @Test
    void agentPromptEndpointDelegatesEntirelyToPromptMemoryServiceSnapshot() {
        var snapshot = new PromptSnapshot("sales", 2, "Sé más directo.", "human", "Ajuste de tono", Instant.now(),
                List.of(new PromptVersionSummary(2, "human", "Ajuste de tono", Instant.now()),
                        new PromptVersionSummary(1, "human", "Versión inicial (seed)", Instant.now())));
        when(promptMemory.snapshot("sales")).thenReturn(snapshot);

        var response = controller.agentPrompt("sales");

        assertEquals(snapshot, response);
    }

    @Test
    void agentPromptVersionEndpointDelegatesEntirelyToPromptMemoryServiceVersionContent() {
        when(promptMemory.versionContent("sales", 1)).thenReturn("Versión vieja.");

        var response = controller.agentPromptVersion("sales", 1);

        assertEquals(1, response.version());
        assertEquals("Versión vieja.", response.content());
    }

    @Test
    void updateAgentPromptEndpointDelegatesEntirelyToPromptMemoryServiceCreateVersion() {
        var updated = new PromptSnapshot("sales", 3, "Sé más breve.", "human", "Otro ajuste", Instant.now(), List.of());
        when(promptMemory.createVersion("sales", "Sé más breve.", "Otro ajuste")).thenReturn(updated);

        var response = controller.updateAgentPrompt("sales", new PromptCommand("Sé más breve.", "Otro ajuste"));

        assertEquals(updated, response);
        verify(promptMemory).createVersion("sales", "Sé más breve.", "Otro ajuste");
    }

    @Test
    void activateAgentPromptVersionEndpointDelegatesEntirelyToPromptMemoryServiceActivateVersion() {
        var reactivated = new PromptSnapshot("sales", 1, "", "human", "Versión inicial (seed)", Instant.now(), List.of());
        when(promptMemory.activateVersion("sales", 1)).thenReturn(reactivated);

        var response = controller.activateAgentPromptVersion("sales", 1);

        assertEquals(reactivated, response);
        verify(promptMemory).activateVersion("sales", 1);
    }

    @Test
    void policiesEndpointDelegatesEntirelyToCompanyPolicyServiceSnapshotAll() {
        var snapshot = new PolicySnapshot(
                "SEED_CAPITAL_USD", 1, 50.0, "system", "Valor inicial de seed", Instant.now(), List.of());
        when(companyPolicyService.snapshotAll()).thenReturn(List.of(snapshot));

        var response = controller.policies();

        assertEquals(List.of(snapshot), response);
    }

    @Test
    void updatePolicyEndpointDelegatesEntirelyToCompanyPolicyServiceCreateVersion() {
        var updated = new PolicySnapshot(
                "SEED_CAPITAL_USD", 2, 200.0, "human", "Ronda de inversión", Instant.now(), List.of());
        when(companyPolicyService.createVersion(PolicyKey.SEED_CAPITAL_USD, 200.0, "Ronda de inversión"))
                .thenReturn(updated);

        var response = controller.updatePolicy("SEED_CAPITAL_USD", new PolicyCommand(200.0, "Ronda de inversión"));

        assertEquals(updated, response);
        verify(companyPolicyService).createVersion(PolicyKey.SEED_CAPITAL_USD, 200.0, "Ronda de inversión");
    }

    @Test
    void activatePolicyVersionEndpointDelegatesEntirelyToCompanyPolicyServiceActivateVersion() {
        var reactivated = new PolicySnapshot(
                "SEED_CAPITAL_USD", 1, 50.0, "system", "Valor inicial de seed", Instant.now(), List.of());
        when(companyPolicyService.activateVersion(PolicyKey.SEED_CAPITAL_USD, 1)).thenReturn(reactivated);

        var response = controller.activatePolicyVersion("SEED_CAPITAL_USD", 1);

        assertEquals(reactivated, response);
        verify(companyPolicyService).activateVersion(PolicyKey.SEED_CAPITAL_USD, 1);
    }

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

    @Test
    void updateAgentModelThrowsWhenAgentDoesNotExist() {
        doThrow(new IllegalArgumentException("No existe el agente ghost"))
                .when(memory).setAgentModel("ghost", "llama3:8b");

        assertThrows(IllegalArgumentException.class,
                () -> controller.updateAgentModel("ghost", new AgentModelCommand("llama3:8b")));
    }
}
