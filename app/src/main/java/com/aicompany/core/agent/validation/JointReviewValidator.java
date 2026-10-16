package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.JointReview;
import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Revisión conjunta (spec 2026-10-15): Java valida en código lo que Neo detecta y lo que Aria decide; el motivo vuelve
 * como CORRECCIÓN DEL INTENTO ANTERIOR. Nunca le pide a un modelo que se revise.
 */
public final class JointReviewValidator {

    public static final int MAX_CONTRADICTIONS = 10;
    private static final Set<String> SIDES = Set.of(JointReview.DESIGN, JointReview.REQUIREMENTS);

    private JointReviewValidator() {
    }

    public static List<String> validateDetection(JointReview.Detection detection, RequirementsSpec spec,
                                                 InteractionDesign design) {
        var errors = new ArrayList<String>();
        var contradictions = detection == null ? List.<JointReview.Contradiction>of() : detection.contradictions();
        if (contradictions.size() > MAX_CONTRADICTIONS) {
            errors.add("Máximo " + MAX_CONTRADICTIONS + " contradicciones (hay " + contradictions.size()
                    + "): junta las que tengan la misma causa.");
        }
        var requirementIds = requirementIds(spec);
        var designIds = designIds(design);
        var seen = new HashSet<String>();
        for (int i = 0; i < contradictions.size(); i++) {
            var c = contradictions.get(i);
            var expected = "CONTRA-%02d".formatted(i + 1);
            var label = c == null || c.id() == null ? expected : c.id();
            if (c == null) {
                errors.add(expected + ": contradicción vacía.");
                continue;
            }
            if (!expected.equals(c.id())) {
                errors.add("La contradicción " + (i + 1) + " debe tener id " + expected + " (tiene " + c.id() + ").");
            }
            if (c.id() != null && !seen.add(c.id())) {
                errors.add(c.id() + " está repetida.");
            }
            if (c.requirementRefs().isEmpty()) {
                errors.add(label + ": requirementRefs necesita al menos una HU-NN o ESC-NN.");
            }
            for (var ref : c.requirementRefs()) {
                if (!requirementIds.contains(ref)) {
                    errors.add(label + ": " + ref + " no existe en las HU (existen " + requirementIds + ").");
                }
            }
            if (c.designRefs().isEmpty()) {
                errors.add(label + ": designRefs necesita al menos un id del diseño.");
            }
            for (var ref : c.designRefs()) {
                if (!designIds.contains(ref)) {
                    errors.add(label + ": " + ref + " no existe en el diseño (existen " + designIds + ").");
                }
            }
            if (blank(c.requirementSays())) {
                errors.add(label + ": requirementSays vacío (qué dicen las HU).");
            }
            if (blank(c.designSays())) {
                errors.add(label + ": designSays vacío (qué dice el diseño).");
            }
            var proposal = c.proposal();
            if (proposal == null || !SIDES.contains(proposal.side())) {
                errors.add(label + ": proposal.side debe ser DESIGN o REQUIREMENTS.");
            }
            if (proposal == null || blank(proposal.change())) {
                errors.add(label + ": proposal.change vacío (el cambio concreto).");
            }
        }
        return errors;
    }

    public static List<String> validateResolution(JointReview.Resolution resolution,
                                                  List<JointReview.Contradiction> contradictions) {
        var errors = new ArrayList<String>();
        var decisions = resolution == null ? List.<JointReview.Decision>of() : resolution.decisions();
        var expected = new LinkedHashMap<String, Integer>();
        contradictions.forEach(c -> expected.put(c.id(), 0));
        for (var d : decisions) {
            if (d == null) {
                errors.add("Hay una decisión vacía.");
                continue;
            }
            if (!expected.containsKey(d.id())) {
                errors.add(d.id() + " no es una contradicción de Neo (son " + expected.keySet() + ").");
                continue;
            }
            if (expected.merge(d.id(), 1, Integer::sum) == 2) {
                errors.add(d.id() + " está decidida dos veces: una sola decisión por contradicción.");
            }
            if (!SIDES.contains(d.side())) {
                errors.add(d.id() + ": side debe ser DESIGN o REQUIREMENTS.");
            }
            if (blank(d.change())) {
                errors.add(d.id() + ": change vacío (el cambio concreto que se aplica).");
            }
            if (blank(d.reason())) {
                errors.add(d.id() + ": reason vacío (por qué lo decides así).");
            }
        }
        expected.forEach((id, count) -> {
            if (count == 0) {
                errors.add(id + " no tiene decisión: decide cada contradicción.");
            }
        });
        return errors;
    }

    static Set<String> requirementIds(RequirementsSpec spec) {
        var ids = new java.util.TreeSet<String>();
        for (var story : spec.userStoriesOrEmpty()) {
            ids.add(story.id());
            story.scenariosOrEmpty().forEach(s -> ids.add(s.id()));
        }
        return ids;
    }

    static Set<String> designIds(InteractionDesign design) {
        var ids = new java.util.TreeSet<String>();
        if (design.app() != null) {
            design.app().screens().forEach(s -> {
                if (s.id() != null) ids.add(s.id());
            });
        }
        if (design.game() != null) {
            design.game().rules().forEach(r -> {
                if (r.id() != null) ids.add(r.id());
            });
            design.game().entities().forEach(e -> {
                if (e.id() != null) ids.add(e.id());
            });
        }
        return ids;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
