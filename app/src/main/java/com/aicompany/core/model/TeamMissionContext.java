package com.aicompany.core.model;

import com.aicompany.core.agent.model.TeamPlan;

public record TeamMissionContext(String missionId, String instruction, TeamSnapshot team, TeamPlan plan) {
}
