package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.DecisionResponse;
import com.aicompany.core.model.FinancialCriteriaCommand;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.MissionStatusResponse;
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

    public MissionService(MissionMemoryService memory, MissionExecutor executor, CompanyEventPublisher events) {
        this.memory = memory;
        this.executor = executor;
        this.events = events;
    }

    public MissionResponse start(String missionId, String instruction, String environment, FinancialCriteriaCommand financialCriteria) {
        validateFinancialCriteria(financialCriteria);

        memory.ensureMission(missionId, instruction, environment, financialCriteria);

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
            Set.of(MissionStatus.AWAITING_INVESTOR, MissionStatus.FAILED);

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
                            + "en AWAITING_INVESTOR o FAILED (estado actual: "
                            + mission.get().status()
                            + ")"
            );
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

        return Optional.of(new DecisionResponse(
                decisionId,
                missionId,
                command.decision(),
                Instant.now()
        ));
    }

    /**
     * Solo se puede borrar una misión cuya orquestación ya terminó: los
     * threads de {@code MissionExecutor} de una misión en curso seguirían
     * escribiendo tareas y evidencia sobre nodos borrados.
     */
    private static final Set<MissionStatus> DELETABLE_STATUSES = Set.of(
            MissionStatus.AWAITING_INVESTOR,
            MissionStatus.FAILED,
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

        events.publish(
                "EMPRESA_MISSION_DELETED",
                missionId,
                null,
                "human",
                Map.of("previousStatus", mission.get().status().name())
        );

        return true;
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
