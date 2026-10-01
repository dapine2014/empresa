package com.aicompany.core.model;

import java.util.Locale;
import java.util.regex.Pattern;

/** Ids de tarea por ronda de evidencia (spec 2026-09-16, revisión 2026-09-27): ronda 0 sin sufijo, luego -R<n>. */
public final class TaskIds {

    private static final Pattern ROUND_SUFFIX = Pattern.compile("-R(\\d+)$");

    private TaskIds() {
    }

    public static String agentTask(String missionId, String agentId, int round) {
        return withRound(missionId + "-" + agentId.toUpperCase(Locale.ROOT), round);
    }

    /** Revisión de QA cuando el mismo agente también escribe los tests (spec 2026-10-01 §1). */
    public static String reviewTask(String missionId, String agentId, int round) {
        return withRound(missionId + "-" + agentId.toUpperCase(Locale.ROOT) + "-REVIEW", round);
    }

    public static String planTask(String missionId, String leaderId, int round) {
        return withRound(missionId + "-" + leaderId.toUpperCase(Locale.ROOT) + "-PLAN", round);
    }

    public static int roundOf(String taskId) {
        var matcher = ROUND_SUFFIX.matcher(taskId == null ? "" : taskId);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static String withRound(String base, int round) {
        return round <= 0 ? base : base + "-R" + round;
    }
}
