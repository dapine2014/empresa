package com.aicompany.core.service;

import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamExecutionResult;
import com.aicompany.core.model.TeamMissionContext;

/** Cómo ejecuta un tipo de equipo las tareas del plan ya validado (spec §2). */
public interface TeamExecutionStrategy {

    TeamExecutionMode mode();

    TeamExecutionResult execute(TeamMissionContext context, MissionProgress progress);
}
