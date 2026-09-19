package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;

public record DiscardLeadCommand(
        @NotBlank String reason
) {
}
