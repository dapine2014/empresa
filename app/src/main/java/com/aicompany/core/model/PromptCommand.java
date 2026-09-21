package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;

public record PromptCommand(
        String content,
        @NotBlank String changeReason
) {
}
