package com.aicompany.core.service;

import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.MissionEventView;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bitácora (spec 2026-10-17): lo que un agente está haciendo de verdad, armado en Java desde las misiones vivas, sus
 * tareas y la bitácora. Lo usa el chat para no dejar que el modelo invente (Neo e Iris hablaron de una misión borrada).
 */
public final class AgentActivity {

    public static final ZoneOffset FOUNDER_ZONE = ZoneOffset.ofHours(-5);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm").withZone(FOUNDER_ZONE);
    private static final Set<MissionStatus> ENDED = Set.of(MissionStatus.COMPLETED, MissionStatus.CANCELLED,
            MissionStatus.FAILED, MissionStatus.INTERRUPTED);
    private static final String LEADER = "engineering";

    public record MissionState(MissionResponse mission, List<AgentTask> tasks, List<MissionEventView> events) {
        public MissionState {
            tasks = tasks == null ? List.of() : tasks;
            events = events == null ? List.of() : events;
        }
    }

    private AgentActivity() {
    }

    private static String stepOrEmpty(String step) {
        return step == null || step.isBlank() ? "" : " · " + step;
    }

    public static boolean live(MissionStatus s) {
        return s != null && !ENDED.contains(s);
    }

    public static boolean stopped(MissionStatus s) {
        return s == MissionStatus.BLOCKED || (s != null && s.name().startsWith("AWAITING_"));
    }

    public static String describe(String agentId, String agentName, List<MissionState> missions, Instant now) {
        var displayName = agentName == null ? agentId : agentName;
        var live = missions.stream().filter(m -> live(m.mission().status()))
                .sorted(Comparator.comparing((MissionState m) -> m.mission().updatedAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        var own = live.stream().filter(m -> m.tasks().stream().anyMatch(t -> agentId.equals(t.agentId()))).toList();
        if (own.isEmpty()) {
            if (LEADER.equals(agentId) && !live.isEmpty()) {
                var devMissions = live.stream()
                        .filter(m -> "TEAM-DEVELOPMENT".equals(m.mission().teamId()))
                        .map(m -> m.mission().missionId()).collect(Collectors.joining(", "));
                if (devMissions.isEmpty()) {
                    return displayName + " — libre: sin tareas en misiones activas.";
                }
                return displayName + " — sin tareas abiertas; coordina como líder técnico " + devMissions
                        + ": la corrección la reparte Java por dueño de archivo; Neo entra en el plan y en el triage de tests.";
            }
            return displayName + " — libre: sin tareas en misiones activas.";
        }
        var lines = new java.util.ArrayList<String>();
        for (var m : own) {
            var mission = m.mission();
            var hasNonTerminalTask = m.tasks().stream()
                    .filter(t -> agentId.equals(t.agentId()))
                    .anyMatch(t -> "RUNNING".equals(t.status()) || "PENDING".equals(t.status()));
            var agentNamePattern = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(displayName) + "(?![\\p{L}\\p{N}])");
            var working = !stopped(mission.status()) && hasNonTerminalTask
                    && mission.message() != null && agentNamePattern.matcher(mission.message()).find();
            var head = (lines.isEmpty() ? displayName + " — " : "") + (working ? "trabajando ahora en "
                    : stopped(mission.status()) ? "detenida, esperando al fundador: " : "en ") + mission.missionId()
                    + " (" + mission.status() + stepOrEmpty(mission.currentStep()) + ")";
            lines.add(head);
            m.tasks().stream().filter(t -> agentId.equals(t.agentId()))
                    .max(Comparator.comparing(AgentTask::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .ifPresent(t -> lines.add("  Última tarea: " + t.taskId() + " (" + t.action() + ", " + t.status()
                            + (t.updatedAt() == null ? "" : ", hace " + Math.max(0, Duration.between(t.updatedAt(), now).toMinutes())
                            + " min") + ")"));
            m.events().stream().filter(e -> agentId.equals(e.agentId())).findFirst()
                    .ifPresent(e -> lines.add("  Último evento: " + eventLine(e)));
        }
        return String.join("\n", lines);
    }

    public static String missionBlock(MissionState state, int maxEvents) {
        var m = state.mission();
        var events = state.events().stream().limit(maxEvents).map(e -> "- " + eventLine(e))
                .collect(Collectors.joining("\n"));
        return m.missionId() + ": " + m.status() + stepOrEmpty(m.currentStep())
                + (m.message() == null || m.message().isBlank() ? "" : " — " + m.message())
                + "\nBitácora (más reciente primero):\n" + (events.isEmpty() ? "- (sin bitácora: misión anterior al 2026-10-17)" : events);
    }

    public static String eventLine(MissionEventView e) {
        var time = e.at() == null ? "" : HOUR.format(e.at()) + " ";
        var name = (e.agentName() == null ? "Forjai" : e.agentName()) + ": ";
        var summary = e.summary() == null ? "" : e.summary();
        return time + name + summary;
    }
}
