package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.CatalogProduct;
import com.aicompany.core.model.CatalogStatus;
import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.OrchestratorRun;
import com.aicompany.core.model.OrchestratorStatus;
import com.aicompany.core.model.OrchestratorStep;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.ProductCommand;
import com.aicompany.core.model.ProductSheet;
import com.aicompany.core.model.ProductView;
import com.aicompany.core.model.TaskIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orquestador del ciclo de producto (spec 2026-09-28). Visión del fundador: sin productos y sin propuesta del
 * inversionista, Forjai crea uno y lo deja listo para vender. Máquina de estados persistente (OrchestratorRun) que en
 * cada chequeo mira Neo4j y avanza como mucho un paso por ciclo; las decisiones del modelo (elegir, ficha, entrega) se
 * validan en Java con un fallback determinista. Nunca mueve un producto pausado o retirado.
 */
@Service
public class ProductOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ProductOrchestrator.class);
    static final String ACTOR = "orchestrator";
    private static final Set<MissionStatus> FINISHED = Set.of(MissionStatus.AWAITING_INVESTOR, MissionStatus.COMPLETED,
            MissionStatus.FAILED, MissionStatus.CANCELLED);
    private static final Set<MissionStatus> BROKEN = Set.of(MissionStatus.FAILED, MissionStatus.CANCELLED);

    static final String DISCOVERY_INSTRUCTION = "Identificar un producto o servicio digital pequeño que Forjai pueda crear "
            + "y vender online a clientes de cualquier país, con el capital disponible: evidencia web de demanda, precios "
            + "de referencia, costo estimado por venta, margen y punto de equilibrio.";

    public record OrchestratorView(OrchestratorRun run, List<OrchestratorStep> steps, boolean enabled) {
    }

    private final OrchestratorMemoryService runs;
    private final ProductService products;
    private final ProductMemoryService productMemory;
    private final ProductAutomation automation;
    private final MissionService missions;
    private final MissionMemoryService missionMemory;
    private final CeoService ceo;
    private final CompanyMemoryService companyMemory;
    private final CompanyPolicyService policies;
    private final CompanyEventPublisher events;
    private final AlertMailService mail;
    private final String defaultCeoModel;

    public ProductOrchestrator(OrchestratorMemoryService runs, ProductService products, ProductMemoryService productMemory,
                               ProductAutomation automation, MissionService missions, MissionMemoryService missionMemory,
                               CeoService ceo, CompanyMemoryService companyMemory, CompanyPolicyService policies,
                               CompanyEventPublisher events, AlertMailService mail,
                               @Value("${ollama.ceo-model}") String defaultCeoModel) {
        this.runs = runs;
        this.products = products;
        this.productMemory = productMemory;
        this.automation = automation;
        this.missions = missions;
        this.missionMemory = missionMemory;
        this.ceo = ceo;
        this.companyMemory = companyMemory;
        this.policies = policies;
        this.events = events;
        this.mail = mail;
        this.defaultCeoModel = defaultCeoModel;
    }

    public boolean enabled() {
        return policies.activeValue(PolicyKey.ORCHESTRATOR_ENABLED) >= 1;
    }

    public OrchestratorView current() {
        var latest = runs.latest().orElse(null);
        return new OrchestratorView(latest, latest == null ? List.of() : runs.steps(latest.id()), enabled());
    }

    public void onMissionFinished(String missionId) {
        tick();
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 120_000)
    public synchronized void tick() {
        if (!enabled()) {
            return;
        }
        var active = runs.active();
        if (active.isEmpty()) {
            maybeStart();
            return;
        }
        for (var run : active) {
            try {
                advance(run);
            } catch (Exception ex) {
                log.error("ORCHESTRATOR run {} failed to advance", run.id(), ex);
                fail(run, "Error inesperado: " + ex.getMessage());
            }
        }
    }

    private void maybeStart() {
        var catalog = products.list();
        var busy = catalog.stream().anyMatch(v -> v.product().status() == CatalogStatus.READY_TO_SELL
                || v.product().status() == CatalogStatus.IN_CONSTRUCTION);
        if (busy || runs.active().size() >= (int) policies.activeValue(PolicyKey.MAX_AUTONOMOUS_PRODUCTS)) {
            return;
        }
        var run = runs.create();
        step(run, "STARTED", "Forjai no tiene productos listos ni en construcción: arranca un ciclo.");
        events.publish("EMPRESA_ORCHESTRATOR_STARTED", null, null, ACTOR, Map.of("runId", run.id()));
        mail.send("Forjai: el orquestador empezó a crear un producto",
                "Forjai no tiene productos listos para vender ni en construcción, así que empezó un ciclo para crear uno. "
                        + "Te aviso cuando elija qué construir. Puedes pausarlo con \"pausa el orquestador\" en el chat.",
                false);
        advance(run);
    }

    private void advance(OrchestratorRun run) {
        ProductView view = null;
        if (run.productId() != null) {
            view = products.view(run.productId()).orElse(null);
            if (view == null) {
                fail(run, "El producto " + run.productId() + " ya no existe.");
                return;
            }
            var status = view.product().status();
            if (status == CatalogStatus.PAUSED || status == CatalogStatus.RETIRED) {
                finish(run, OrchestratorStatus.STOPPED, "El fundador " + (status == CatalogStatus.PAUSED ? "pausó" : "retiró")
                        + " el producto: el orquestador no lo mueve.", "EMPRESA_ORCHESTRATOR_STOPPED", null);
                return;
            }
        }
        switch (run.status()) {
            case CHOOSING -> choose(run);
            case DISCOVERING -> discovering(run);
            case PROPOSING -> propose(run, view);
            case BUILDING -> building(run, view);
            default -> { }
        }
    }

    private void choose(OrchestratorRun run) {
        var candidates = products.list().stream().map(ProductView::product)
                .filter(p -> p.status() == CatalogStatus.IDEA)
                .filter(p -> productMemory.evidence(p).demandWithWebEvidence())
                .toList();
        if (candidates.isEmpty()) {
            var missionId = "MISSION-ORQ-" + System.currentTimeMillis();
            var discovering = new OrchestratorRun(run.id(), OrchestratorStatus.DISCOVERING, null, missionId, null, null,
                    run.startedAt(), Instant.now(), null);
            runs.save(discovering);
            missions.start(missionId, DISCOVERY_INSTRUCTION, "PRODUCTION", null, null);
            missionMemory.markLaunchedBy(missionId, ACTOR);
            step(discovering, "DISCOVERING", "No hay ideas con evidencia de demanda: lanzó la discovery " + missionId + ".");
            return;
        }
        var founder = candidates.stream().filter(p -> ProductService.FOUNDER.equals(p.createdBy()))
                .max(Comparator.comparing(CatalogProduct::createdAt)).orElse(null);
        CatalogProduct chosen;
        String reason;
        if (founder != null) {
            chosen = founder;
            reason = "Idea propuesta por el fundador.";
        } else {
            var byId = candidates.stream().collect(Collectors.toMap(CatalogProduct::id, p -> p));
            CatalogProduct fromAlex = null;
            String alexReason = null;
            try {
                var choice = ceo.chooseProduct(candidatesText(candidates), ceoModel());
                fromAlex = choice == null ? null : byId.get(choice.productId());
                alexReason = choice == null ? null : choice.reason();
            } catch (Exception ex) {
                log.warn("ORCHESTRATOR choice by the CEO failed: {}", ex.getMessage());
            }
            if (fromAlex != null) {
                chosen = fromAlex;
                reason = alexReason;
            } else {
                chosen = candidates.stream().max(Comparator.<CatalogProduct>comparingInt(p -> p.validatedBy().size())
                        .thenComparing(CatalogProduct::createdAt)).orElseThrow();
                reason = "Regla de Java: la idea con más misiones de demanda (" + chosen.validatedBy().size()
                        + ") y, a igualdad, la más reciente.";
            }
        }
        var proposing = new OrchestratorRun(run.id(), OrchestratorStatus.PROPOSING, chosen.id(), run.discoveryMissionId(),
                null, reason, run.startedAt(), Instant.now(), null);
        runs.save(proposing);
        step(proposing, "CHOSE", chosen.name() + ": " + reason);
        events.publish("EMPRESA_ORCHESTRATOR_CHOSE", null, null, ACTOR,
                Map.of("runId", run.id(), "productId", chosen.id(), "reason", reason == null ? "" : reason));
        mail.send("Forjai eligió qué construir: " + chosen.name(), "El orquestador eligió \"" + chosen.name() + "\".\n\n"
                + "Motivo: " + reason + "\n\nAhora completa su ficha y lanza la construcción.", false);
    }

    private void discovering(OrchestratorRun run) {
        var mission = missionMemory.find(run.discoveryMissionId()).orElse(null);
        if (mission == null) {
            fail(run, "La misión de discovery " + run.discoveryMissionId() + " ya no existe.");
            return;
        }
        if (mission.status() == MissionStatus.CANCELLED) {
            founderRejected(run, mission.missionId());
            return;
        }
        if (BROKEN.contains(mission.status())) {
            fail(run, "La misión de discovery " + mission.missionId() + " falló.");
            return;
        }
        if (FINISHED.contains(mission.status())) {
            var choosing = run.with(OrchestratorStatus.CHOOSING);
            runs.save(choosing);
            step(choosing, "CHOOSING", "La discovery terminó: vuelve a elegir entre las ideas.");
        }
    }

    private void propose(OrchestratorRun run, ProductView view) {
        var p = view.product();
        ProductSheet sheet;
        try {
            sheet = ceo.proposeProductSheet(productText(p), evidenceText(p), ceoModel());
            var markets = sheet.markets() == null || sheet.markets().isEmpty() ? List.of("WORLDWIDE") : sheet.markets();
            var languages = sheet.languages() == null || sheet.languages().isEmpty() ? List.of("en", "es") : sheet.languages();
            products.update(p.id(), new ProductCommand(null, null, sheet.kind(), sheet.targetCustomer(), sheet.priceUsd(),
                    sheet.priceOnRequest(), sheet.estimatedCostUsd(), sheet.delivery(), markets, languages,
                    "Ficha completada por el orquestador"), ACTOR);
        } catch (Exception ex) {
            fail(run, "No se pudo completar la ficha de " + p.name() + ": " + ex.getMessage());
            return;
        }
        if (p.status() == CatalogStatus.IDEA) {
            products.changeStatus(p.id(), CatalogStatus.IN_CONSTRUCTION, "Construcción lanzada por el orquestador", ACTOR);
        }
        var kind = sheet.kind() == null ? p.kind() : sheet.kind();
        var team = "SERVICE".equals(kind) ? TeamMemoryService.TEAM_CREATIVE_PRODUCT_INTELLIGENCE
                : TeamMemoryService.TEAM_ENGINEERING;
        var missionId = "MISSION-ORQ-" + System.currentTimeMillis();
        // Idempotencia: el ciclo queda en BUILDING con la misión antes de lanzarla.
        var building = new OrchestratorRun(run.id(), OrchestratorStatus.BUILDING, p.id(), run.discoveryMissionId(),
                missionId, run.choiceReason(), run.startedAt(), Instant.now(), null);
        runs.save(building);
        missions.start(missionId, buildInstruction(p, sheet, kind), "PRODUCTION", null, team);
        missionMemory.markLaunchedBy(missionId, ACTOR);
        var builtBy = new ArrayList<>(p.builtBy());
        builtBy.add(missionId);
        products.linkMissions(p.id(), null, builtBy, ACTOR);
        step(building, "BUILDING", "Lanzó " + missionId + " (" + team + ") para construir " + p.name() + ".");
        events.publish("EMPRESA_ORCHESTRATOR_BUILDING", null, null, ACTOR,
                Map.of("runId", run.id(), "productId", p.id(), "missionId", missionId, "teamId", team));
    }

    private void building(OrchestratorRun run, ProductView view) {
        var mission = missionMemory.find(run.buildMissionId()).orElse(null);
        if (mission == null) {
            fail(run, "La misión de construcción " + run.buildMissionId() + " ya no existe.");
            return;
        }
        if (!FINISHED.contains(mission.status())) {
            return;
        }
        if (mission.status() == MissionStatus.CANCELLED) {
            founderRejected(run, mission.missionId());
            return;
        }
        var p = view.product();
        var broken = BROKEN.contains(mission.status());
        if (!broken) {
            if ("SERVICE".equals(p.kind())) {
                try {
                    var delivery = ceo.summarizeDelivery(productText(p), missionResults(run.buildMissionId()), ceoModel());
                    if (delivery != null && !delivery.isBlank()) {
                        products.update(p.id(), new ProductCommand(null, null, null, null, null, null, null, delivery, null,
                                null, "Entrega diseñada por el equipo Creative"), ACTOR);
                    }
                } catch (Exception ex) {
                    log.warn("ORCHESTRATOR delivery summary failed: {}", ex.getMessage());
                }
            }
            automation.buildFinished(run.buildMissionId());
        }
        var after = products.view(p.id()).orElse(view);
        if (after.product().status() == CatalogStatus.READY_TO_SELL) {
            ready(run, after.product());
            return;
        }
        var missing = new ArrayList<>(after.missing());
        if (missing.isEmpty() && !broken) {
            try {
                products.changeStatus(p.id(), CatalogStatus.READY_TO_SELL, "Cumple los requisitos", ACTOR);
                ready(run, after.product());
                return;
            } catch (IllegalArgumentException ex) {
                missing.add(ex.getMessage());
            }
        }
        if (broken) {
            missing.add(0, "La misión de construcción " + mission.missionId() + " falló.");
        }
        var max = (int) policies.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS);
        if (missionMemory.evidenceRound(run.buildMissionId()) < max) {
            var reason = "[Orquestador] Para que el producto quede listo para vender falta: " + String.join(" ", missing);
            missions.recordDecision(run.buildMissionId(), new DecisionCommand(InvestorDecision.REQUEST_MORE_EVIDENCE, reason));
            step(run, "ROUND", reason);
            return;
        }
        fail(run, "No quedó listo para vender tras las rondas de corrección: " + String.join(" ", missing));
    }

    /** El fundador rechazó la misión (REJECT → CANCELLED): es una decisión suya, no un fallo, y no se reintenta. */
    private void founderRejected(OrchestratorRun run, String missionId) {
        finish(run, OrchestratorStatus.STOPPED, "El fundador rechazó la misión " + missionId
                + ": el orquestador no la reintenta.", "EMPRESA_ORCHESTRATOR_STOPPED", null);
    }

    private void ready(OrchestratorRun run, CatalogProduct p) {
        finish(run, OrchestratorStatus.READY, p.name() + " quedó listo para vender.", "EMPRESA_ORCHESTRATOR_READY", null);
        mail.send("✅ Forjai: " + p.name() + " está listo para vender", "El orquestador terminó el ciclo: \"" + p.name()
                + "\" cumple los requisitos y quedó listo para vender. El siguiente paso es buscarle clientes.", false);
    }

    private void fail(OrchestratorRun run, String reason) {
        finish(run, OrchestratorStatus.FAILED, reason, "EMPRESA_ORCHESTRATOR_FAILED", reason);
        mail.send("⚠ Forjai: el orquestador no pudo terminar el producto", reason
                + "\n\nPuedes revisar el ciclo en la pantalla Productos o preguntar en el chat.", true);
    }

    private void finish(OrchestratorRun run, OrchestratorStatus status, String detail, String event, String failure) {
        var done = new OrchestratorRun(run.id(), status, run.productId(), run.discoveryMissionId(), run.buildMissionId(),
                run.choiceReason(), run.startedAt(), Instant.now(), failure);
        runs.save(done);
        step(done, status.name(), detail);
        events.publish(event, null, null, ACTOR, Map.of("runId", run.id(), "detail", detail));
    }

    private void step(OrchestratorRun run, String step, String detail) {
        runs.addStep(run.id(), step, detail);
        log.info("ORCHESTRATOR run={} step={} detail={}", run.id(), step, detail);
    }

    private String ceoModel() {
        return companyMemory.agentModel("ceo", defaultCeoModel);
    }

    private String candidatesText(List<CatalogProduct> candidates) {
        return candidates.stream().map(p -> "- " + p.id() + ": " + p.name() + " — " + (p.description() == null ? "" : p.description())
                        + " (misiones de demanda: " + p.validatedBy().size() + ")")
                .collect(Collectors.joining("\n"));
    }

    private static String productText(CatalogProduct p) {
        return p.name() + " (" + p.kind() + "). " + (p.description() == null ? "" : p.description())
                + (p.targetCustomer() == null ? "" : " Cliente objetivo: " + p.targetCustomer() + ".")
                + " Mercados: " + p.markets() + ".";
    }

    private String evidenceText(CatalogProduct p) {
        return p.validatedBy().stream().map(this::missionResults).collect(Collectors.joining("\n\n"));
    }

    private String missionResults(String missionId) {
        var text = missionMemory.tasks(missionId).stream()
                .filter(t -> "COMPLETED".equals(t.status()) && t.result() != null)
                .map(t -> t.agentId() + " (" + missionId + ", ronda " + TaskIds.roundOf(t.taskId()) + "): " + t.result())
                .collect(Collectors.joining("\n"));
        return text.length() > 12_000 ? text.substring(0, 12_000) + "…" : text;
    }

    private static String buildInstruction(CatalogProduct p, ProductSheet sheet, String kind) {
        var target = sheet.targetCustomer() == null ? p.targetCustomer() : sheet.targetCustomer();
        if ("SERVICE".equals(kind)) {
            return "Diseñar cómo se entrega el servicio \"" + p.name() + "\" de Forjai a " + target + ": pasos, entregables, "
                    + "plantillas y tiempos, listo para venderse online a clientes de cualquier país. Descripción: "
                    + p.description();
        }
        return "Construir el producto de software \"" + p.name() + "\" de Forjai para " + target + ", listo para venderse "
                + "online a clientes de cualquier país. Descripción: " + p.description();
    }
}
