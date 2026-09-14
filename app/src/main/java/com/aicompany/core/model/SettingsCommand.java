package com.aicompany.core.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SettingsCommand(
        @NotBlank @Email String alertEmail
) {
}
