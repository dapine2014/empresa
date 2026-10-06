package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.DecisionResponse;
import com.aicompany.core.model.FinancialCriteriaCommand;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.MissionStatusResponse;
import com.aicompany.core.model.RequirementsDecisionCommand;
import com.aicompany.core.model.RequirementsView;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.agent.validation.RequirementsText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class MissionService {

    private static final Logger log = LoggerFactory.getLogger(MissionService.class);

    private final MissionMemoryService memory;
    private final MissionExecutor executor;
    private final CompanyEventPublisher events;
    private final TeamMemoryService teamMemory;
    private final DevelopmentWorkspaceService workspace;
    private final CompanyPolicyService policies;

    public MissionService(
            MissionMemoryService memory,
            MissionExecutor executor,
            CompanyEventPublisher events,
            TeamMemoryService teamMemory,
            DevelopmentWorkspaceService workspace) {
        this(memory, executor, events, teamMemory, workspace, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MissionService(
            MissionMemoryService memory,
            MissionExecutor executor,
            CompanyEventPublisher events,
            TeamMemoryService teamMemory,
            DevelopmentWorkspaceService workspace,
            CompanyPolicyService policies) {
        this.memory = memory;
        this.executor = executor;
        this.events = events;
        this.teamMemory = teamMemory;
        this.workspace = workspace;
        this.policies = policies;
    }

    private AgentAvailability agentAvailability;

    /** Decisión del fundador (2026-10-01): solo trabajan los agentes encendidos (opcional en tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAgentAvailability(AgentAvailability agentAvailability) {
        this.agentAvailability = agentAvailability;
    }

    /**
     * Agentes sin los cuales la misión no puede correr: discovery, los 6; un equipo, Alex y su líder (y Aria en el
     * Development Group). Público para la recuperación (spec misiones durables §2): retomar o corregir nunca llama al
     * modelo de un agente apagado.
     */
    public void requireAgentsOn(String teamId, String what) {
        requireAgentsOn(teamId, what, true);
    }

    private void requireAgentsOn(String teamId, String what, boolean needsAria) {
        if (agentAvailability == null) {
            return;
        }
        if (teamId == null) {
            agentAvailability.requireEnabled(AgentAvailability.DISCOVERY_AGENTS, what);
            return;
        }
        var team = teamMemory.snapshot(teamId);
        var required = new java.util.ArrayList<>(java.util.List.of(AgentAvailability.CEO, team.leaderAgentId()));
        if (needsAria && TeamMemoryService.teamType(teamId).map("DEVELOPMENT"::equals).orElse(false)) {
            required.add(AgentAvailability.PRODUCT_OWNER);
        }
        agentAvailability.requireEnabled(required, what);

    }

    public MissionResponse start(String missionId, String instruction, String environment, FinancialCriteriaCommand financialCriteria) {
        return start(missionId, instruction, environment, financialCriteria, null);
    }

    private com.aicompany.core.database.DatabaseConnectionService databaseConnections;

    /** Spec 2026-10-02 §1: conexiones de base de datos de la misión (opcional en tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDatabaseConnections(com.aicompany.core.database.DatabaseConnectionService databaseConnections) {
        this.databaseConnections = databaseConnections;
    }

    public MissionResponse start(
            String missionId,
            String instruction,
            String environment,
            FinancialCriteriaCommand financialCriteria,
            String teamId) {
        return start(missionId, instruction, environment, financialCriteria, teamId, java.util.List.of());
    }

    public MissionResponse start(
            String missionId,
            String instruction,
            String environment,
            FinancialCriteriaCommand financialCriteria,
            String teamId,
            java.util.List<String> databaseNames) {

        var databases = databaseNames == null ? java.util.List.<String>of() : databaseNames;
        if (databaseConnections != null && !databases.isEmpty()) {
            databaseConnections.requireExisting(databases);
        }

        validateFinancialCriteria(financialCriteria);
        validateTeam(teamId);
        requireAgentsOn(teamId, teamId == null ? "iniciar una discovery" : "iniciar una misión de " + teamId);

        memory.ensureMission(missionId, instruction, environment, financialCriteria, teamId);

        if (databaseConnections != null && !databases.isEmpty()) {
            databaseConnections.linkToMission(missionId, databases);
        }

        events.publishMission(
                "EMPRESA_MISSION_CREATED",
                missionId,
                "CREATED",
                0,
                "Creada",
                "Misión recibida"
        );

        executor.executeAsync(missionId, instruction)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        log.error("MISSION {} - async future failed", missionId, error);
                    } else {
                        log.info("MISSION {} - async orchestration finished", missionId);
                    }
                });

        return memory.find(missionId).orElseThrow();
    }

    private RequirementsMemoryService requirementsMemory;
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    /** Spec 2026-10-03 Aria: requisitos de Aria (opcional en tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRequirements(RequirementsMemoryService requirementsMemory) {
        this.requirementsMemory = requirementsMemory;
    }

    public Optional<RequirementsView> requirements(String missionId) {
        if (requirementsMemory == null) {
            return Optional.empty();
        }
        var mission = memory.find(missionId);
        if (mission.isEmpty()) {
            return Optional.empty();
        }
        var latest = requirementsMemory.latest(missionId);
        var spec = latest.map(v -> JSON.readValue(v.specJson(), RequirementsSpec.class)).orElse(null);
        return Optional.of(new RequirementsView(missionId, mission.get().status().name(),
                latest.map(RequirementsMemoryService.Version::version).orElse(null),
                latest.map(RequirementsMemoryService.Version::status).orElse(null),
                requirementsMemory.approved(missionId).map(RequirementsMemoryService.Version::version).orElse(null),
                requirementsMemory.builtIncrement(missionId),
                spec == null ? 0 : spec.incrementNumbers().size(), spec,
                spec == null ? "" : RequirementsText.summary(spec)));
    }

    public RequirementsView decideRequirements(String missionId, RequirementsDecisionCommand command) {
        var mission = memory.find(missionId)
                .orElseThrow(() -> new IllegalArgumentException("No existe la misión " + missionId));
        if (mission.status() != MissionStatus.AWAITING_SPEC_APPROVAL) {
            throw new IllegalStateException("MISSION " + missionId + " no está esperando la aprobación de requisitos "
                    + "(estado actual: " + mission.status() + ").");
        }
        var version = requirementsMemory.latest(missionId)
                .orElseThrow(() -> new IllegalStateException("MISSION " + missionId + " no tiene requisitos."));
        if (!"DRAFT".equals(version.status())) {
            // Review Focus 2: el hilo todavía no cambió el estado de la misión; la versión ya decidida lo delata.
            throw new IllegalStateException("La versión " + version.version() + " de los requisitos de " + missionId
                    + " ya está " + version.status() + ".");
        }
        if (command.decision() != RequirementsDecisionCommand.RequirementsDecision.REJECT) {
            // Revisión final Aria (8): aprobar llama a Neo y ajustar a Aria; ninguno puede estar apagado.
            requireAgentsOn(mission.teamId(), "decidir sobre los requisitos de " + missionId);
        }
        if (command.decision() == RequirementsDecisionCommand.RequirementsDecision.ADJUST
                && (command.instruction() == null || command.instruction().isBlank())) {
            throw new IllegalArgumentException("Para ajustar los requisitos escribe la instrucción para Aria.");
        }
        if (!requirementsMemory.claimDecision(missionId, version.version(),
                command.decision() == RequirementsDecisionCommand.RequirementsDecision.ADJUST ? command.instruction().strip() : null)) {
            // Revisión final Aria (12): doble clic; la primera decisión ya está en curso.
            throw new IllegalStateException("La versión " + version.version() + " de los requisitos de " + missionId
                    + " ya tiene una decisión en curso.");
        }
        switch (command.decision()) {
            case APPROVE -> {
                requirementsMemory.approve(missionId, version.version(), "founder");
                events.publish("EMPRESA_REQUIREMENTS_APPROVED", missionId, null, "human", Map.of("version", version.version()));
                executor.continueAfterApprovalAsync(missionId);
            }
            case ADJUST -> executor.adjustRequirementsAsync(missionId, command.instruction().strip());
            case REJECT -> {
                requirementsMemory.reject(missionId, version.version());
                memory.updateMission(missionId, MissionStatus.CANCELLED, 100, "Requisitos rechazados",
                        "El fundador rechazó los requisitos de Aria.");
                // Revisión final Aria (14): mismo aviso que el REJECT del inversionista.
                events.publishMission("EMPRESA_MISSION_UPDATED", missionId, MissionStatus.CANCELLED.name(), 100,
                        "Requisitos rechazados", "El fundador rechazó los requisitos de Aria.");
                events.publish("EMPRESA_REQUIREMENTS_REJECTED", missionId, null, "human", Map.of("version", version.version()));
            }
        }
        return requirements(missionId).orElseThrow();
    }

    private static final Set<MissionStatus> STOPPED = Set.of(MissionStatus.AWAITING_INVESTOR, MissionStatus.COMPLETED,
            MissionStatus.FAILED);

    public RequirementsView buildIncrement(String missionId, int increment) {
        var mission = memory.find(missionId)
                .orElseThrow(() -> new IllegalArgumentException("No existe la misión " + missionId));
        var approved = requirementsMemory.approved(missionId)
                .orElseThrow(() -> new IllegalStateException("MISSION " + missionId + " no tiene requisitos aprobados."));
        var spec = JSON.readValue(approved.specJson(), RequirementsSpec.class);
        if (!spec.incrementNumbers().contains(increment)) {
            throw new IllegalStateException("MISSION " + missionId + " no tiene incremento " + increment
                    + " (tiene " + spec.incrementNumbers() + ").");
        }
        var built = requirementsMemory.builtIncrement(missionId);
        if (increment != built + 1) {
            throw new IllegalStateException("El incremento " + increment + " va después del " + (increment - 1)
                    + "; el último construido es el " + built + ".");
        }
        if (!STOPPED.contains(mission.status())) {
            throw new IllegalStateException("MISSION " + missionId + " está en curso (" + mission.status() + ").");
        }
        requireAgentsOn(mission.teamId(), "construir el incremento " + increment + " de " + missionId);
        var lastRound = memory.evidenceRound(missionId);
        if (!memory.claimNextRound(missionId, lastRound, STOPPED)) {
            // Revisión final Aria (12): doble clic; el primer pedido ya lanzó la ronda.
            throw new IllegalStateException("MISSION " + missionId + " ya arrancó otra ronda; espera a que termine.");
        }
        executor.buildIncrementAsync(missionId, increment, lastRound + 1);
        return requirements(missionId).orElseThrow();
    }

    /**
     * Rondas de evidencia ya usadas contra {@code MAX_EVIDENCE_ROUNDS}. Revisión final Aria (1): los incrementos ocupan
     * números de ronda (ids -R<n>) pero no consumen el límite.
     */
    public int evidenceRoundsUsed(String missionId) {
        return memory.evidenceRound(missionId)
                - (requirementsMemory == null ? 0 : requirementsMemory.incrementRounds(missionId))
                // Spec misiones durables §3: las correcciones tampoco consumen el límite.
                - (recoveryMemory == null ? 0 : recoveryMemory.correctionRounds(missionId).size());
    }

    private com.aicompany.core.recovery.RecoveryMemoryService recoveryMemory;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecoveryMemory(com.aicompany.core.recovery.RecoveryMemoryService recoveryMemory) {
        this.recoveryMemory = recoveryMemory;
    }

    public Optional<MissionResponse> status(String missionId) {
        return memory.find(missionId);
    }

    /**
     * Misiones recientes para el panel "Missions" del Command Center web
     * — límite fijo, v1 no expone paginación.
     */
    public List<MissionResponse> list() {
        return memory.findAll(50);
    }

    public Optional<MissionStatusResponse> details(String missionId) {
        return memory.find(missionId)
                .map(mission -> new MissionStatusResponse(
                        mission,
                        memory.tasks(missionId)
                ));
    }

    /**
     * Solo se puede decidir sobre una misión que ya terminó su
     * orquestación (con resultado, parcial o total) o que falló del todo
     * -- no tiene sentido "aprobar" una misión que todavía está
     * corriendo agentes.
     */
    private static final Set<MissionStatus> DECIDABLE_STATUSES =
            Set.of(MissionStatus.AWAITING_INVESTOR, MissionStatus.FAILED, MissionStatus.BLOCKED);

    public Optional<DecisionResponse> recordDecision(
            String missionId,
            DecisionCommand command) {

        var mission = memory.find(missionId);

        if (mission.isEmpty()) {
            return Optional.empty();
        }

        if (!DECIDABLE_STATUSES.contains(mission.get().status())) {
            throw new IllegalStateException(
                    "Solo se puede registrar una decisión sobre una misión "
                            + "en AWAITING_INVESTOR, FAILED o BLOCKED (estado actual: "
                            + mission.get().status()
                            + ")"
            );
        }

        // Spec evidence-rounds (revisión 2026-09-27): el límite se chequea antes de escribir nada.
        Integer nextRound = null;
        var blockedIndication = false;
        if (command.decision() == InvestorDecision.REQUEST_MORE_EVIDENCE) {
            if (policies == null) {
                throw new IllegalStateException("Falta CompanyPolicyService");
            }
            var lastRound = memory.evidenceRound(missionId);
            var current = evidenceRoundsUsed(missionId);
            var max = (int) policies.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS);
            blockedIndication = mission.get().status() == MissionStatus.BLOCKED;
            // Spec misiones durables §3: la indicación sobre una BLOCKED no consume rondas de evidencia.
            if (!blockedIndication && current >= max) {
                throw new IllegalStateException("MISSION " + missionId + " ya usó " + current + " de " + max
                        + " vueltas de evidencia: decide con APPROVE o REJECT (o sube MAX_EVIDENCE_ROUNDS en Settings).");
            }
            nextRound = lastRound + 1;
            // Revisión final Aria (16): una misión anterior a Aria reutiliza el plan de Neo y no la necesita.
            requireAgentsOn(memory.teamId(missionId).orElse(null), "pedir más evidencia sobre " + missionId,
                    requirementsMemory == null || !requirementsMemory.predatesRequirements(missionId));
            if (!memory.claimNextRound(missionId, lastRound, DECIDABLE_STATUSES)) {
                // Revisión final Aria (12): doble clic; el primer pedido ya lanzó la ronda y este no escribe nada.
                throw new IllegalStateException("MISSION " + missionId + " ya arrancó otra ronda; espera a que termine.");
            }
        }

        var decisionId = missionId + "-DECISION-" + Instant.now().toEpochMilli();

        memory.recordDecision(
                missionId,
                decisionId,
                command.decision(),
                command.reasoning()
        );

        var newStatus = switch (command.decision()) {
            case APPROVE -> MissionStatus.COMPLETED;
            case REJECT -> MissionStatus.CANCELLED;
            case REQUEST_MORE_EVIDENCE -> null;
        };

        if (newStatus != null) {

            memory.updateMission(
                    missionId,
                    newStatus,
                    100,
                    "Decisión del inversionista",
                    command.reasoning()
            );

            events.publishMission(
                    "EMPRESA_MISSION_UPDATED",
                    missionId,
                    newStatus.name(),
                    100,
                    "Decisión del inversionista",
                    command.reasoning()
            );
        }

        events.publish(
                "EMPRESA_MISSION_DECISION_RECORDED",
                missionId,
                null,
                "human",
                Map.of(
                        "decision", command.decision().name(),
                        "reasoning", command.reasoning()
                )
        );

        if (nextRound != null && blockedIndication) {
            var round = nextRound;
            var lastValidation = memory.tasks(missionId).stream()
                    .filter(t -> "VALIDATION".equals(t.kind()) && t.validationStatus() != null)
                    .max(java.util.Comparator.comparing(com.aicompany.core.model.AgentTask::updatedAt,
                            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
            var report = com.aicompany.core.recovery.CorrectionReport.from(lastValidation, mission.get().message());
            var request = "INDICACIÓN DEL FUNDADOR: " + command.reasoning() + "\n\n" + report.text();
            var increment = requirementsMemory == null ? 1 : Math.max(1, requirementsMemory.currentIncrement(missionId));
            if (recoveryMemory != null) {
                recoveryMemory.addCorrectionRound(missionId,
                        new com.aicompany.core.recovery.CorrectionRound(round, increment, request, report.failing(), true));
            }
            executor.correctAsync(missionId, round, request)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            log.error("MISSION {} - correction round {} failed", missionId, round, error);
                        }
                    });
        } else if (nextRound != null) {
            var round = nextRound;
            executor.reexecuteAsync(missionId, memory.instructionOf(missionId).orElse(""), round, command.reasoning())
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            log.error("MISSION {} - evidence round {} failed", missionId, round, error);
                        }
                    });
        }

        return Optional.of(new DecisionResponse(
                decisionId,
                missionId,
                command.decision(),
                Instant.now(),
                nextRound
        ));
    }

    /**
     * Solo se puede borrar una misión cuya orquestación ya terminó: los
     * threads de {@code MissionExecutor} de una misión en curso seguirían
     * escribiendo tareas y evidencia sobre nodos borrados.
     */
    private static final Set<MissionStatus> DELETABLE_STATUSES = Set.of(
            MissionStatus.AWAITING_INVESTOR,
            MissionStatus.AWAITING_SPEC_APPROVAL,
            MissionStatus.FAILED,
            MissionStatus.INTERRUPTED,
            MissionStatus.BLOCKED,
            MissionStatus.COMPLETED,
            MissionStatus.CANCELLED
    );

    /** Misión fundacional real (`docs/MISSION-001.md`) — nunca se borra desde la API. */
    private static final String FOUNDATIONAL_MISSION_ID = "MISSION-001";

    /**
     * Borrado real (no soft-delete) de una misión y todo lo que la
     * orquestación colgó de ella — pensado para limpiar misiones de
     * prueba mientras se pulen prompts. Devuelve {@code false} si la
     * misión no existe.
     */
    public boolean delete(String missionId) {

        var mission = memory.find(missionId);

        if (mission.isEmpty()) {
            return false;
        }

        if (FOUNDATIONAL_MISSION_ID.equals(missionId)) {
            throw new IllegalStateException(
                    FOUNDATIONAL_MISSION_ID + " es la misión fundacional y no se puede borrar");
        }

        if (!DELETABLE_STATUSES.contains(mission.get().status())) {
            throw new IllegalStateException(
                    "No se puede borrar una misión en curso (estado actual: "
                            + mission.get().status()
                            + ")"
            );
        }

        if (memory.hasRealCustomerData(missionId)) {
            throw new IllegalStateException(
                    "La misión " + missionId + " tiene clientes o ventas reales registrados; no se puede borrar");
        }

        memory.deleteMission(missionId);

        try {
            workspace.deleteWorkspace(missionId);
        } catch (Exception ex) {
            // Neo4j ya quedó limpio; el directorio huérfano no debe revertir el borrado.
            log.warn("MISSION {} - no se pudo borrar el workspace: {}", missionId, ex.getMessage());
        }

        events.publish(
                "EMPRESA_MISSION_DELETED",
                missionId,
                null,
                "human",
                Map.of("previousStatus", mission.get().status().name())
        );

        return true;
    }

    /**
     * teamId es opcional; si viene, tiene que ser uno de los 3 equipos del
     * catálogo fijo, existir en Neo4j con status ACTIVE y tener líder y
     * miembros reales (spec §1). Nunca lo decide ni lo corrige un modelo.
     */
    private void validateTeam(String teamId) {

        if (teamId == null) {
            return;
        }

        if (!TeamMemoryService.KNOWN_TEAM_IDS.contains(teamId)) {
            throw new IllegalArgumentException("teamId desconocido: " + teamId
                    + ". Valores válidos: " + TeamMemoryService.KNOWN_TEAM_IDS);
        }

        var team = teamMemory.snapshot(teamId);

        if (team == null || !"ACTIVE".equals(team.status())) {
            throw new IllegalArgumentException("El equipo " + teamId + " no existe o no está ACTIVE en Company Memory.");
        }

        if (team.leaderAgentId() == null || team.members().isEmpty()) {
            throw new IllegalArgumentException("El equipo " + teamId + " no tiene líder o miembros en Company Memory.");
        }
    }

    private void validateFinancialCriteria(FinancialCriteriaCommand financialCriteria) {

        if (financialCriteria == null) {
            return;
        }

        if (financialCriteria.metric() == null) {
            throw new IllegalArgumentException(
                    "financialCriteria.metric es obligatorio si se declara un objetivo financiero");
        }

        if (financialCriteria.targetAmount() <= 0) {
            throw new IllegalArgumentException("financialCriteria.targetAmount debe ser mayor a 0");
        }

        if (financialCriteria.deadline() != null && financialCriteria.deadline().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("financialCriteria.deadline no puede ser anterior a hoy");
        }
    }
}
