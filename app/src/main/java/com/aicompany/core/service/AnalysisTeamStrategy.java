package com.aicompany.core.service;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamExecutionResult;
import com.aicompany.core.model.TeamMissionContext;
import org.springframework.stereotype.Service;

/**
 * Creative / Product Intelligence y Marketing & Growth: las tareas WORK
 * del plan corren con el AgentRuntime actual (gates, reintento,
 * replanificación) vía AgentTaskBatchRunner. Sin capacidades nuevas.
 */
@Service
public class AnalysisTeamStrategy implements TeamExecutionStrategy {

    private final AgentTaskBatchRunner batchRunner;

    public AnalysisTeamStrategy(AgentTaskBatchRunner batchRunner) {
        this.batchRunner = batchRunner;
    }

    @Override
    public TeamExecutionMode mode() {
        return TeamExecutionMode.ANALYSIS;
    }

    @Override
    public TeamExecutionResult execute(TeamMissionContext context, MissionProgress progress) {

        var definitions = context.plan().workTasks().stream()
                .map(t -> new AgentTaskBatchRunner.AgentTaskDefinition(
                        t.agentId(), t.action(), t.objective(), TeamPlan.KIND_WORK))
                .toList();

        var outcomes = batchRunner.run(
                context.missionId(),
                context.instruction(),
                definitions,
                () -> progress.advance(
                        MissionStatus.WAITING_AGENT_RESULTS,
                        30,
                        "Trabajo del equipo",
                        "Los miembros de " + context.team().teamName() + " están trabajando en paralelo."));

        return new TeamExecutionResult.AgentOutcomes(outcomes);
    }
}
