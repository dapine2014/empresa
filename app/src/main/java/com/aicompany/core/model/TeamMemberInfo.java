package com.aicompany.core.model;

import java.util.List;

public record TeamMemberInfo(
        String agentId,
        String name,
        String role,
        String roleCode,
        List<String> capabilities,
        String model
) {
}
