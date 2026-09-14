package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DecisionCommand(
        @NotNull InvestorDecision decision,
        @NotBlank String reasoning
) {
}
