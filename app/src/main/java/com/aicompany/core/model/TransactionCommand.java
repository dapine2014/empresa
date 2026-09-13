package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record TransactionCommand(
        @NotBlank String transactionId,
        @NotBlank String customerId,
        @NotBlank String description,
        @PositiveOrZero double revenueUsd,
        @PositiveOrZero double costUsd,
        @NotBlank String evidenceDescription,
        String evidenceSource,
        @NotBlank String evidenceSourceType,
        boolean evidenceVerified
) {
}
