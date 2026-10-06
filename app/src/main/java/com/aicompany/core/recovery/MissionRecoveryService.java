package com.aicompany.core.recovery;

import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.TaskIds;
import com.aicompany.core.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Spec misiones durables §1 y §3: al arrancar, al terminar cada misión y cada 5 minutos recupera, en orden, las huérfanas
 * (Development Group se retoma; el resto queda INTERRUPTED) y las que fallaron (corrección automática con frenos). Una
 * misión de desarrollo a la vez. Nunca lanza: un error se registra y se sigue con la siguiente.
 * <p>
 * Las huérfanas solo se detectan en el pase de arranque ({@code pendingOrphans}): un hilo vivo siempre se desregistra en
 * su {@code finally}, así que no aparecen huérfanas después, y una misión recién persistida en un estado de trabajo
 * (antes de registrarse como viva) nunca se confunde con una.
 */
@Service
public class MissionRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(MissionRecoveryService.class);
    static final String DEVELOPMENT_TEAM = "TEAM-DEVELOPMENT";
    static final Set<MissionStatus> WORKING = EnumSet.of(MissionStatus.CREATED, MissionStatus.PLANNING,
            MissionStatus.DELEGATING, MissionStatus.RESEARCHING, MissionStatus.WAITING_AGENT_RESULTS,
            MissionStatus.EVALUATING, MissionStatus.CONSOLIDATING, MissionStatus.EXECUTING);

    private final MissionMemoryService missions;
    private final RequirementsMemoryService requirements;
    private final RecoveryMemoryService recovery;
    private final MissionExecutor executor;
    private final CompanyPolicyService policies;
    private final ModelCostService cost;
    private final Executor dispatcher;
    private final List<String> resumed = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> pendingOrphans = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Autowired
    public MissionRecoveryService(MissionMemoryService missions, RequirementsMemoryService requirements,
                                  RecoveryMemoryService recovery, MissionExecutor executor, CompanyPolicyService policies,
                                  ModelCostService cost) {
        this(missions, requirements, recovery, executor, policies, cost,
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    var thread = new Thread(r, "mission-recovery");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    MissionRecoveryService(MissionMemoryService missions, RequirementsMemoryService requirements,
                           RecoveryMemoryService recovery, MissionExecutor executor, CompanyPolicyService policies,
                           ModelCostService cost, Executor dispatcher) {
        this.missions = missions;
        this.requirements = requirements;
        this.recovery = recovery;
        this.executor = executor;
        this.policies = policies;
        this.cost = cost;
        this.dispatcher = dispatcher;
    }

    /** Spec §1: después de CompanyMemoryInitializer (@Order(0)), en un hilo propio para no demorar el arranque. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void onStartup() {
        dispatcher.execute(() -> {
            try {
                log.info("RECOVERY - {} agent(s) WORKING -> IDLE", missions.resetWorkingAgents());
            } catch (Exception ex) {
                log.warn("RECOVERY - could not reset working agents: {}", ex.getMessage());
            }
            collectOrphans();
            recoverAll();
        });
    }

    /** Toda misión en estado de trabajo sin hilo vivo es huérfana; las que no son del Development Group quedan INTERRUPTED. */
    private synchronized void collectOrphans() {
        try {
            for (var m : missions.findAll(200)) {
                if (!WORKING.contains(m.status()) || executor.isLive(m.missionId())) {
                    continue;
                }
                if (DEVELOPMENT_TEAM.equals(m.teamId())) {
                    pendingOrphans.add(m.missionId());
                } else {
                    safely(m.missionId(), () -> executor.resumeAsync(m.missionId(),
                            new ResumePoint(ResumePoint.Stage.INTERRUPT, 0, ResumePoint.RoundKind.ORIGINAL, null)));
                }
            }
        } catch (Exception ex) {
            log.warn("RECOVERY - could not collect orphans: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void periodicCheck() {
        dispatcher.execute(this::recoverAll);
    }

    /** Gancho de MissionExecutor al llegar a AWAITING_INVESTOR, FAILED o BLOCKED. */
    public void onMissionFinished(String missionId) {
        // Corre en el hilo de la misión que termina, antes de que se desregistre: se despacha aparte y de nuevo a los 5 s.
        dispatcher.execute(this::recoverAll);
        java.util.concurrent.CompletableFuture.delayedExecutor(5, java.util.concurrent.TimeUnit.SECONDS, dispatcher)
                .execute(this::recoverAll);
    }

    public List<String> resumedSinceStart() {
        return List.copyOf(resumed);
    }

    /** Recorre las misiones en el orden del spec §1; lanza como máximo una de desarrollo. */
    public synchronized void recoverAll() {
        List<MissionResponse> all;
        try {
            all = missions.findAll(200);
        } catch (Exception ex) {
            log.warn("RECOVERY - could not list missions: {}", ex.getMessage());
            return;
        }
        var byAge = Comparator.comparing((MissionResponse m) -> missions.createdAt(m.missionId()).orElse(Instant.EPOCH));
        var stillWorking = new HashSet<String>();
        all.stream().filter(m -> WORKING.contains(m.status())).forEach(m -> stillWorking.add(m.missionId()));
        pendingOrphans.retainAll(stillWorking);
        var orphans = all.stream().filter(m -> pendingOrphans.contains(m.missionId()) && !executor.isLive(m.missionId()))
                .sorted(byAge).toList();
        var developmentBusy = all.stream().anyMatch(m -> DEVELOPMENT_TEAM.equals(m.teamId()) && executor.isLive(m.missionId()));

        if (developmentBusy) {
            return;
        }
        for (var m : orphans) {
            if (dispatchResume(m)) {
                return;
            }
        }
        var failed = all.stream().filter(this::failedAndCorrectable).sorted(byAge).toList();
        for (var m : failed) {
            if (dispatchCorrection(m)) {
                return;
            }
        }
    }

    /**
     * A pedido del fundador ("reanuda MISSION-X"): una INTERRUPTED del Development Group se retoma desde su ResumePoint;
     * una de discovery u otro equipo se relanza como la primera vez (ronda 0) o con el último pedido (ronda de evidencia).
     */
    public synchronized void resume(String missionId) {
        var mission = missions.find(missionId).orElseThrow(() -> new IllegalArgumentException("No existe la misión " + missionId));
        if (mission.status() != MissionStatus.INTERRUPTED) {
            throw new IllegalStateException("MISSION " + missionId + " no está INTERRUPTED (estado: " + mission.status() + ").");
        }
        if (executor.isLive(missionId)) {
            throw new IllegalStateException("MISSION " + missionId + " ya se está ejecutando.");
        }
        if (DEVELOPMENT_TEAM.equals(mission.teamId())) {
            if (!dispatchResume(mission)) {
                throw new IllegalStateException("No se pudo retomar " + missionId + " (ver el log).");
            }
            return;
        }
        var instruction = missions.instructionOf(missionId).orElse("");
        var round = missions.evidenceRound(missionId);
        if (round == 0) {
            executor.executeAsync(missionId, instruction);
        } else {
            var requests = missions.evidenceRequests(missionId);
            executor.reexecuteAsync(missionId, instruction, round,
                    requests.isEmpty() ? CeoService.NO_INVESTOR_COMMENT : requests.get(requests.size() - 1));
        }
        resumed.add(missionId);
    }

    private boolean dispatchResume(MissionResponse mission) {
        if (executor.isLive(mission.missionId())) {
            return false;
        }
        try {
            executor.resumeAsync(mission.missionId(), ResumePoint.of(snapshot(mission)));
            pendingOrphans.remove(mission.missionId());
            resumed.add(mission.missionId());
            return true;
        } catch (Exception ex) {
            log.warn("RECOVERY - could not resume {}: {}", mission.missionId(), ex.getMessage());
            return false;
        }
    }

    boolean failedAndCorrectable(MissionResponse m) {
        if (!DEVELOPMENT_TEAM.equals(m.teamId()) || executor.isLive(m.missionId())) {
            return false;
        }
        var failed = m.status() == MissionStatus.FAILED || (m.status() == MissionStatus.AWAITING_INVESTOR
                && missions.lastValidationStatus(m.missionId()).filter("FAILED"::equals).isPresent());
        return failed && requirements.approved(m.missionId()).isPresent();
    }

    private boolean dispatchCorrection(MissionResponse m) {
        var missionId = m.missionId();
        try {
            var increment = Math.max(1, requirements.currentIncrement(missionId));
            var report = CorrectionReport.from(lastValidation(missionId), m.message());
            var brake = brake(missionId, increment, report.failing());
            if (brake.isPresent()) {
                executor.block(missionId, brake.get() + "\n\nSigue fallando:\n" + report.text());
                return false;
            }
            var current = missions.evidenceRound(missionId);
            if (!missions.claimNextRound(missionId, current)) {
                return false;
            }
            var round = current + 1;
            recovery.addCorrectionRound(missionId, new CorrectionRound(round, increment, report.text(), report.failing(), false));
            executor.correctAsync(missionId, round, report.text());
            return true;
        } catch (Exception ex) {
            log.warn("RECOVERY - could not start the correction of {}: {}", missionId, ex.getMessage());
            return false;
        }
    }

    /** Spec §3: contador por incremento (desde la última ronda del fundador), costo de la misión y "sin avance". */
    public Optional<String> brake(String missionId, int increment, int failingNow) {
        var rounds = recovery.correctionRounds(missionId).stream().filter(r -> r.increment() == increment).toList();
        var lastFounder = -1;
        for (int i = 0; i < rounds.size(); i++) {
            if (rounds.get(i).byFounder()) {
                lastFounder = i;
            }
        }
        var auto = rounds.subList(lastFounder + 1, rounds.size()).stream().filter(r -> !r.byFounder()).toList();
        var max = (int) policies.activeValue(PolicyKey.MAX_AUTO_CORRECTIONS);
        if (auto.size() >= max) {
            return Optional.of("Se agotaron las " + max + " correcciones automáticas del incremento " + increment + ".");
        }
        var spent = cost.missionCost(missionId).totalUsd();
        var maxCost = policies.activeValue(PolicyKey.MAX_MISSION_COST_USD);
        if (spent >= maxCost) {
            return Optional.of(String.format(Locale.ROOT, "La misión gastó US$ %.2f en modelos (máximo US$ %.2f).", spent, maxCost));
        }
        if (auto.size() >= 2) {
            var before = auto.get(auto.size() - 2).failing();
            var last = auto.get(auto.size() - 1).failing();
            if (last >= before && failingNow >= last) {
                return Optional.of("Dos rondas de corrección seguidas sin avance (" + before + " → " + last + " → "
                        + failingNow + " cosas que fallan).");
            }
        }
        return Optional.empty();
    }

    MissionSnapshot snapshot(MissionResponse mission) {
        var missionId = mission.missionId();
        var round = missions.evidenceRound(missionId);
        var incrementRounds = requirements.incrementRoundsByNumber(missionId);
        Integer increment = incrementRounds != null && incrementRounds.containsKey(round) ? incrementRounds.get(round)
                : round == 0 ? Math.max(1, requirements.currentIncrement(missionId)) : null;
        var correction = recovery.correctionRound(missionId, round).isPresent();
        var latest = requirements.latest(missionId);
        var all = missions.tasks(missionId);
        var tasks = all.stream().filter(t -> "WORK".equals(t.kind()))
                .filter(t -> TaskIds.roundOf(t.taskId()) == round)
                .map(t -> new MissionSnapshot.RoundTask(t.taskId(), t.status(), t.commitSha())).toList();
        var planTask = all.stream().anyMatch(t -> "PLANNING".equals(t.kind())
                && "COMPLETED".equals(t.status()) && TaskIds.roundOf(t.taskId()) == round);
        return new MissionSnapshot(missionId, mission.teamId(), round, increment, correction,
                latest.map(RequirementsMemoryService.Version::status).orElse(null),
                requirements.approved(missionId).isPresent(), requirements.pendingAdjustment(missionId).orElse(null),
                planTask || !tasks.isEmpty(), tasks);
    }

    private Optional<AgentTask> lastValidation(String missionId) {
        return missions.tasks(missionId).stream().filter(t -> "VALIDATION".equals(t.kind()) && t.validationStatus() != null)
                .max(Comparator.comparing(AgentTask::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private void safely(String missionId, Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            log.warn("RECOVERY - {} failed: {}", missionId, ex.getMessage());
        }
    }
}
