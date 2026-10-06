package com.aicompany.core.recovery;

import java.util.List;

/**
 * Spec misiones durables §2: lo guardado de una misión huérfana, armado por {@code MissionRecoveryService}.
 * {@code workTasks}: las tareas WORK de la ronda en curso; {@code incrementOfRound}: el incremento si la ronda es de
 * incremento ({@code incrementRoundsByNumber}), o {@code buildingIncrement} en la ronda 0; {@code correctionRound}:
 * la ronda está en {@code correctionRounds}.
 */
public record MissionSnapshot(String missionId, String teamId, int round, Integer incrementOfRound, boolean correctionRound,
                              String latestRequirementsStatus, boolean requirementsApproved, String pendingAdjustment,
                              boolean planForRound, List<RoundTask> workTasks) {
    public record RoundTask(String taskId, String status, String commitSha) { }
}
