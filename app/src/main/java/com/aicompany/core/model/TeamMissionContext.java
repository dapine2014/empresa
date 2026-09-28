package com.aicompany.core.model;

import com.aicompany.core.agent.model.TeamPlan;

/** round > 0: ronda de evidencia sobre el mismo plan (spec 2026-09-16, revisión 2026-09-27). */
public record TeamMissionContext(String missionId, String instruction, TeamSnapshot team, TeamPlan plan, int round) {

    public TeamMissionContext(String missionId, String instruction, TeamSnapshot team, TeamPlan plan) {
        this(missionId, instruction, team, plan, 0);
    }
}
