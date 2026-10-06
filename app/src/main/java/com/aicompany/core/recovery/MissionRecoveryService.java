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
 * (Development Group se retoma; el resto queda INTERRUPTED), las INTERRUPTED del Development Group y las que fallaron (corrección automática con frenos). Una
 * misión de desarrollo a la vez. Nunca lanza: un error se registra y se sigue con la siguiente.
 * <p>
 * Las huérfanas solo se detectan en el pase de arranque ({@code pendingOrphans}; si ese pase falla, el siguiente lo
 * reintenta): un hilo vivo siempre se desregistra en su {@code finally}, así que no aparecen huérfanas después, y una
 * misión recién persistida en un estado de trabajo (antes de registrarse como viva) nunca se confunde con una.
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
    /** Revisión final (6a): mientras la detección de huérfanas no haya corrido entera, cada pase la reintenta. */
    private volatile boolean orphansCollected;
    /** Revisión final (6b): intentos fallidos de retomar cada huérfana; a los {@link #MAX_ORPHAN_ATTEMPTS} queda INTERRUPTED. */
    private final Map<String, Integer> orphanFailures = new java.util.concurrent.ConcurrentHashMap<>();
    static final int MAX_ORPHAN_ATTEMPTS = 3;
    /** Spec misiones durables §3: estados desde los que la corrección automática puede reclamar la ronda siguiente. */
    static final Set<MissionStatus> CORRECTABLE = EnumSet.of(MissionStatus.FAILED, MissionStatus.AWAITING_INVESTOR);
    private MissionService missionService;

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

    /**
     * Spec misiones durables §2: el chequeo de agentes encendidos de MissionService. {@code @Lazy}: MissionService depende
     * de MissionExecutor, que depende de este servicio (ciclo de beans). Opcional en tests.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMissionService(@org.springframework.context.annotation.Lazy MissionService missionService) {
        this.missionService = missionService;
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
            orphansCollected = true;
        } catch (Exception ex) {
            log.warn("RECOVERY - could not collect orphans (se reintenta en el próximo pase): {}", ex.getMessage());
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
        if (!orphansCollected) {
            collectOrphans();
        }
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
        orphanFailures.keySet().retainAll(pendingOrphans);
        var orphans = all.stream().filter(m -> pendingOrphans.contains(m.missionId()) && !executor.isLive(m.missionId()))
                .sorted(byAge).toList();
        var developmentBusy = all.stream().anyMatch(m -> DEVELOPMENT_TEAM.equals(m.teamId()) && executor.isLive(m.missionId()));

        if (developmentBusy) {
            return;
        }
        for (var m : orphans) {
            if (agentsOn(m, "retomar " + m.missionId()) && dispatchResume(m)) {
                return;
            }
        }
        // Spec §1: las INTERRUPTED del Development Group se retoman solas (las de discovery u otros equipos, a pedido).
        var interrupted = all.stream().filter(m -> m.status() == MissionStatus.INTERRUPTED
                && DEVELOPMENT_TEAM.equals(m.teamId()) && !executor.isLive(m.missionId())).sorted(byAge).toList();
        for (var m : interrupted) {
            if (agentsOn(m, "retomar " + m.missionId()) && dispatchResume(m)) {
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
        // Spec misiones durables §2: "con los chequeos de agentes encendidos de siempre" (todos los equipos).
        if (missionService != null) {
            missionService.requireAgentsOn(mission.teamId(), "reanudar " + missionId);
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

    /** Revisión 2026-10-06 §5: estados desde los que el fundador puede pedir la verificación humana (misión detenida). */
    static final Set<MissionStatus> VERIFIABLE = EnumSet.of(MissionStatus.BLOCKED, MissionStatus.FAILED,
            MissionStatus.AWAITING_INVESTOR, MissionStatus.INTERRUPTED);

    /**
     * Spec misiones durables, revisión 2026-10-06 §5 (manos humanas): "verifica MISSION-X". Un desarrollador corrigió el
     * código en el repositorio de la misión e hizo commit; se verifica el HEAD real sin llamar a ningún modelo (por eso no
     * exige agentes encendidos); verifyAsync pone en cero el contador de correcciones automáticas del incremento, así un
     * resultado FAILED arranca la corrección con todas sus rondas. Sincronizado con recoverAll: nunca corre junto a una corrección.
     */
    public synchronized void verify(String missionId) {
        var mission = missions.find(missionId).orElseThrow(() -> new IllegalArgumentException("No existe la misión " + missionId));
        if (executor.isLive(missionId)) {
            throw new IllegalStateException("MISSION " + missionId + " se está ejecutando: espera a que termine para verificarla.");
        }
        if (!VERIFIABLE.contains(mission.status())) {
            throw new IllegalStateException("MISSION " + missionId + " no está detenida (estado: " + mission.status()
                    + "); se verifica en BLOCKED, FAILED, AWAITING_INVESTOR o INTERRUPTED.");
        }
        if (!DEVELOPMENT_TEAM.equals(mission.teamId())) {
            throw new IllegalStateException("MISSION " + missionId + " no es del Development Group: no tiene código que verificar.");
        }
        if (workspace == null || workspace.headIfAny(missionId).isEmpty()) {
            throw new IllegalStateException("MISSION " + missionId + " no tiene repositorio con commits que verificar.");
        }
        if (missions.lastTeamPlanJson(missionId).isEmpty()) {
            throw new IllegalStateException("MISSION " + missionId + " no tiene un plan de Neo guardado: no se sabe qué verificar.");
        }
        // Revisión 2026-10-06 §5 (fix 5): el contador lo pone en cero verifyAsync, tras pasar a EVALUATING.
        executor.verifyAsync(missionId);
    }

    private DevelopmentWorkspaceService workspace;

    /** Revisión 2026-10-06 §5: para saber si la misión tiene repositorio (opcional en tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWorkspace(DevelopmentWorkspaceService workspace) {
        this.workspace = workspace;
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
            var missionId = mission.missionId();
            log.warn("RECOVERY - could not resume {}: {}", missionId, ex.getMessage());
            if (pendingOrphans.contains(missionId)
                    && orphanFailures.merge(missionId, 1, Integer::sum) >= MAX_ORPHAN_ATTEMPTS) {
                // Revisión final (6b): una huérfana que no se puede retomar no se reintenta para siempre en silencio.
                log.warn("RECOVERY - {} could not be resumed after {} attempts -> INTERRUPTED", missionId, MAX_ORPHAN_ATTEMPTS);
                pendingOrphans.remove(missionId);
                orphanFailures.remove(missionId);
                safely(missionId, () -> executor.interrupt(missionId));
            }
            return false;
        }
    }

    /** Spec misiones durables §2: nunca se llama al modelo de un agente apagado; la misión queda como está y se reintenta. */
    private boolean agentsOn(MissionResponse m, String what) {
        if (missionService == null) {
            return true;
        }
        try {
            missionService.requireAgentsOn(m.teamId(), what);
            return true;
        } catch (Exception ex) {
            log.info("RECOVERY - {} skipped: {}", m.missionId(), ex.getMessage());
            return false;
        }
    }

    boolean failedAndCorrectable(MissionResponse m) {
        return !executor.isLive(m.missionId()) && failedWithApprovedRequirements(m);
    }

    /** Development Group, FAILED (o AWAITING_INVESTOR con la validación FAILED) y requisitos aprobados; sin mirar el hilo. */
    private boolean failedWithApprovedRequirements(MissionResponse m) {
        if (!DEVELOPMENT_TEAM.equals(m.teamId())) {
            return false;
        }
        var failed = m.status() == MissionStatus.FAILED || (m.status() == MissionStatus.AWAITING_INVESTOR
                && missions.lastValidationStatus(m.missionId()).filter("FAILED"::equals).isPresent());
        return failed && requirements.approved(m.missionId()).isPresent();
    }

    /**
     * Revisión final (3): true si la corrección automática se va a ocupar de esta misión (falló, tiene requisitos aprobados
     * y ningún freno salta), para que el orquestador no pida su propia ronda en paralelo. No mira si el hilo sigue vivo
     * (el orquestador se entera mientras la misión termina) ni los agentes encendidos (si están apagados la corrección
     * espera a que vuelvan). Nunca lanza.
     */
    public boolean willCorrect(String missionId) {
        try {
            var mission = missions.find(missionId).orElse(null);
            if (mission == null || !failedWithApprovedRequirements(mission)) {
                return false;
            }
            var report = CorrectionReport.from(lastValidation(missionId), mission.message());
            return brake(missionId, Math.max(1, requirements.currentIncrement(missionId)), report.failing()).isEmpty();
        } catch (Exception ex) {
            log.warn("RECOVERY - could not evaluate the correction of {}: {}", missionId, ex.getMessage());
            return false;
        }
    }

    private boolean dispatchCorrection(MissionResponse listed) {
        var missionId = listed.missionId();
        try {
            // Revisión final (1b): se relee justo antes del freno y del CAS; entre el listado y acá el fundador o el
            // orquestador pudieron lanzar otra ronda. Nunca se bloquea una misión viva.
            var m = missions.find(missionId).orElse(null);
            if (m == null || !failedAndCorrectable(m)) {
                return false;
            }
            var current = missions.evidenceRound(missionId);
            var increment = Math.max(1, requirements.currentIncrement(missionId));
            var report = CorrectionReport.from(lastValidation(missionId), m.message());
            var brake = brake(missionId, increment, report.failing());
            if (brake.isPresent()) {
                if (!executor.isLive(missionId)) {
                    executor.block(missionId, brake.get() + "\n\nSigue fallando:\n" + report.text());
                }
                return false;
            }
            if (!agentsOn(m, "corregir " + missionId)) {
                return false;
            }
            if (!missions.claimNextRound(missionId, current, CORRECTABLE)) {
                return false;
            }
            var round = current + 1;
            // Revisión 2026-10-06 §2: sin avance, la ronda busca una estrategia nueva antes de correr. Se decide ANTES de
            // registrar la ronda: needsStrategy compara las rondas anteriores contra lo que falla ahora.
            var withStrategy = needsStrategy(missionId, increment, report.failing());
            recovery.addCorrectionRound(missionId, new CorrectionRound(round, increment, report.text(), report.failing(), false,
                    report.failures()));
            executor.correctAsync(missionId, round, report.text(), withStrategy);
            return true;
        } catch (Exception ex) {
            log.warn("RECOVERY - could not start the correction of {}: {}", missionId, ex.getMessage());
            return false;
        }
    }

    /** Spec §3: contador por incremento (desde la última ronda del fundador), y costo de la misión (el "sin avance" ya no frena: ver needsStrategy). */
    public Optional<String> brake(String missionId, int increment, int failingNow) {
        var auto = automaticRounds(missionId, increment);
        var max = (int) policies.activeValue(PolicyKey.MAX_AUTO_CORRECTIONS);
        if (auto.size() >= max) {
            return Optional.of("Se agotaron las " + max + " correcciones automáticas del incremento " + increment + ".");
        }
        var spent = cost.missionCost(missionId).totalUsd();
        var maxCost = policies.activeValue(PolicyKey.MAX_MISSION_COST_USD);
        if (spent >= maxCost) {
            return Optional.of(String.format(Locale.ROOT, "La misión gastó US$ %.2f en modelos (máximo US$ %.2f).", spent, maxCost));
        }
        return Optional.empty();
    }

    /** Revisión 2026-10-06 §2: dos rondas automáticas seguidas sin bajar lo que falla piden una estrategia nueva. */
    public boolean needsStrategy(String missionId, int increment, int failingNow) {
        var auto = automaticRounds(missionId, increment);
        if (auto.size() < 2) {
            return false;
        }
        var before = auto.get(auto.size() - 2).failing();
        var last = auto.get(auto.size() - 1).failing();
        return last >= before && failingNow >= last;
    }

    /** Rondas automáticas del incremento desde la última del fundador o la última verificación humana (revisión 2026-10-06 §5). */
    private List<CorrectionRound> automaticRounds(String missionId, int increment) {
        return CorrectionRound.automaticSinceFounder(recovery.correctionRounds(missionId), increment,
                recovery.counterResetAfter(missionId, increment));
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
        // Revisión 2026-10-06 §5: la última tarea es una verificación humana sin resultado → se cortó a mitad.
        var humanVerify = all.stream()
                .max(Comparator.comparing(AgentTask::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(t -> t.taskId().contains(MissionExecutor.HUMAN_VERIFY_SUFFIX) && t.validationStatus() == null)
                .map(AgentTask::taskId).orElse(null);
        return new MissionSnapshot(missionId, mission.teamId(), round, increment, correction,
                latest.map(RequirementsMemoryService.Version::status).orElse(null),
                requirements.approved(missionId).isPresent(), requirements.pendingAdjustment(missionId).orElse(null),
                planTask || !tasks.isEmpty(), tasks, humanVerify);
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
