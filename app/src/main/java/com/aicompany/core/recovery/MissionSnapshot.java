package com.aicompany.core.recovery;

import java.util.List;

/**
 * Spec misiones durables §2: lo guardado de una misión huérfana, armado por {@code MissionRecoveryService}.
 * {@code workTasks}: las tareas WORK de la ronda en curso; {@code incrementOfRound}: el incremento si la ronda es de
 * incremento ({@code incrementRoundsByNumber}), o {@code buildingIncrement} en la ronda 0; {@code correctionRound}:
 * la ronda está en {@code correctionRounds}; {@code humanVerifyTask}: la verificación humana cortada (la última tarea de la
 * misión es una {@code -HUMAN-VERIFY-} sin validationStatus; revisión 2026-10-06 §5), o null.
 * <p>
 * Spec 2026-10-14 Kael §1.2: {@code designNeeded}: el último spec de Aria es APP o GAME (y Kael está disponible);
 * {@code designForLatestSpec}: hay un diseño DRAFT de Kael para esa versión del spec; {@code pendingDesignAdjustment}: el
 * ajuste a Kael reclamado y sin terminar, o null.
 * <p>
 * Revisión conjunta (spec 2026-10-15): {@code jointReviewPending}: hay diseño DRAFT para el spec DRAFT, la policy está
 * encendida y no hay una revisión cerrada para ese par de versiones.
 */
public record MissionSnapshot(String missionId, String teamId, int round, Integer incrementOfRound, boolean correctionRound,
                              String latestRequirementsStatus, boolean requirementsApproved, String pendingAdjustment,
                              boolean planForRound, List<RoundTask> workTasks, String humanVerifyTask,
                              boolean designNeeded, boolean designForLatestSpec, String pendingDesignAdjustment,
                              boolean jointReviewPending) {
    public record RoundTask(String taskId, String status, String commitSha) { }

    public MissionSnapshot(String missionId, String teamId, int round, Integer incrementOfRound, boolean correctionRound,
                           String latestRequirementsStatus, boolean requirementsApproved, String pendingAdjustment,
                           boolean planForRound, List<RoundTask> workTasks, String humanVerifyTask,
                           boolean designNeeded, boolean designForLatestSpec, String pendingDesignAdjustment) {
        this(missionId, teamId, round, incrementOfRound, correctionRound, latestRequirementsStatus, requirementsApproved,
                pendingAdjustment, planForRound, workTasks, humanVerifyTask, designNeeded, designForLatestSpec,
                pendingDesignAdjustment, false);
    }

    public MissionSnapshot(String missionId, String teamId, int round, Integer incrementOfRound, boolean correctionRound,
                           String latestRequirementsStatus, boolean requirementsApproved, String pendingAdjustment,
                           boolean planForRound, List<RoundTask> workTasks, String humanVerifyTask) {
        this(missionId, teamId, round, incrementOfRound, correctionRound, latestRequirementsStatus, requirementsApproved,
                pendingAdjustment, planForRound, workTasks, humanVerifyTask, false, false, null, false);
    }

    public MissionSnapshot(String missionId, String teamId, int round, Integer incrementOfRound, boolean correctionRound,
                           String latestRequirementsStatus, boolean requirementsApproved, String pendingAdjustment,
                           boolean planForRound, List<RoundTask> workTasks) {
        this(missionId, teamId, round, incrementOfRound, correctionRound, latestRequirementsStatus, requirementsApproved,
                pendingAdjustment, planForRound, workTasks, null);
    }
}
