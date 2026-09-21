package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;

public record AgentModelCommand(
        @NotBlank String model
) {
}
