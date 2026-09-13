package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;

public record MissionCommand(
        @NotBlank String missionId,
        @NotBlank String instruction
) {}
