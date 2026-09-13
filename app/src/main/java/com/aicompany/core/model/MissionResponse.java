package com.aicompany.core.model;

import java.time.Instant;

public record MissionResponse(
        String missionId,
        MissionStatus status,
        int progress,
        String currentStep,
        String message,
        Instant updatedAt
) {}
