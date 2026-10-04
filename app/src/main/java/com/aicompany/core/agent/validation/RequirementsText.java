package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.stream.Collectors;

/** Spec 2026-10-03 Aria §2-§3: el spec aprobado como texto (armado por Java) para los prompts y el chat. */
public final class RequirementsText {

    private RequirementsText() {
    }

    /** Se antepone a la instrucción que reciben Neo y el equipo: solo las HU del incremento, el resto explícito. */
    public static String incrementBlock(RequirementsSpec spec, int increment) {
        var total = spec.incrementNumbers().size();
        var goal = spec.incrementsOrEmpty().stream().filter(i -> i.number() == increment)
                .map(RequirementsSpec.Increment::goal).findFirst().orElse("");
        var stories = spec.storiesOf(increment).stream().map(s -> "- " + s.id() + " (" + s.role() + "): quiero "
                        + s.iWant() + " para " + s.soThat()
                        + "\n  Criterios: " + String.join("; ", s.acceptanceCriteriaOrEmpty())
                        + s.scenariosOrEmpty().stream().map(e -> "\n  " + e.id() + " " + e.title() + ": DADO "
                                + String.join(" y ", e.given()) + " CUANDO " + String.join(" y ", e.when())
                                + " ENTONCES " + String.join(" y ", e.then())).collect(Collectors.joining()))
                .collect(Collectors.joining("\n"));
        var later = spec.userStoriesOrEmpty().stream()
                .filter(s -> spec.storiesOf(increment).stream().noneMatch(i -> i.id().equals(s.id())))
                .map(RequirementsSpec.UserStory::id).collect(Collectors.joining(", "));
        return """
                REQUISITOS APROBADOS POR EL FUNDADOR (Aria, Product Owner): INCREMENTO %d de %d — %s
                Roles: %s
                Construye SOLO estas historias, con estos escenarios (cada escenario debe poder probarse):
                %s
                NO construyas en este incremento: %s
                Fuera de alcance del producto: %s
                """.formatted(increment, total, goal,
                spec.rolesOrEmpty().stream().map(RequirementsSpec.Role::name).collect(Collectors.joining(", ")),
                stories, later.isEmpty() ? "(nada; es el último incremento)" : later,
                spec.outOfScopeOrEmpty().isEmpty() ? "(nada declarado)" : String.join("; ", spec.outOfScopeOrEmpty()));
    }

    public static String summary(RequirementsSpec spec) {
        var increments = spec.incrementsOrEmpty().stream().map(i -> "Incremento " + i.number() + " (" + i.goal() + "): "
                        + spec.storiesOf(i.number()).stream().map(s -> s.id() + " " + s.iWant())
                        .collect(Collectors.joining(", ")))
                .collect(Collectors.joining(". "));
        return "Roles: " + spec.rolesOrEmpty().stream().map(RequirementsSpec.Role::name).collect(Collectors.joining(", "))
                + ". " + increments + ". Supuestos: " + String.join("; ", spec.assumptionsOrEmpty())
                + ". Fuera de alcance: " + String.join("; ", spec.outOfScopeOrEmpty()) + ".";
    }
}
