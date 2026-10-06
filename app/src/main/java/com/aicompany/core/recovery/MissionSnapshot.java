package com.aicompany.core.recovery;

import java.util.List;

/**
 * Spec misiones durables §2: lo guardado de una misión huérfana, armado por {@code MissionRecoveryService}.
 * {@code workTasks}: las tareas WORK de la ronda en curso; {@code incrementOfRound}: el incremento si la ronda es de
 * incremento ({@code incrementRoundsByNumber}), o {@code buildingIncrement} en la ronda 0; {@code correctionRound}:
 * la ronda está en {@code correctionRounds}; {@code humanVerifyTask}: la verificación humana cortada (la última tarea de la
 * misión es una {@code -HUMAN-VERIFY-} sin validationStatus; revisión 2026-10-06 §5), o null.
 */
public record MissionSnapshot(String missionId, String teamId, int round, Integer incrementOfRound, boolean correctionRound,
                              String latestRequirementsStatus, boolean requirementsApproved, String pendingAdjustment,
                              boolean planForRound, List<RoundTask> workTasks, String humanVerifyTask) {
    public record RoundTask(String taskId, String status, String commitSha) { }

    public MissionSnapshot(String missionId, String teamId, int round, Integer incrementOfRound, boolean correctionRound,
                           String latestRequirementsStatus, boolean requirementsApproved, String pendingAdjustment,
                           boolean planForRound, List<RoundTask> workTasks) {
        this(missionId, teamId, round, incrementOfRound, correctionRound, latestRequirementsStatus, requirementsApproved,
                pendingAdjustment, planForRound, workTasks, null);
    }
}
