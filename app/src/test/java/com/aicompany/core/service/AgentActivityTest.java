package com.aicompany.core.service;

import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.MissionEventView;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Bitácora (spec 2026-10-17): el estado real de un agente, armado por Java para el chat. */
class AgentActivityTest {

    private static final Instant NOW = Instant.parse("2026-10-17T16:30:00Z");

    private static MissionResponse mission(String id, MissionStatus status, String step, String message, Instant at) {
        return new MissionResponse(id, status, "TEST", 50, step, message, at, null, "TEAM-DEVELOPMENT");
    }

    private static AgentTask task(String id, String agent, String status, Instant at) {
        return new AgentTask(id, "M", agent, "WORK", status, "", at, "WORK", null, null, List.of(), null, null);
    }

    private static MissionEventView event(String agentId, String name, String summary, Instant at) {
        return new MissionEventView("e-" + at, "EMPRESA_TASK_COMMITTED", at, agentId, name, null, summary, null, null);
    }

    @Test void workingNowWhenTheStepNamesTheAgent() {
        var pong = new AgentActivity.MissionState(mission("MISSION-PONG", MissionStatus.WAITING_AGENT_RESULTS,
                "Desarrollo por capas", "Kael (2/3) está generando [game]", NOW),
                List.of(task("MISSION-PONG-INTERACTION-DESIGN-R18", "interaction-design", "RUNNING",
                        NOW.minusSeconds(120))),
                List.of(event("interaction-design", "Kael", "Kael commiteó 1 archivo (game/Main.cs)", NOW.minusSeconds(600))));
        var text = AgentActivity.describe("interaction-design", "Kael", List.of(pong), NOW);
        assertTrue(text.startsWith("Kael — trabajando ahora en MISSION-PONG"), text);
        assertTrue(text.contains("WAITING_AGENT_RESULTS · Desarrollo por capas"), text);
        assertTrue(text.contains("MISSION-PONG-INTERACTION-DESIGN-R18 (WORK, RUNNING, hace 2 min)"), text);
        assertTrue(text.contains("11:20 Kael: Kael commiteó 1 archivo (game/Main.cs)"), text);   // 16:20 UTC = 11:20 UTC-5
    }

    // Review Focus 2: dos misiones vivas, la más reciente primero.
    @Test void twoLiveMissionsAreListedMostRecentFirst() {
        var old = new AgentActivity.MissionState(mission("MISSION-A", MissionStatus.EVALUATING, "Sandbox", "",
                NOW.minusSeconds(3600)), List.of(task("MISSION-A-BACKEND", "backend", "COMPLETED", NOW.minusSeconds(3600))),
                List.of());
        var recent = new AgentActivity.MissionState(mission("MISSION-B", MissionStatus.DELEGATING, "Delegación", "",
                NOW.minusSeconds(60)), List.of(task("MISSION-B-BACKEND", "backend", "PENDING", NOW.minusSeconds(60))),
                List.of());
        var text = AgentActivity.describe("backend", "Iris", List.of(old, recent), NOW);
        assertTrue(text.indexOf("MISSION-B") < text.indexOf("MISSION-A"), text);
        assertTrue(text.contains("MISSION-A-BACKEND (WORK, COMPLETED, hace 60 min)"), text);
    }

    @Test void stoppedMissionsSayTheyWaitForTheFounder() {
        var blocked = new AgentActivity.MissionState(mission("MISSION-C", MissionStatus.BLOCKED, "Bloqueada", "", NOW),
                List.of(task("MISSION-C-QA", "qa", "COMPLETED", NOW)), List.of());
        assertTrue(AgentActivity.describe("qa", "Vera", List.of(blocked), NOW).contains("detenida, esperando al fundador"));
    }

    // Review Focus 3: sin misiones vivas con tareas suyas → libre (aunque haya misiones terminadas).
    @Test void freeWhenNoLiveMissionHasItsTasks() {
        var done = new AgentActivity.MissionState(mission("MISSION-D", MissionStatus.COMPLETED, "Fin", "", NOW),
                List.of(task("MISSION-D-BACKEND", "backend", "COMPLETED", NOW)), List.of());
        var other = new AgentActivity.MissionState(mission("MISSION-E", MissionStatus.PLANNING, "Plan", "", NOW),
                List.of(task("MISSION-E-QA", "qa", "PENDING", NOW)), List.of());
        assertEquals("Iris — libre: sin tareas en misiones activas.",
                AgentActivity.describe("backend", "Iris", List.of(done, other), NOW));
    }

    @Test void neoCoordinatesWhenItsTeamHasALiveMission() {
        var pong = new AgentActivity.MissionState(mission("MISSION-PONG", MissionStatus.EVALUATING, "Sandbox", "", NOW),
                List.of(task("MISSION-PONG-QA", "qa", "RUNNING", NOW)), List.of());
        var text = AgentActivity.describe("engineering", "Neo", List.of(pong), NOW);
        assertTrue(text.startsWith("Neo — sin tareas abiertas; coordina como líder técnico MISSION-PONG"), text);
        assertTrue(text.contains("la corrección la reparte Java por dueño de archivo"), text);
    }

    @Test void missionBlockCarriesStateAndTheLastEvents() {
        var events = java.util.stream.IntStream.range(0, 30).mapToObj(i -> event("qa", "Vera", "evento " + i,
                NOW.minusSeconds(i * 60L))).toList();
        var block = AgentActivity.missionBlock(new AgentActivity.MissionState(mission("MISSION-PONG",
                MissionStatus.EVALUATING, "Sandbox", "Compilando", NOW), List.of(), events), 20);
        assertTrue(block.startsWith("MISSION-PONG: EVALUATING · Sandbox — Compilando"), block);
        assertTrue(block.contains("evento 0") && block.contains("evento 19") && !block.contains("evento 20"), block);
    }

    @Test void liveAndStopped() {
        assertTrue(AgentActivity.live(MissionStatus.BLOCKED));
        assertFalse(AgentActivity.live(MissionStatus.INTERRUPTED));
        assertTrue(AgentActivity.stopped(MissionStatus.AWAITING_INVESTOR));
        assertFalse(AgentActivity.stopped(MissionStatus.EVALUATING));
    }
}
