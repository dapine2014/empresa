package com.aicompany.core.event;

import com.aicompany.core.model.MissionEventView;
import com.aicompany.core.recovery.CorrectionReport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Bitácora (spec 2026-10-17): lo que pasó en un incremento, armado por Java, para el plan de Neo. */
public final class MissionEventDigest {

    public static final String HEADER = "LO QUE PASÓ EN EL INCREMENTO ";

    private MissionEventDigest() {
    }

    public static String digest(List<MissionEventView> newestFirst, int increment) {
        var ordered = newestFirst.stream()
                .sorted(Comparator.comparing(MissionEventView::at, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
        // El tramo de N va del PRIMER INCREMENT_STARTED de N hasta el primer inicio de un N distinto (los relanzamientos
        // del mismo N se juntan); el 1 sin inicio propio cuenta desde el principio.
        int from = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (isStart(ordered.get(i)) && Integer.valueOf(increment).equals(ordered.get(i).increment())) {
                from = i + 1;
                break;
            }
        }
        if (from < 0) {
            if (increment != 1) {
                return "";
            }
            from = 0;
        }
        var span = new ArrayList<MissionEventView>();
        for (int i = from; i < ordered.size(); i++) {
            var e = ordered.get(i);
            if (isStart(e)) {
                if (!Integer.valueOf(increment).equals(e.increment())) {
                    break;
                }
                continue;
            }
            span.add(e);
        }
        if (span.isEmpty()) {
            return "";
        }
        var lines = new ArrayList<String>();
        var roundEvents = span.stream().filter(e -> type(e, "MISSION_CORRECTION_STARTED")).toList();
        var rounds = roundEvents.stream().map(MissionEventView::round).filter(java.util.Objects::nonNull)
                .map(String::valueOf).toList();
        rounds = rounds.subList(Math.max(0, rounds.size() - 10), rounds.size());
        lines.add("Rondas de corrección: " + roundEvents.size() + (rounds.isEmpty() ? "" : " (" + String.join(", ", rounds) + ")"));
        span.stream().filter(e -> type(e, "SANDBOX_VERIFICATION_COMPLETED")).filter(e -> e.summary() != null).reduce((a, b) -> b)
                .ifPresent(e -> lines.add("Resultado final: " + e.summary()));
        var byFingerprint = new LinkedHashMap<String, List<MissionEventView>>();
        span.stream().filter(e -> (type(e, "REPAIR_REQUESTED") || type(e, "SANDBOX_FINISHED")) && e.summary() != null)
                .forEach(e -> byFingerprint.computeIfAbsent(CorrectionReport.fingerprint(e.summary()), k -> new ArrayList<>()).add(e));
        var repeated = byFingerprint.values().stream().filter(l -> l.size() >= 2)
                .sorted(Comparator.comparingInt((List<MissionEventView> l) -> l.size()).reversed()).limit(5)
                .map(l -> l.size() + "× " + l.get(0).summary()).toList();
        if (!repeated.isEmpty()) {
            lines.add("Fallas repetidas:\n  - " + String.join("\n  - ", repeated));
        }
        var triage = span.stream().filter(e -> type(e, "TEST_TRIAGE_COMPLETED") && e.summary() != null)
                .map(MissionEventView::summary).toList();
        lines.addAll(triage.subList(Math.max(0, triage.size() - 5), triage.size()));
        Map<String, Long> repairs = span.stream().filter(e -> type(e, "REPAIR_REQUESTED"))
                .collect(Collectors.groupingBy(e -> e.agentName() == null ? "?" : e.agentName(), LinkedHashMap::new,
                        Collectors.counting()));
        if (!repairs.isEmpty()) {
            lines.add("Reparaciones por agente: " + repairs.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(x -> x.getKey() + " " + x.getValue()).collect(Collectors.joining(", ")));
        }
        return HEADER + increment + " (Java):\n" + String.join("\n", lines);
    }

    private static boolean isStart(MissionEventView e) {
        return type(e, "INCREMENT_STARTED");
    }

    private static boolean type(MissionEventView e, String suffix) {
        return ("EMPRESA_" + suffix).equals(e.type());
    }
}
