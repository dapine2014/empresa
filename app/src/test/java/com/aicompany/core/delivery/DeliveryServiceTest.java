package com.aicompany.core.delivery;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.SandboxResult;
import com.aicompany.core.service.ContractMemoryService;
import com.aicompany.core.service.DevelopmentWorkspaceService;
import com.aicompany.core.service.InteractionDesignMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import com.aicompany.core.service.ModelCostService;
import com.aicompany.core.service.RequirementsMemoryService;
import com.aicompany.core.service.SandboxRunnerClient;
import com.aicompany.core.database.DatabaseConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryServiceTest {

    static final String SHA = "a".repeat(40);
    DeliveryMemoryService deliveries = mock(DeliveryMemoryService.class);
    SandboxRunnerClient runner = mock(SandboxRunnerClient.class);
    DeliveryPackager packager = mock(DeliveryPackager.class);
    AndroidSigningKeyService keys = mock(AndroidSigningKeyService.class);
    CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    MissionMemoryService missions = mock(MissionMemoryService.class);
    RequirementsMemoryService requirements = mock(RequirementsMemoryService.class);
    ContractMemoryService contracts = mock(ContractMemoryService.class);
    InteractionDesignMemoryService designs = mock(InteractionDesignMemoryService.class);
    DatabaseConnectionService dbs = mock(DatabaseConnectionService.class);
    ModelCostService cost = mock(ModelCostService.class);
    DevelopmentWorkspaceService workspace = mock(DevelopmentWorkspaceService.class);
    DeliveryService service;

    @BeforeEach
    void setUp() throws Exception {
        var json = JsonMapper.builder().build();
        var builder = new DeliveryFactsBuilder(requirements, contracts, designs, dbs, cost, workspace, missions, json);
        service = new DeliveryService(deliveries, runner, packager, keys, builder, events, missions, json);
        when(keys.status(anyString())).thenReturn(new AndroidSigningKeyService.Status(false, "", ""));
        when(keys.plain(anyString())).thenReturn(Optional.empty());
        when(packager.zipPath(anyString(), anyInt())).thenReturn(Path.of("/tmp/e/M-1/incremento-1.zip"));
        when(packager.stagingDir(anyString(), anyInt())).thenReturn(Path.of("/tmp/e/.staging"));
        when(packager.assemble(any())).thenReturn(Path.of("/tmp/e/M-1/incremento-1.zip"));
        when(workspace.filesAtCommit(anyString(), anyString())).thenReturn(List.of());
        when(cost.missionCost(anyString())).thenReturn(new ModelCostService.Summary(List.of()));
    }

    static SandboxResult.StepResult step(String name, String status, String tail) {
        return new SandboxResult.StepResult(name, status, 0, 1, tail, 0, 0);
    }

    static SandboxResult verifiedRun(int passed, int total) {
        return new SandboxResult("PASS", List.of(new SandboxResult.StepResult("test", "PASS", 0, 1, "", passed, total - passed)));
    }

    @Test void aFailingTargetGivesAPartialDeliveryWithTheReason() throws Exception {
        when(runner.packageJob(any(), any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(new SandboxResult("FAIL",
                List.of(step("checkout", "PASS", ""), step("restore", "PASS", ""), step("package:windows", "PASS", ""),
                        step("package:android-apk", "FAIL", "falta el SDK")))));
        var contract = new com.aicompany.core.agent.model.ClientContract("c", "GAME", List.of(), List.of(), false, "",
                new com.aicompany.core.agent.model.ClientContract.Delivery(List.of("WINDOWS", "ANDROID")));
        var approved = new ContractMemoryService.Version(1, JsonMapper.builder().build().writeValueAsString(contract), "APPROVED", "", null);
        when(contracts.approved("M-1")).thenReturn(Optional.of(approved));

        var r = service.deliver("M-1", 2, SHA, "GODOT_DOTNET_GAME", 3, verifiedRun(47, 47));

        assertEquals("PARTIAL", r.status());
        verify(packager).assemble(argThat(a -> a.entregaMd().contains("- android-apk: no se generó (falta el SDK)")));
        verify(deliveries).save(argThat(d -> "PARTIAL".equals(d.status())));
        verify(events).publish(eq("EMPRESA_DELIVERY_PACKAGED"), eq("M-1"), isNull(), eq("delivery"),
                argThat(m -> "PARTIAL".equals(m.get("status")) && Integer.valueOf(2).equals(m.get("increment"))));
    }

    @Test void noTargetPassingIsAFailedDeliveryThatStillShipsCodeAndDocs() throws Exception {
        when(runner.packageJob(any(), any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(new SandboxResult("FAIL",
                List.of(step("checkout", "PASS", ""), step("restore", "FAIL", "sin red")))));

        var r = service.deliver("M-1", 1, SHA, "FLUTTER_WEB_APP", 0, verifiedRun(1, 1));

        assertEquals("FAILED", r.status());
        assertEquals("no se generó ningún compilado", r.error());
        verify(packager).assemble(argThat(a -> a.extraFiles().containsKey("docker/Dockerfile")));
    }

    @Test void theRunnerDownGivesAFailedDeliveryAndNeverThrows() {
        when(runner.packageJob(any(), any(), any(), anyInt(), any(), any())).thenReturn(Optional.empty());
        when(runner.lastError()).thenReturn("sandbox-runner no disponible: Connection refused");

        var r = service.deliver("M-1", 1, SHA, "GODOT_DOTNET_GAME", 0, verifiedRun(10, 10));

        assertEquals("FAILED", r.status());
        assertTrue(r.error().contains("Connection refused"));
        verify(events).publish(eq("EMPRESA_DELIVERY_FAILED"), eq("M-1"), isNull(), eq("delivery"), anyMap());
    }

    @Test void theEventNeverCarriesSecrets() {
        when(keys.status("M-1")).thenReturn(new AndroidSigningKeyService.Status(true, "forjai", "t"));
        when(keys.plain("M-1")).thenReturn(Optional.of(new AndroidSigningKeyService.Plain("S0VZ", "forjai", "clave123")));
        when(runner.packageJob(any(), any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(new SandboxResult("PASS",
                List.of(step("package:android-aab", "PASS", "")))));

        service.deliver("M-1", 3, SHA, "GODOT_DOTNET_GAME", 0, verifiedRun(5, 5));

        verify(events).publish(eq("EMPRESA_DELIVERY_PACKAGED"), any(), any(), any(), argThat(m -> !m.toString().contains("clave123")
                && !m.toString().contains("S0VZ")));
        verify(runner).packageJob(any(), any(), any(), eq(3), argThat(t -> t.contains("android-aab")), notNull());
    }

    @Test void recoveryRepackagesAStuckDelivery() {
        when(deliveries.packaging()).thenReturn(List.of(new DeliveryRecord("M-1", 2, "PACKAGING", null, 0, List.of(), null, "t")));
        when(missions.verifiedCommit("M-1")).thenReturn(Optional.of(SHA));
        when(missions.lastTeamPlanJson("M-1")).thenReturn(Optional.of("{\"stackProfile\":\"GODOT_DOTNET_GAME\"}"));
        when(runner.packageJob(any(), any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(new SandboxResult("PASS",
                List.of(step("package:windows", "PASS", "")))));

        service.resumeStuck();

        verify(runner).packageJob(eq("M-1"), eq(SHA), eq("GODOT_DOTNET_GAME"), eq(2), any(), any());
    }

    @Test void deliverVerifiedWithoutAVerifiedCommitSaysSo() {
        when(missions.verifiedCommit("M-1")).thenReturn(Optional.empty());
        var ex = assertThrows(IllegalStateException.class, () -> service.deliverVerified("M-1"));
        assertEquals("M-1 no tiene un incremento VERIFIED para entregar.", ex.getMessage());
    }

    @Test void resumeStuckSkipsMissionsInWorkingStatesAndRedoesStoppedOnes() {
        when(deliveries.packaging()).thenReturn(List.of(
                new DeliveryRecord("M-1", 1, "PACKAGING", null, 0, List.of(), null, "t"),
                new DeliveryRecord("M-2", 1, "PACKAGING", null, 0, List.of(), null, "t")));
        when(missions.find("M-1")).thenReturn(Optional.of(new com.aicompany.core.model.MissionResponse("M-1",
                com.aicompany.core.model.MissionStatus.EVALUATING, "", 90, "", "", null, null, "TEAM-DEVELOPMENT")));
        when(missions.find("M-2")).thenReturn(Optional.of(new com.aicompany.core.model.MissionResponse("M-2",
                com.aicompany.core.model.MissionStatus.AWAITING_INVESTOR, "", 100, "", "", null, null, "TEAM-DEVELOPMENT")));
        when(missions.verifiedCommit(anyString())).thenReturn(Optional.of(SHA));
        when(missions.lastTeamPlanJson(anyString())).thenReturn(Optional.of("{\"stackProfile\":\"GODOT_DOTNET_GAME\"}"));
        when(runner.packageJob(any(), any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(new SandboxResult("PASS",
                List.of(step("package:windows", "PASS", "")))));

        service.resumeStuck();

        verify(runner, never()).packageJob(eq("M-1"), any(), any(), anyInt(), any(), any());
        verify(runner).packageJob(eq("M-2"), any(), any(), anyInt(), any(), any());
    }
}
