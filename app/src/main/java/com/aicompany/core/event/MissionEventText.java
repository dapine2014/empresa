package com.aicompany.core.event;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bitácora (spec 2026-10-17): el resumen en español de cada evento, armado por Java (nunca por un modelo). */
public final class MissionEventText {

    public static final int MAX_SUMMARY = 300;

    private MissionEventText() {
    }

    public static String summary(String eventType, String agentName, Map<String, Object> data) {
        var d = data == null ? Map.<String, Object>of() : data;
        var who = agentName == null || agentName.isBlank() ? "Forjai" : agentName;
        var text = switch (eventType == null ? "" : eventType) {
            case "EMPRESA_MISSION_UPDATED" -> "Misión → " + str(d, "status")
                    + (str(d, "currentStep").isEmpty() ? "" : ": " + str(d, "currentStep"))
                    + (str(d, "message").isEmpty() ? "" : " — " + str(d, "message"));
            case "EMPRESA_TASK_COMMITTED" -> {
                var files = list(d, "files");
                var shown = files.size() <= 3 ? String.join(", ", files) : String.join(", ", files.subList(0, 3)) + "…";
                yield who + " commiteó " + files.size() + (files.size() == 1 ? " archivo" : " archivos")
                        + (files.isEmpty() ? "" : " (" + shown + ")")
                        + (d.get("repairRound") == null ? "" : " — reparación " + d.get("repairRound"))
                        + (d.get("testTriageRound") == null ? "" : " — corrección del triage " + d.get("testTriageRound"));
            }
            case "EMPRESA_SANDBOX_STARTED" -> "Sandbox arrancó (vuelta " + d.getOrDefault("round", 0) + ")";
            case "EMPRESA_SANDBOX_FINISHED", "EMPRESA_SANDBOX_VERIFICATION_COMPLETED" -> sandbox(d);
            case "EMPRESA_MISSION_CORRECTION_STARTED" -> "Corrección automática ronda " + d.get("round");
            case "EMPRESA_REPAIR_REQUESTED" -> "Errores de compilación devueltos a " + who + ": " + d.get("errors")
                    + (num(d, "repeated") > 0 ? " (" + d.get("repeated") + " REPETIDOS)" : "")
                    + (list(d, "files").isEmpty() ? "" : " en " + String.join(", ", list(d, "files")));
            case "EMPRESA_TEST_TRIAGE_COMPLETED" -> d.get("error") != null
                    ? "Triage de " + who + " falló: " + d.get("error")
                    : "Triage de " + who + ": " + String.join("; ", list(d, "verdicts"));
            case "EMPRESA_TASK_ATTEMPT_REJECTED" -> "Intento " + d.get("attempt") + " de " + who + " rechazado: "
                    + String.join("; ", list(d, "reasons"));
            case "EMPRESA_DELIVERY_PACKAGED" -> "Entrega del incremento " + d.get("increment") + " (" + str(d, "status")
                    + "): " + str(d, "path");
            case "EMPRESA_DELIVERY_FAILED" -> "Entrega del incremento " + d.get("increment") + " falló: " + str(d, "error");
            case "EMPRESA_MODEL_SWITCHED" -> "Modelo de " + who + ": " + str(d, "from") + " → " + str(d, "to")
                    + " (" + str(d, "kind") + ")";
            default -> (eventType == null ? "EVENTO" : eventType.replaceFirst("^EMPRESA_", "").replace('_', ' '))
                    + " por " + who + detail(d);
        };
        text = text.replaceAll("\\s+", " ").strip();
        return text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY - 1) + "…";
    }

    /** Genérico: «message» si existe; si no, «result» (TASK_FAILED/TASK_RETRY lo publican vía publishTask), recortado. */
    private static String detail(Map<String, Object> d) {
        var text = !str(d, "message").isEmpty() ? str(d, "message") : str(d, "result");
        if (text.isEmpty()) {
            return "";
        }
        text = text.replaceAll("\\s+", " ");
        return " — " + (text.length() <= 200 ? text : text.substring(0, 199) + "…");
    }

    private static String sandbox(Map<String, Object> d) {
        var overall = str(d, "overall");
        if ("PASS".equals(overall)) {
            return "Sandbox PASS: " + d.getOrDefault("testsPassed", 0) + " tests";
        }
        var step = str(d, "failedStep");
        var head = "Sandbox " + overall + (step.isEmpty() ? "" : " en " + step);
        if (num(d, "compileErrors") > 0) {
            return head + ": " + d.get("compileErrors") + " errores de compilación";
        }
        if (d.get("testsPassed") != null || d.get("testsFailed") != null) {
            return head + ": " + d.getOrDefault("testsPassed", 0) + " tests OK, " + d.getOrDefault("testsFailed", 0)
                    + " fallan";
        }
        return head;
    }

    private static String str(Map<String, Object> d, String key) {
        var v = d.get(key);
        return v == null ? "" : String.valueOf(v).strip();
    }

    private static long num(Map<String, Object> d, String key) {
        return d.get(key) instanceof Number n ? n.longValue() : 0;
    }

    private static List<String> list(Map<String, Object> d, String key) {
        return d.get(key) instanceof List<?> l ? l.stream().filter(Objects::nonNull).map(String::valueOf).toList()
                : List.of();
    }
}
