package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;

public record CustomerCommand(
        @NotBlank String customerId,
        @NotBlank String name,
        String contact,
        String leadId,
        @NotBlank String evidenceDescription,
        String evidenceSource,
        @NotBlank String evidenceSourceType,
        boolean evidenceVerified
) {
}
