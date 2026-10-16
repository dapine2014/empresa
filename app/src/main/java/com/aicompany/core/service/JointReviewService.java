package com.aicompany.core.service;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.JointReview;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.PolicyKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Revisión conjunta (spec 2026-10-15, decisión del fundador: "cuando se generan contradicciones el líder técnico decide
 * junto con el PO"). Java decide si aplica, en qué ronda se está y qué ajuste va a cada lado; Neo y Aria solo detectan
 * y deciden. Nunca deja la misión FAILED: si falla, queda registrada y la misión sigue a la aprobación del fundador.
 */
@Service
public class JointReviewService {

    private static final Logger log = LoggerFactory.getLogger(JointReviewService.class);
    public static final String PREFIX = "Decisión del líder técnico (Neo) con el PO (Aria):";
    public static final int ROUNDS = 2;
    public static final String ARIA_CHANGED_ADJUSTMENT =
            "Aria cambió las historias de usuario: rehaz el diseño sobre las HU actuales.";
    private static final String ARIA_CHANGED = "Aria cambió las historias de usuario";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Outcome(String status, List<JointReview.Contradiction> contradictions,
                          List<JointReview.Decision> decisions, String requirementsAdjustment, String designAdjustment,
                          String detail) {
        public boolean needsChanges() {
            return requirementsAdjustment != null || designAdjustment != null;
        }
    }

    private final JointReviewWriter writer;
    private final JointReviewMemoryService memory;
    private final RequirementsMemoryService requirements;
    private final InteractionDesignMemoryService designs;
    private final CompanyPolicyService policies;
    private final AgentAvailability availability;
    private final CompanyEventPublisher events;

    public JointReviewService(JointReviewWriter writer, JointReviewMemoryService memory,
                              RequirementsMemoryService requirements, InteractionDesignMemoryService designs,
                              CompanyPolicyService policies, AgentAvailability availability, CompanyEventPublisher events) {
        this.writer = writer;
        this.memory = memory;
        this.requirements = requirements;
        this.designs = designs;
        this.policies = policies;
        this.availability = availability;
        this.events = events;
    }

    public boolean enabled() {
        return policies.activeValue(PolicyKey.JOINT_REVIEW_ENABLED) >= 1.0;
    }

    public Outcome review(String missionId, RequirementsSpec spec, int specVersion, InteractionDesign design,
                          int designVersion, int round) {
        Outcome outcome;
        if (!enabled()) {
            outcome = new Outcome("SKIPPED", List.of(), List.of(), null, null, "La revisión conjunta está apagada (policy).");
        } else if (!availability.isEnabled(JointReviewWriter.NEO)) {
            outcome = new Outcome("SKIPPED", List.of(), List.of(), null, null, "Neo está apagado: no hubo revisión conjunta.");
        } else if (!availability.isEnabled(RequirementsWriter.ARIA)) {
            outcome = new Outcome("SKIPPED", List.of(), List.of(), null, null, "Aria está apagada: no hubo revisión conjunta.");
        } else {
            outcome = run(missionId, spec, design, round);
        }
        try {
            memory.save(missionId, round, specVersion, designVersion, outcome.status(),
                    JSON.writeValueAsString(outcome.contradictions()), JSON.writeValueAsString(outcome.decisions()),
                    outcome.detail());
            log.info("MISSION {} - joint review round={} status={} contradictions={}", missionId, round, outcome.status(),
                    outcome.contradictions().size());
            events.publish("EMPRESA_JOINT_REVIEW_COMPLETED", missionId, null, JointReviewWriter.NEO, Map.of(
                    "round", round, "status", outcome.status(), "contradictions", outcome.contradictions().size(),
                    "resolvedBy", Map.of(
                            "requirements", count(outcome.decisions(), JointReview.REQUIREMENTS),
                            "design", count(outcome.decisions(), JointReview.DESIGN))));
        } catch (RuntimeException ex) {
            log.warn("MISSION {} - joint review round={} could not be recorded: {}", missionId, round, ex.getMessage());
        }
        return outcome;
    }

    private Outcome run(String missionId, RequirementsSpec spec, InteractionDesign design, int round) {
        try {
            var founder = founderAdjustments(concat(requirements.instructions(missionId), designs.instructions(missionId)));
            var contradictions = writer.detect(missionId, spec, design, founder);
            if (contradictions.isEmpty()) {
                return new Outcome("COHERENT", List.of(), List.of(), null, null, "");
            }
            if (round >= ROUNDS) {
                return new Outcome("UNRESOLVED", contradictions, List.of(), null, null, "");
            }
            var decisions = writer.decide(missionId, spec, design, contradictions, founder);
            return new Outcome("RESOLVED", contradictions, decisions,
                    adjustment(contradictions, decisions, JointReview.REQUIREMENTS),
                    adjustment(contradictions, decisions, JointReview.DESIGN), "");
        } catch (MissionCancellations.MissionCancelledException ex) {
            throw ex; // la cancelación corta la misión; no es una revisión FAILED.
        } catch (RuntimeException ex) {
            log.warn("MISSION {} - joint review failed: {}", missionId, ex.getMessage());
            return new Outcome("FAILED", List.of(), List.of(), null, null,
                    "La revisión conjunta no se pudo completar: " + ex.getMessage());
        }
    }

    static List<String> founderAdjustments(List<String> instructions) {
        return instructions.stream().filter(i -> i != null && !i.isBlank())
                .filter(i -> !i.startsWith(PREFIX) && !i.startsWith(ARIA_CHANGED)).toList();
    }

    private static String adjustment(List<JointReview.Contradiction> contradictions, List<JointReview.Decision> decisions,
                                     String side) {
        var lines = decisions.stream().filter(d -> side.equals(d.side()))
                .map(d -> "- " + d.id() + " (" + refs(contradictions, d.id()) + "): " + d.change()).toList();
        return lines.isEmpty() ? null : PREFIX + "\n" + String.join("\n", lines);
    }

    private static String refs(List<JointReview.Contradiction> contradictions, String id) {
        return contradictions.stream().filter(c -> c.id().equals(id)).findFirst()
                .map(c -> String.join(", ", c.requirementRefs()) + " ↔ " + String.join(", ", c.designRefs()))
                .orElse("");
    }

    public String founderNote(List<Outcome> outcomes) {
        var parts = new ArrayList<String>();
        var resolved = outcomes.stream().filter(o -> "RESOLVED".equals(o.status())).toList();
        var decisions = resolved.stream().flatMap(o -> o.decisions().stream().map(d -> "%s (%s) → %s: %s".formatted(
                d.id(), refs(o.contradictions(), d.id()), JointReview.DESIGN.equals(d.side()) ? "diseño" : "HU", d.change())))
                .toList();
        if (!decisions.isEmpty()) {
            parts.add("Neo y Aria resolvieron " + decisions.size() + (decisions.size() == 1 ? " contradicción: "
                    : " contradicciones: ") + String.join("; ", decisions) + ".");
        }
        outcomes.stream().filter(o -> "UNRESOLVED".equals(o.status())).forEach(o -> parts.add("⚠ Sin resolver: "
                + o.contradictions().stream().map(c -> c.id() + " (" + c.requirementSays() + " ↔ " + c.designSays() + ")")
                .collect(Collectors.joining("; ")) + "."));
        outcomes.stream().filter(o -> "FAILED".equals(o.status()) || "SKIPPED".equals(o.status()))
                .filter(o -> !o.detail().isBlank()).forEach(o -> parts.add("⚠ " + o.detail()));
        if (parts.isEmpty() && outcomes.stream().anyMatch(o -> "COHERENT".equals(o.status()))) {
            parts.add("Neo y Aria revisaron HU y diseño: sin contradicciones.");
        }
        return String.join(" ", parts);
    }

    private static int count(List<JointReview.Decision> decisions, String side) {
        return (int) decisions.stream().filter(d -> side.equals(d.side())).count();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        var all = new ArrayList<String>(a);
        all.addAll(b);
        return all;
    }
}
