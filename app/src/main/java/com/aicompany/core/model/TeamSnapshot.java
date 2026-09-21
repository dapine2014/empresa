package com.aicompany.core.model;

import java.util.List;

public record TeamSnapshot(
        String teamId,
        String teamName,
        String status,
        String leaderAgentId,
        List<TeamMemberInfo> members
) {
}
