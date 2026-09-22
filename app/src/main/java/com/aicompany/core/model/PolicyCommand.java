package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;

public record PolicyCommand(
        double value,
        @NotBlank String changeReason) {
}
