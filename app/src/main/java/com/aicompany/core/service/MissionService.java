package com.aicompany.core.service;

import com.aicompany.core.config.AppProperties;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.DecisionResponse;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.MissionStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
    private final AppProperties appProperties;

    public MissionService(
            MissionMemoryService memory,
            MissionExecutor executor,
            CompanyEventPublisher events,
            AppProperties appProperties) {
        this.memory = memory;
        this.executor = executor;
        this.events = events;
        this.appProperties = appProperties;
    }

    public MissionResponse start(String missionId, String instruction, String environment) {
        memory.ensureMission(missionId, instruction, environment);

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

        Integer newEvidenceRound = null;

        if (command.decision() == InvestorDecision.REQUEST_MORE_EVIDENCE) {

            var incremented =
                    memory.incrementEvidenceRound(
                            missionId,
                            appProperties.maxEvidenceRounds()
                    );

            if (incremented.isEmpty()) {

                throw new IllegalStateException(
                        "La misión " + missionId + " ya alcanzó el límite de "
                                + appProperties.maxEvidenceRounds()
                                + " vueltas de evidencia adicional; usa APPROVE o REJECT."
                );
            }

            newEvidenceRound = incremented.get();
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

        } else {

            var instruction = memory.instructionOf(missionId).orElseThrow();

            executor.reexecuteAsync(missionId, instruction, newEvidenceRound, command.reasoning())
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            log.error("MISSION {} - evidence round async future failed", missionId, error);
                        } else {
                            log.info("MISSION {} - evidence round orchestration finished", missionId);
                        }
                    });
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
}
