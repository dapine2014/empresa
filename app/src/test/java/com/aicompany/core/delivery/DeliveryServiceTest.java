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
        when(packager.stagingDir(anyString(), anyInt(), any())).thenReturn(Path.of("/tmp/e/.staging"));
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
        when(missions.verifiedIncrement("M-1")).thenReturn(Optional.of(2));
        when(missions.lastTeamPlanJson("M-1")).thenReturn(Optional.of("{\"stackProfile\":\"GODOT_DOTNET_GAME\"}"));
        when(runner.packageJob(any(), any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(new SandboxResult("PASS",
                List.of(step("package:windows", "PASS", "")))));

        service.resumeStuck();

        verify(packager).cleanOrphans();
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

    static com.aicompany.core.model.MissionResponse mission(String id, com.aicompany.core.model.MissionStatus status) {
        return new com.aicompany.core.model.MissionResponse(id, status, "", 90, "", "", null, null, "TEAM-DEVELOPMENT");
    }

    void verifiedGame(String missionId) {
        when(missions.verifiedCommit(missionId)).thenReturn(Optional.of(SHA));
        when(missions.lastTeamPlanJson(missionId)).thenReturn(Optional.of("{\"stackProfile\":\"GODOT_DOTNET_GAME\"}"));
        when(runner.packageJob(any(), any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(new SandboxResult("PASS",
                List.of(step("package:windows", "PASS", "")))));
    }

    // Revisión final (2): con el incremento 2 en construcción, «Generar entrega» empaqueta el 1, que es el VERIFIED.
    @Test void deliverVerifiedUsesTheIncrementThatWasVerifiedNotTheOneInConstruction() {
        verifiedGame("M-1");
        when(missions.verifiedIncrement("M-1")).thenReturn(Optional.of(1));
        when(requirements.currentIncrement("M-1")).thenReturn(2);
        when(requirements.builtIncrement("M-1")).thenReturn(1);

        var r = service.deliverVerified("M-1");

        assertEquals(1, r.increment());
        verify(runner).packageJob(eq("M-1"), eq(SHA), any(), eq(1), any(), any());
        verify(packager).zipPath("M-1", 1);
    }

    @Test void deliverVerifiedWithoutAStoredIncrementUsesTheLastBuilt() {
        verifiedGame("M-1");
        when(requirements.builtIncrement("M-1")).thenReturn(3);

        service.deliverVerified("M-1");

        verify(runner).packageJob(eq("M-1"), eq(SHA), any(), eq(3), any(), any());
    }

    @Test void deliverVerifiedIsRejectedWhileTheMissionIsWorking() {
        verifiedGame("M-1");
        when(missions.find("M-1")).thenReturn(Optional.of(mission("M-1", com.aicompany.core.model.MissionStatus.EVALUATING)));

        var ex = assertThrows(IllegalStateException.class, () -> service.deliverVerified("M-1"));

        assertTrue(ex.getMessage().contains("está trabajando (EVALUATING)"), ex.getMessage());
        verifyNoInteractions(runner);
    }

    // Revisión final (11): a pedido no se sabe cuántas rondas hubo.
    @Test void onDemandDeliveriesSayTheCorrectionRoundsAreNotAvailable() throws Exception {
        verifiedGame("M-1");

        service.deliverVerified("M-1");

        verify(packager).assemble(argThat(a -> a.entregaMd().contains("- Rondas de corrección: no disponible")
                && !a.entregaMd().contains("0 rondas de corrección")));
    }

    // Revisión final (3): un solo empaquetado por misión e incremento a la vez.
    @Test void aSecondDeliveryOfTheSameIncrementIsRejectedForTheButtonAndSkippedForTheHook() throws Exception {
        verifiedGame("M-1");
        when(missions.verifiedIncrement("M-1")).thenReturn(Optional.of(1));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(runner.packageJob(any(), any(), any(), anyInt(), any(), any())).thenAnswer(inv -> {
            entered.countDown();
            assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
            return Optional.of(new SandboxResult("PASS", List.of(step("package:windows", "PASS", ""))));
        });
        var first = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> service.deliver("M-1", 1, SHA, "GODOT_DOTNET_GAME", 2, verifiedRun(1, 1)));
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));

        var ex = assertThrows(IllegalStateException.class, () -> service.deliverVerified("M-1"));
        assertEquals("Ya se está empaquetando el incremento 1 de M-1: espera a que termine.", ex.getMessage());
        assertNull(service.deliver("M-1", 1, SHA, "GODOT_DOTNET_GAME", 2, verifiedRun(1, 1)));
        service.abandonInterrupted("M-1", 1);
        verify(deliveries, never()).find(anyString(), anyInt());

        release.countDown();
        assertEquals("PARTIAL", first.get(10, java.util.concurrent.TimeUnit.SECONDS).status());
        verify(runner, times(1)).packageJob(any(), any(), any(), anyInt(), any(), any());
        // Terminado el primero, se puede volver a generar.
        assertNotNull(service.deliverVerified("M-1"));
    }

    // Revisión final (3): el zip usa la carpeta propia del job y después se borra.
    @Test void theJobsOwnStagingDirIsPackagedAndDiscarded() throws Exception {
        var job = "incremento-1-" + java.util.UUID.randomUUID();
        var dir = Path.of("/tmp/e/.staging/M-1").resolve(job);
        when(packager.stagingDir("M-1", 1, job)).thenReturn(dir);
        when(runner.packageJob(any(), any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(new SandboxResult("PASS",
                List.of(step("package:windows", "PASS", "")), job)));

        service.deliver("M-1", 1, SHA, "GODOT_DOTNET_GAME", 0, verifiedRun(1, 1));

        verify(packager).assemble(argThat(a -> dir.equals(a.staging())));
        verify(packager).discardStaging(dir);
    }

    // Revisión final (9): un juego cuyo contrato pide solo Docker no llama al runner y entrega código y documentos.
    @Test void withNoPossibleTargetTheRunnerIsNotCalledAndTheZipStillShips() throws Exception {
        var contract = new com.aicompany.core.agent.model.ClientContract("c", "GAME", List.of(), List.of(), false, "",
                new com.aicompany.core.agent.model.ClientContract.Delivery(List.of("DOCKER")));
        when(contracts.approved("M-1")).thenReturn(Optional.of(new ContractMemoryService.Version(1,
                JsonMapper.builder().build().writeValueAsString(contract), "APPROVED", "", null)));

        var r = service.deliver("M-1", 1, SHA, "GODOT_DOTNET_GAME", 0, verifiedRun(1, 1));

        verifyNoInteractions(runner);
        assertEquals("FAILED", r.status());
        assertEquals("no se pidió ningún compilado posible", r.error());
        assertEquals("/tmp/e/M-1/incremento-1.zip", r.path());
        verify(packager).assemble(argThat(a -> a.staging() == null && a.entregaMd().contains(DeliveryTargets.NO_DOCKER_GAME)));
    }

    // Revisión final (12): cancelar la misión no se traga como una entrega fallida más.
    @Test void aCancelledMissionIsRethrownAfterRecordingTheDelivery() {
        when(runner.packageJob(any(), any(), any(), anyInt(), any(), any()))
                .thenThrow(new com.aicompany.core.service.MissionCancellations.MissionCancelledException("M-1"));

        assertThrows(com.aicompany.core.service.MissionCancellations.MissionCancelledException.class,
                () -> service.deliver("M-1", 1, SHA, "GODOT_DOTNET_GAME", 0, verifiedRun(1, 1)));

        verify(deliveries).save(argThat(d -> "FAILED".equals(d.status()) && d.error().contains("canceló")));
    }

    // Revisión final (4): si el incremento ya no es el VERIFIED, la entrega cortada queda FAILED (no se rehace).
    @Test void resumeStuckFailsADeliveryWhoseIncrementIsNoLongerTheVerifiedOne() {
        when(deliveries.packaging()).thenReturn(List.of(new DeliveryRecord("M-1", 2, "PACKAGING", "", 0, List.of(), "", "t")));
        verifiedGame("M-1");
        when(missions.verifiedIncrement("M-1")).thenReturn(Optional.of(1));

        service.resumeStuck();

        verifyNoInteractions(runner);
        verify(deliveries).save(argThat(d -> d.increment() == 2 && "FAILED".equals(d.status())
                && d.error().contains("ya no es el VERIFIED")));
    }

    @Test void abandonInterruptedOnlyFailsADeliveryLeftInPackaging() {
        when(deliveries.find("M-1", 1)).thenReturn(Optional.of(new DeliveryRecord("M-1", 1, "PACKAGING", "", 0, List.of(), "", "t")));
        when(deliveries.find("M-1", 2)).thenReturn(Optional.of(new DeliveryRecord("M-1", 2, "READY", "/z", 1, List.of(), "", "t")));

        service.abandonInterrupted("M-1", 1);
        service.abandonInterrupted("M-1", 2);

        verify(deliveries).save(argThat(d -> d.increment() == 1 && "FAILED".equals(d.status())
                && d.error().equals(DeliveryService.INTERRUPTED)));
        verify(deliveries, never()).save(argThat(d -> d.increment() == 2));
    }

    // Revisión final (8): migraciones en orden numérico, solo las versionadas, y el LEEME con los comandos.
    @Test void anAppDeliveryShipsTheVersionedMigrationsInOrderWithTheReadmeAndDocker() throws Exception {
        when(workspace.filesAtCommit("M-1", SHA)).thenReturn(List.of("db/postgres/migrations/V10__indices.sql",
                "db/postgres/migrations/V2__datos.sql", "db/postgres/migrations/V1__crear.sql",
                "db/postgres/migrations/LEEME.txt", "src/App.cs"));
        when(workspace.readFileAtCommit(eq("M-1"), eq(SHA), anyString())).thenAnswer(inv -> "-- " + inv.getArgument(2));
        when(runner.packageJob(any(), any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(new SandboxResult("PASS",
                List.of(step("package:app", "PASS", "")))));

        service.deliver("M-1", 1, SHA, "DOTNET_APP", 0, verifiedRun(1, 1));

        verify(packager).assemble(argThat(a -> List.of("V1__crear.sql", "V2__datos.sql", "V10__indices.sql")
                        .equals(List.copyOf(a.migrations().keySet()))
                && a.extraFiles().get("base-de-datos/LEEME.md").contains("-f migraciones/V1__crear.sql")
                && a.extraFiles().get("docker/docker-compose.yml").contains("image: postgres:16")
                && a.extraFiles().containsKey("docker/Dockerfile")
                && a.entregaMd().contains("docker compose up")));
        verify(workspace, never()).readFileAtCommit(any(), any(), eq("db/postgres/migrations/LEEME.txt"));
    }
}
