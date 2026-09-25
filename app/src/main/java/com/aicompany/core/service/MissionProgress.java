package com.aicompany.core.service;

import com.aicompany.core.model.MissionStatus;

/** Callback a MissionExecutor.advanceMission: la estrategia nunca persiste transiciones por su cuenta. */
@FunctionalInterface
public interface MissionProgress {
    void advance(MissionStatus status, int progress, String currentStep, String message);
}
