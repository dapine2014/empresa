package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.CatalogProduct;
import com.aicompany.core.model.CatalogStatus;
import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.OrchestratorRun;
import com.aicompany.core.model.OrchestratorStatus;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.ProductChoice;
import com.aicompany.core.model.ProductEvidence;
import com.aicompany.core.model.ProductSheet;
import com.aicompany.core.model.ProductView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec orquestador (2026-09-28): ciclo autónomo elegir → ficha → construir → listo, un paso por chequeo. */
class ProductOrchestratorTest {

    private final OrchestratorMemoryService runs = mock(OrchestratorMemoryService.class);
    private final ProductService products = mock(ProductService.class);
    private final ProductMemoryService productMemory = mock(ProductMemoryService.class);
    private final ProductAutomation automation = mock(ProductAutomation.class);
    private final MissionService missions = mock(MissionService.class);
    private final MissionMemoryService missionMemory = mock(MissionMemoryService.class);
    private final CeoService ceo = mock(CeoService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final AlertMailService mail = mock(AlertMailService.class);
    private final ProductOrchestrator orchestrator = new ProductOrchestrator(runs, products, productMemory, automation,
            missions, missionMemory, ceo, companyMemory, policies, events, mail, "qwen3:8b");

    private final List<OrchestratorRun> saved = new ArrayList<>();

    {
        when(policies.activeValue(PolicyKey.ORCHESTRATOR_ENABLED)).thenReturn(1.0);
        when(policies.activeValue(PolicyKey.MAX_AUTONOMOUS_PRODUCTS)).thenReturn(1.0);
        when(policies.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS)).thenReturn(2.0);
        when(companyMemory.agentModel(eq("ceo"), anyString())).thenReturn("nvidia-ceo:m");
        when(runs.active()).thenReturn(List.of());
        when(runs.create()).thenAnswer(inv -> run(OrchestratorStatus.CHOOSING, null, null, null));
        doAnswer(inv -> saved.add(inv.getArgument(0))).when(runs).save(any());
        when(missionMemory.tasks(anyString())).thenReturn(List.of());
        when(productMemory.evidence(any())).thenReturn(new ProductEvidence(true, false));
    }

    private static OrchestratorRun run(OrchestratorStatus status, String productId, String discovery, String build) {
        return new OrchestratorRun("RUN-1", status, productId, discovery, build, null, Instant.now(), Instant.now(), null);
    }

    private static CatalogProduct product(String id, CatalogStatus status, String createdBy, String kind, int demandMissions) {
        var validated = new ArrayList<String>();
        for (int i = 0; i < demandMissions; i++) {
            validated.add("MISSION-D" + i);
        }
        return new CatalogProduct(id, "Producto " + id, "desc", kind, "Pymes", 100, false, 10, null, List.of("WORLDWIDE"),
                List.of("en", "es"), status, null, createdBy, Instant.parse("2026-09-28T10:00:00Z"),
                Instant.parse("2026-09-28T10:00:00Z"), validated, List.of());
    }

    private static CatalogProduct withoutTarget(CatalogProduct p) {
        return new CatalogProduct(p.id(), p.name(), p.description(), p.kind(), null, p.priceUsd(), p.priceOnRequest(),
                p.estimatedCostUsd(), p.delivery(), p.markets(), p.languages(), p.status(), p.statusBeforePause(),
                p.createdBy(), p.createdAt(), p.updatedAt(), p.validatedBy(), p.builtBy());
    }

    private static ProductSheet sheet(String target, double price, double cost) {
        return new ProductSheet("SERVICE", target, List.of("WORLDWIDE"), List.of("en", "es"), price, false, cost, "Entrega en 48 h",
                "Pack de Contenido", "10 posts listos para redes a partir de una pieza larga.");
    }

    private static ProductView view(CatalogProduct p, List<String> missing) {
        return new ProductView(p, missing, List.of());
    }

    private static MissionResponse mission(String id, MissionStatus status) {
        return new MissionResponse(id, status, "PRODUCTION", 95, "x", "x", Instant.now(), null);
    }

    private OrchestratorRun lastSaved() {
        return saved.get(saved.size() - 1);
    }

    // --- Disparador ---

    @Test
    void itDoesNotStartWhileSomethingIsReadyOrInConstruction() {
        when(products.list()).thenReturn(List.of(view(product("P1", CatalogStatus.READY_TO_SELL, "human", "SOFTWARE", 1), List.of())));
        orchestrator.tick();
        when(products.list()).thenReturn(List.of(view(product("P1", CatalogStatus.IN_CONSTRUCTION, "human", "SOFTWARE", 1), List.of())));
        orchestrator.tick();

        verify(runs, never()).create();
    }

    @Test
    void itDoesNothingWhenTheFounderTurnedItOff() {
        when(policies.activeValue(PolicyKey.ORCHESTRATOR_ENABLED)).thenReturn(0.0);
        when(products.list()).thenReturn(List.of());

        orchestrator.tick();

        verify(runs, never()).create();
        verify(runs, never()).active();
    }

    @Test
    void anActiveRunIsAdvancedInsteadOfStartingAnother() {
        when(runs.active()).thenReturn(List.of(run(OrchestratorStatus.DISCOVERING, null, "MISSION-D", null)));
        when(missionMemory.find("MISSION-D")).thenReturn(Optional.of(mission("MISSION-D", MissionStatus.WAITING_AGENT_RESULTS)));

        orchestrator.tick();

        verify(runs, never()).create();
    }

    // --- Elegir ---

    @Test
    void withoutCandidatesItLaunchesADiscovery() {
        when(products.list()).thenReturn(List.of());

        orchestrator.tick();

        verify(missions).start(startsWith("MISSION-ORQ-"), contains("cualquier país"), eq("PRODUCTION"), isNull(), isNull());
        verify(missionMemory).markLaunchedBy(startsWith("MISSION-ORQ-"), eq("orchestrator"));
        assertEquals(OrchestratorStatus.DISCOVERING, lastSaved().status());
        verify(events).publish(eq("EMPRESA_ORCHESTRATOR_STARTED"), isNull(), isNull(), eq("orchestrator"), anyMap());
        verify(mail, atLeastOnce()).send(anyString(), anyString(), anyBoolean());
    }

    @Test
    void theFoundersIdeaWinsWithoutAskingAlex() {
        when(products.list()).thenReturn(List.of(
                view(product("P1", CatalogStatus.IDEA, "product", "SOFTWARE", 3), List.of()),
                view(product("P2", CatalogStatus.IDEA, "human", "SERVICE", 1), List.of())));

        orchestrator.tick();

        verify(ceo, never()).chooseProduct(anyString(), anyString());
        assertEquals("P2", lastSaved().productId());
        assertEquals(OrchestratorStatus.PROPOSING, lastSaved().status());
    }

    @Test
    void alexChoosesAmongIdeasWithEvidence() {
        when(products.list()).thenReturn(List.of(
                view(product("P1", CatalogStatus.IDEA, "product", "SOFTWARE", 1), List.of()),
                view(product("P2", CatalogStatus.IDEA, "product", "SERVICE", 1), List.of())));
        when(ceo.chooseProduct(anyString(), eq("nvidia-ceo:m"))).thenReturn(new ProductChoice("P1", "Mejor margen"));

        orchestrator.tick();

        assertEquals("P1", lastSaved().productId());
        assertEquals("Mejor margen", lastSaved().choiceReason());
        verify(events).publish(eq("EMPRESA_ORCHESTRATOR_CHOSE"), isNull(), isNull(), eq("orchestrator"), anyMap());
    }

    @Test
    void anInvalidOrFailedChoiceFallsBackToTheJavaRule() {
        when(products.list()).thenReturn(List.of(
                view(product("P1", CatalogStatus.IDEA, "product", "SOFTWARE", 1), List.of()),
                view(product("P2", CatalogStatus.IDEA, "product", "SERVICE", 3), List.of())));
        when(ceo.chooseProduct(anyString(), anyString())).thenReturn(new ProductChoice("P9", "inventado"))
                .thenThrow(new IllegalStateException("modelo caído"));

        orchestrator.tick();
        assertEquals("P2", lastSaved().productId());
        assertTrue(lastSaved().choiceReason().contains("Regla de Java"), lastSaved().choiceReason());

        saved.clear();
        orchestrator.tick();
        assertEquals("P2", lastSaved().productId());
    }

    @Test
    void ideasWithoutWebEvidenceAreNotCandidates() {
        when(products.list()).thenReturn(List.of(view(product("P1", CatalogStatus.IDEA, "product", "SOFTWARE", 1), List.of())));
        when(productMemory.evidence(any())).thenReturn(new ProductEvidence(false, false));

        orchestrator.tick();

        verify(missions).start(startsWith("MISSION-ORQ-"), anyString(), eq("PRODUCTION"), isNull(), isNull());
    }

    // --- Descubrir ---

    @Test
    void aFinishedDiscoveryGoesBackToChoosing() {
        when(runs.active()).thenReturn(List.of(run(OrchestratorStatus.DISCOVERING, null, "MISSION-D", null)));
        when(missionMemory.find("MISSION-D")).thenReturn(Optional.of(mission("MISSION-D", MissionStatus.AWAITING_INVESTOR)));

        orchestrator.tick();

        assertEquals(OrchestratorStatus.CHOOSING, lastSaved().status());
    }

    @Test
    void aFailedDiscoveryFailsTheRun() {
        when(runs.active()).thenReturn(List.of(run(OrchestratorStatus.DISCOVERING, null, "MISSION-D", null)));
        when(missionMemory.find("MISSION-D")).thenReturn(Optional.of(mission("MISSION-D", MissionStatus.FAILED)));

        orchestrator.tick();

        assertEquals(OrchestratorStatus.FAILED, lastSaved().status());
        verify(events).publish(eq("EMPRESA_ORCHESTRATOR_FAILED"), isNull(), isNull(), eq("orchestrator"), anyMap());
    }

    // --- Ficha y construcción ---

    @Test
    void theSheetIsAppliedAndSoftwareIsBuiltByEngineering() {
        var p = product("P1", CatalogStatus.IDEA, "product", "SOFTWARE", 1);
        when(runs.active()).thenReturn(List.of(run(OrchestratorStatus.PROPOSING, "P1", null, null)));
        when(products.view("P1")).thenReturn(Optional.of(view(p, List.of())));
        when(ceo.proposeProductSheet(anyString(), anyString(), anyString())).thenReturn(new ProductSheet("SOFTWARE",
                "Pequeñas empresas", List.of("WORLDWIDE"), List.of("en", "es"), 120.0, false, 10.0, "",
                "Email Signature Generator", "Genera firmas de email profesionales en HTML."));
        when(products.update(eq("P1"), any(), eq("orchestrator"))).thenReturn(view(p, List.of()));

        orchestrator.tick();

        verify(products).update(eq("P1"), argThat(c -> c.priceUsd() == 120.0 && "SOFTWARE".equals(c.kind())), eq("orchestrator"));
        verify(products).changeStatus(eq("P1"), eq(CatalogStatus.IN_CONSTRUCTION), anyString(), eq("orchestrator"));
        verify(missions).start(startsWith("MISSION-ORQ-"), contains("Email Signature Generator"), eq("PRODUCTION"), isNull(), eq("TEAM-ENGINEERING"));
        verify(products).linkMissions(eq("P1"), isNull(), argThat(l -> l.size() == 1), eq("orchestrator"));
        assertEquals(OrchestratorStatus.BUILDING, lastSaved().status());
        assertNotNull(lastSaved().buildMissionId());
    }

    @Test
    void aServiceIsDesignedByCreative() {
        var p = product("P2", CatalogStatus.IDEA, "product", "SERVICE", 1);
        when(runs.active()).thenReturn(List.of(run(OrchestratorStatus.PROPOSING, "P2", null, null)));
        when(products.view("P2")).thenReturn(Optional.of(view(p, List.of())));
        when(ceo.proposeProductSheet(anyString(), anyString(), anyString())).thenReturn(new ProductSheet("SERVICE",
                "Pymes", List.of(), List.of(), 150.0, false, 20.0, "borrador", "Auditoría Express", "Revisión de procesos en 48 h."));
        when(products.update(eq("P2"), any(), eq("orchestrator"))).thenReturn(view(p, List.of()));

        orchestrator.tick();

        verify(products).update(eq("P2"), argThat(c -> List.of("WORLDWIDE").equals(c.markets())), eq("orchestrator"));
        verify(missions).start(anyString(), anyString(), eq("PRODUCTION"), isNull(), eq("TEAM-CREATIVE-PRODUCT-INTELLIGENCE"));
    }

    @Test
    void anInvalidSheetFailsTheRunWithTheReason() {
        var p = product("P1", CatalogStatus.IDEA, "product", "SOFTWARE", 1);
        when(runs.active()).thenReturn(List.of(run(OrchestratorStatus.PROPOSING, "P1", null, null)));
        when(products.view("P1")).thenReturn(Optional.of(view(p, List.of())));
        when(ceo.proposeProductSheet(anyString(), anyString(), anyString())).thenReturn(new ProductSheet("SOFTWARE",
                "Pymes", List.of(), List.of(), -5.0, false, 1.0, "", "Producto X", "Descripción."));
        when(products.update(eq("P1"), any(), eq("orchestrator")))
                .thenThrow(new IllegalArgumentException("El precio no puede ser negativo."));

        orchestrator.tick();

        assertEquals(OrchestratorStatus.FAILED, lastSaved().status());
        assertTrue(lastSaved().failureReason().contains("negativo"), lastSaved().failureReason());
        verify(missions, never()).start(anyString(), anyString(), anyString(), any(), anyString());
    }

    // --- Construyendo ---

    private void building(CatalogProduct p, MissionStatus missionStatus) {
        when(runs.active()).thenReturn(List.of(run(OrchestratorStatus.BUILDING, p.id(), null, "MISSION-B")));
        when(missionMemory.find("MISSION-B")).thenReturn(Optional.of(mission("MISSION-B", missionStatus)));
    }

    @Test
    void whileTheBuildRunsNothingHappens() {
        var p = product("P1", CatalogStatus.IN_CONSTRUCTION, "product", "SOFTWARE", 1);
        building(p, MissionStatus.WAITING_AGENT_RESULTS);
        when(products.view("P1")).thenReturn(Optional.of(view(p, List.of())));

        orchestrator.tick();

        verify(automation, never()).buildFinished(anyString());
        assertTrue(saved.isEmpty());
    }

    @Test
    void aFinishedBuildThatMeetsEverythingEndsTheRunAsReady() {
        var building = product("P1", CatalogStatus.IN_CONSTRUCTION, "product", "SOFTWARE", 1);
        var ready = product("P1", CatalogStatus.READY_TO_SELL, "product", "SOFTWARE", 1);
        building(building, MissionStatus.AWAITING_INVESTOR);
        when(products.view("P1")).thenReturn(Optional.of(view(building, List.of())), Optional.of(view(ready, List.of())));

        orchestrator.tick();

        verify(automation).buildFinished("MISSION-B");
        assertEquals(OrchestratorStatus.READY, lastSaved().status());
        verify(events).publish(eq("EMPRESA_ORCHESTRATOR_READY"), isNull(), isNull(), eq("orchestrator"), anyMap());
    }

    @Test
    void missingRequirementsAskForACorrectionRoundWhileRoundsRemain() {
        var p = product("P1", CatalogStatus.IN_CONSTRUCTION, "product", "SOFTWARE", 1);
        building(p, MissionStatus.AWAITING_INVESTOR);
        when(products.view("P1")).thenReturn(Optional.of(view(p, List.of("Falta una misión de construcción asociada con su validación en VERIFIED."))));
        when(missionMemory.evidenceRound("MISSION-B")).thenReturn(0);

        orchestrator.tick();

        verify(missions).recordDecision(eq("MISSION-B"), argThat((DecisionCommand d) ->
                d.decision() == InvestorDecision.REQUEST_MORE_EVIDENCE && d.reasoning().contains("VERIFIED")));
        assertTrue(saved.isEmpty() || lastSaved().status() == OrchestratorStatus.BUILDING);
    }

    @Test
    void withoutRoundsLeftTheRunFailsWithWhatIsMissing() {
        var p = product("P1", CatalogStatus.IN_CONSTRUCTION, "product", "SOFTWARE", 1);
        building(p, MissionStatus.FAILED);
        when(products.view("P1")).thenReturn(Optional.of(view(p, List.of("Falta una misión de construcción verificada."))));
        when(missionMemory.evidenceRound("MISSION-B")).thenReturn(2);

        orchestrator.tick();

        verify(missions, never()).recordDecision(anyString(), any());
        assertEquals(OrchestratorStatus.FAILED, lastSaved().status());
        assertTrue(lastSaved().failureReason().contains("verificada"), lastSaved().failureReason());
    }

    @Test
    void aServiceGetsItsDeliveryFromCreativeBeforeTheCheck() {
        var p = product("P2", CatalogStatus.IN_CONSTRUCTION, "product", "SERVICE", 1);
        building(p, MissionStatus.AWAITING_INVESTOR);
        when(products.view("P2")).thenReturn(Optional.of(view(p, List.of())));
        when(missionMemory.tasks("MISSION-B")).thenReturn(List.of(new AgentTask("MISSION-B-INTERACTION-DESIGN", "MISSION-B",
                "interaction-design", "DESIGN", "COMPLETED", "{\"recommendation\":\"Flujo en 3 pasos\"}", Instant.now())));
        when(ceo.summarizeDelivery(anyString(), contains("Flujo en 3 pasos"), anyString())).thenReturn("Entrega en 48 h");

        orchestrator.tick();

        verify(products).update(eq("P2"), argThat(c -> "Entrega en 48 h".equals(c.delivery())), eq("orchestrator"));
        verify(automation).buildFinished("MISSION-B");
    }

    @Test
    void aDeletedBuildMissionFailsTheRun() {
        var p = product("P1", CatalogStatus.IN_CONSTRUCTION, "product", "SOFTWARE", 1);
        when(runs.active()).thenReturn(List.of(run(OrchestratorStatus.BUILDING, "P1", null, "MISSION-B")));
        when(products.view("P1")).thenReturn(Optional.of(view(p, List.of())));
        when(missionMemory.find("MISSION-B")).thenReturn(Optional.empty());

        orchestrator.tick();

        assertEquals(OrchestratorStatus.FAILED, lastSaved().status());
        assertTrue(lastSaved().failureReason().contains("ya no existe"));
    }

    // Revisión final: si el fundador rechaza la misión (CANCELLED), el ciclo se detiene; no es un fallo ni pide rondas.
    @Test
    void aBuildRejectedByTheFounderStopsTheRun() {
        var p = product("P1", CatalogStatus.IN_CONSTRUCTION, "product", "SOFTWARE", 1);
        building(p, MissionStatus.CANCELLED);
        when(products.view("P1")).thenReturn(Optional.of(view(p, List.of("Falta el precio."))));

        orchestrator.tick();

        assertEquals(OrchestratorStatus.STOPPED, lastSaved().status());
        verify(missions, never()).recordDecision(anyString(), any());
        verify(events).publish(eq("EMPRESA_ORCHESTRATOR_STOPPED"), isNull(), isNull(), eq("orchestrator"), anyMap());
        verify(mail, never()).send(anyString(), anyString(), eq(true));
    }

    @Test
    void aDiscoveryRejectedByTheFounderStopsTheRun() {
        when(runs.active()).thenReturn(List.of(run(OrchestratorStatus.DISCOVERING, null, "MISSION-D", null)));
        when(missionMemory.find("MISSION-D")).thenReturn(Optional.of(mission("MISSION-D", MissionStatus.CANCELLED)));

        orchestrator.tick();

        assertEquals(OrchestratorStatus.STOPPED, lastSaved().status());
    }

    @Test
    void aPausedProductStopsTheRun() {
        var p = product("P1", CatalogStatus.PAUSED, "product", "SOFTWARE", 1);
        when(runs.active()).thenReturn(List.of(run(OrchestratorStatus.BUILDING, "P1", null, "MISSION-B")));
        when(products.view("P1")).thenReturn(Optional.of(view(p, List.of())));

        orchestrator.tick();

        assertEquals(OrchestratorStatus.STOPPED, lastSaved().status());
        verify(missions, never()).recordDecision(anyString(), any());
    }

    @Test
    void theCurrentViewShowsTheLatestRunAndWhetherItIsOn() {
        when(runs.latest()).thenReturn(Optional.of(run(OrchestratorStatus.BUILDING, "P1", null, "MISSION-B")));
        when(runs.steps("RUN-1")).thenReturn(List.of());

        var current = orchestrator.current();

        assertTrue(current.enabled());
        assertEquals(OrchestratorStatus.BUILDING, current.run().status());
    }

    // --- Revisión en vivo (2026-09-29): la ficha la valida Java y las rondas solo piden lo que produce la misión ---

    private void proposing(CatalogProduct p) {
        when(runs.active()).thenReturn(List.of(run(OrchestratorStatus.PROPOSING, p.id(), null, null)));
        when(products.view(p.id())).thenReturn(Optional.of(view(p, List.of())));
        when(products.update(eq(p.id()), any(), eq("orchestrator"))).thenReturn(view(p, List.of()));
    }

    @Test
    void aSheetWithoutTargetCustomerIsAskedAgainWithTheCorrection() {
        var p = withoutTarget(product("P1", CatalogStatus.IDEA, "product", "SERVICE", 1));
        proposing(p);
        var text = org.mockito.ArgumentCaptor.forClass(String.class);
        when(ceo.proposeProductSheet(text.capture(), anyString(), anyString()))
                .thenReturn(sheet(null, 39, 5), sheet("Creadores de contenido", 39, 5));

        orchestrator.tick();

        assertTrue(text.getAllValues().get(1).contains("CORRECCIÓN"), text.getAllValues().get(1));
        assertTrue(text.getAllValues().get(1).contains("cliente objetivo"), text.getAllValues().get(1));
        verify(products).update(eq("P1"), argThat(c -> "Creadores de contenido".equals(c.targetCustomer())), eq("orchestrator"));
        assertEquals(OrchestratorStatus.BUILDING, lastSaved().status());
    }

    @Test
    void aSheetThatStaysIncompleteFailsTheRunWithoutBuilding() {
        var p = withoutTarget(product("P1", CatalogStatus.IDEA, "product", "SERVICE", 1));
        proposing(p);
        when(ceo.proposeProductSheet(anyString(), anyString(), anyString())).thenReturn(sheet("", 39, 0));

        orchestrator.tick();

        assertEquals(OrchestratorStatus.FAILED, lastSaved().status());
        assertTrue(lastSaved().failureReason().contains("cliente objetivo"), lastSaved().failureReason());
        assertTrue(lastSaved().failureReason().contains("costo"), lastSaved().failureReason());
        verify(products, never()).update(anyString(), any(), anyString());
        verify(missions, never()).start(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void aMissingSheetFieldAfterTheBuildIsCompletedByTheOrchestratorNotByARound() {
        var p = withoutTarget(product("P1", CatalogStatus.IN_CONSTRUCTION, "product", "SERVICE", 1));
        building(p, MissionStatus.AWAITING_INVESTOR);
        var fixed = product("P1", CatalogStatus.IN_CONSTRUCTION, "product", "SERVICE", 1);
        when(products.view("P1")).thenReturn(Optional.of(view(p, List.of("Falta el cliente objetivo."))),
                Optional.of(view(p, List.of("Falta el cliente objetivo."))), Optional.of(view(fixed, List.of())));
        when(ceo.proposeProductSheet(anyString(), anyString(), anyString())).thenReturn(sheet("Creadores de contenido", 39, 5));
        when(ceo.summarizeDelivery(anyString(), anyString(), anyString())).thenReturn("");

        orchestrator.tick();

        verify(products).update(eq("P1"), argThat(c -> "Creadores de contenido".equals(c.targetCustomer())), eq("orchestrator"));
        verify(products).changeStatus(eq("P1"), eq(CatalogStatus.READY_TO_SELL), anyString(), eq("orchestrator"));
        verify(missions, never()).recordDecision(anyString(), any());
        assertEquals(OrchestratorStatus.READY, lastSaved().status());
    }

    @Test
    void aSheetFieldTheOrchestratorCannotCompleteFailsWithoutRounds() {
        var p = withoutTarget(product("P1", CatalogStatus.IN_CONSTRUCTION, "product", "SERVICE", 1));
        building(p, MissionStatus.FAILED);
        when(products.view("P1")).thenReturn(Optional.of(view(p, List.of("Falta el cliente objetivo."))));
        when(ceo.proposeProductSheet(anyString(), anyString(), anyString())).thenReturn(sheet(null, 39, 5));
        when(missionMemory.evidenceRound("MISSION-B")).thenReturn(0);

        orchestrator.tick();

        verify(missions, never()).recordDecision(anyString(), any());
        assertEquals(OrchestratorStatus.FAILED, lastSaved().status());
        assertTrue(lastSaved().failureReason().contains("cliente objetivo"), lastSaved().failureReason());
    }

    @Test
    void aFailedRunReturnsItsProductToIdea() {
        var p = product("P1", CatalogStatus.IN_CONSTRUCTION, "product", "SOFTWARE", 1);
        building(p, MissionStatus.FAILED);
        when(products.view("P1")).thenReturn(Optional.of(view(p, List.of("Falta una misión de construcción asociada con su validación en VERIFIED."))));
        when(missionMemory.evidenceRound("MISSION-B")).thenReturn(2);

        orchestrator.tick();

        assertEquals(OrchestratorStatus.FAILED, lastSaved().status());
        verify(products).changeStatus(eq("P1"), eq(CatalogStatus.IDEA), contains("VERIFIED"), eq("orchestrator"));
    }

    @Test
    void aProductWhoseRunFailedInTheLastDayIsNotChosenAgain() {
        when(products.list()).thenReturn(List.of(
                view(product("P1", CatalogStatus.IDEA, "product", "SERVICE", 5), List.of()),
                view(product("P2", CatalogStatus.IDEA, "product", "SERVICE", 1), List.of())));
        when(runs.failedProductsSince(any())).thenReturn(java.util.Set.of("P1"));

        orchestrator.tick();

        assertEquals("P2", lastSaved().productId());
    }

    // --- Revisión en vivo (2026-09-29): 3 ciclos seguidos fallaron por la ficha con costo 0 y cada uno lanzó una discovery ---

    @Test
    void theSheetIsAskedUpToThreeTimes() {
        var p = product("P1", CatalogStatus.IDEA, "product", "SOFTWARE", 1);
        proposing(p);
        when(ceo.proposeProductSheet(anyString(), anyString(), anyString())).thenReturn(sheet("Pymes", 19, 0));

        orchestrator.tick();

        verify(ceo, times(3)).proposeProductSheet(anyString(), anyString(), anyString());
        assertEquals(OrchestratorStatus.FAILED, lastSaved().status());
    }

    @Test
    void twoFailedCyclesInARowPauseTheOrchestratorInsteadOfStartingAnother() {
        when(products.list()).thenReturn(List.of());
        when(runs.finishedStatusesSince(any(), eq(2)))
                .thenReturn(List.of(OrchestratorStatus.FAILED, OrchestratorStatus.FAILED));
        when(runs.latest()).thenReturn(Optional.of(new OrchestratorRun("RUN-0", OrchestratorStatus.FAILED, "P1", null, null,
                null, Instant.now(), Instant.now(), "la ficha quedó incompleta: costo 0")));

        orchestrator.tick();

        verify(runs, never()).create();
        verify(missions, never()).start(anyString(), anyString(), anyString(), any(), any());
        verify(policies).createVersion(eq(PolicyKey.ORCHESTRATOR_ENABLED), eq(0.0),
                argThat(r -> r.contains("2 ciclos seguidos") && r.contains("costo 0")));
        verify(events).publish(eq("EMPRESA_ORCHESTRATOR_PAUSED"), isNull(), isNull(), eq("orchestrator"), anyMap());
        verify(mail).send(contains("pausó"), contains("reanuda el orquestador"), eq(true));
    }

    @Test
    void aSingleFailureStillStartsANewCycle() {
        when(products.list()).thenReturn(List.of());
        when(runs.finishedStatusesSince(any(), eq(2)))
                .thenReturn(List.of(OrchestratorStatus.FAILED, OrchestratorStatus.READY));

        orchestrator.tick();

        verify(runs).create();
        verify(policies, never()).createVersion(any(), anyDouble(), anyString());
    }

    @Test
    void failuresAreCountedOnlySinceTheFounderLastTurnedItOn() {
        var resumedAt = Instant.parse("2026-09-29T20:00:00Z");
        when(policies.snapshot(PolicyKey.ORCHESTRATOR_ENABLED)).thenReturn(new com.aicompany.core.model.PolicySnapshot(
                "ORCHESTRATOR_ENABLED", 3, 1.0, "human", "Reanudado", resumedAt, List.of()));
        when(products.list()).thenReturn(List.of());
        when(runs.finishedStatusesSince(resumedAt, 2)).thenReturn(List.of());

        orchestrator.tick();

        verify(runs).create();
    }

    @Test
    void whenPausedTheViewCarriesTheReason() {
        when(policies.activeValue(PolicyKey.ORCHESTRATOR_ENABLED)).thenReturn(0.0);
        when(policies.snapshot(PolicyKey.ORCHESTRATOR_ENABLED)).thenReturn(new com.aicompany.core.model.PolicySnapshot(
                "ORCHESTRATOR_ENABLED", 4, 0.0, "human", "Pausado por 2 ciclos seguidos fallidos", Instant.now(), List.of()));
        when(runs.latest()).thenReturn(Optional.empty());

        assertEquals("Pausado por 2 ciclos seguidos fallidos", orchestrator.current().pauseReason());
    }

    // Revisión en vivo (2026-09-30): el nombre de la idea era la primera oración del plan de Luna ("Priorizar 3
    // candidatos…") y Engineering construyó una app para priorizar candidatos. La ficha define el producto concreto.
    @Test
    void aPlanLikeNameIsAskedAgainAndTheValidNameRenamesTheProduct() {
        var p = product("P1", CatalogStatus.IDEA, "product", "SOFTWARE", 1);
        proposing(p);
        var text = org.mockito.ArgumentCaptor.forClass(String.class);
        var plan = new ProductSheet("SOFTWARE", "Freelancers", List.of("WORLDWIDE"), List.of("en"), 19.0, false, 2.0, "",
                "Priorizar 3 candidatos de producto digital", "Elegir entre plantilla y generador.");
        var concrete = new ProductSheet("SOFTWARE", "Freelancers", List.of("WORLDWIDE"), List.of("en"), 19.0, false, 2.0,
                "", "Email Signature Generator", "Genera firmas de email profesionales en HTML listas para copiar.");
        when(ceo.proposeProductSheet(text.capture(), anyString(), anyString())).thenReturn(plan, concrete);

        orchestrator.tick();

        assertTrue(text.getAllValues().get(1).contains("CORRECCIÓN"), text.getAllValues().get(1));
        assertTrue(text.getAllValues().get(1).contains("nombre"), text.getAllValues().get(1));
        verify(products).update(eq("P1"), argThat(c -> "Email Signature Generator".equals(c.name())
                && c.description().startsWith("Genera firmas")), eq("orchestrator"));
        verify(missions).start(anyString(), argThat(i -> i.contains("Email Signature Generator")
                && i.contains("Genera firmas")), eq("PRODUCTION"), isNull(), eq("TEAM-ENGINEERING"));
    }

    @Test
    void aSheetWithoutNameOrDescriptionIsIncomplete() {
        var problems = ProductOrchestrator.sheetProblems(new ProductSheet("SOFTWARE", "Freelancers", List.of(), List.of(),
                19.0, false, 2.0, "", " ", null), product("P1", CatalogStatus.IDEA, "product", "SOFTWARE", 1));

        assertTrue(problems.stream().anyMatch(x -> x.contains("nombre")), problems.toString());
        assertTrue(problems.stream().anyMatch(x -> x.contains("descripción")), problems.toString());
    }

    @Test
    void planVerbsAreRejectedAsProductNames() {
        for (var name : List.of("Seleccionar un nicho de micro-SaaS", "Antes de definir el producto", "Validar demanda")) {
            var problems = ProductOrchestrator.sheetProblems(new ProductSheet("SOFTWARE", "Freelancers", List.of(),
                    List.of(), 19.0, false, 2.0, "", name, "Algo concreto."), product("P1", CatalogStatus.IDEA, "product",
                    "SOFTWARE", 1));
            assertTrue(problems.stream().anyMatch(x -> x.contains("nombre")), name + " → " + problems);
        }
    }
}
