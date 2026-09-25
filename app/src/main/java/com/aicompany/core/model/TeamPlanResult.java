package com.aicompany.core.model;

import com.aicompany.core.agent.model.TeamPlan;

/** Plan del líder ya validado + el equipo real sobre el que se validó. */
public record TeamPlanResult(TeamSnapshot team, TeamPlan plan) {
}
