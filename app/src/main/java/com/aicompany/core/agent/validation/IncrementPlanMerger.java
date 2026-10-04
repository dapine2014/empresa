package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.TeamPlan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Spec 2026-10-03 Aria §3, verificado en vivo (MISSION-ARIA-CRM-1791093806, incremento 2): un incremento construye
 * sobre el producto existente. Java conserva los contextos, componentes, términos y base del plan anterior (Neo agrega,
 * nunca quita), así las rutas de lo ya construido siguen teniendo dueño y estructura.
 */
public final class IncrementPlanMerger {

    private IncrementPlanMerger() {
    }

    public static TeamPlan merge(TeamPlan next, TeamPlan previous) {
        if (previous == null || next == null) {
            return next;
        }
        var contexts = union(previous.boundedContextsOrEmpty(), next.boundedContextsOrEmpty(),
                TeamPlan.BoundedContext::name);
        var terms = union(previous.ubiquitousLanguageOrEmpty(), next.ubiquitousLanguageOrEmpty(),
                TeamPlan.GlossaryTerm::term);
        var components = new LinkedHashSet<String>(componentNames(previous));
        components.addAll(componentNames(next));
        return new TeamPlan(next.summary(), next.techStack(), next.entryPoint(), next.tasks(),
                next.participationConflicts(), next.stackProfile() != null ? next.stackProfile() : previous.stackProfile(),
                contexts, terms, next.database() != null ? next.database() : previous.database(),
                List.copyOf(components));
    }

    /** Lo que Neo debe conservar, para su prompt. */
    public static String existingProduct(TeamPlan previous) {
        return """
                PRODUCTO EXISTENTE (incrementos anteriores, en el mismo repositorio): componentes %s; contextos %s.
                Se conservan tal cual (Java los mantiene en el plan): agrega los contextos nuevos que necesite este
                incremento y elige miembros que cubran las capas de todos los componentes.
                """.formatted(componentNames(previous), previous.contextNames());
    }

    private static List<String> componentNames(TeamPlan plan) {
        if (plan.components() != null && !plan.components().isEmpty()) {
            return plan.components().stream().filter(Objects::nonNull).toList();
        }
        return plan.stackProfile() == null ? List.of() : List.of(plan.stackProfile());
    }

    private static <T> List<T> union(List<T> first, List<T> second, Function<T, String> key) {
        var seen = new LinkedHashSet<String>();
        var out = new ArrayList<T>();
        for (var item : java.util.stream.Stream.concat(first.stream(), second.stream()).filter(Objects::nonNull).toList()) {
            var k = key.apply(item);
            if (k != null && seen.add(k.strip().toLowerCase(Locale.ROOT))) {
                out.add(item);
            }
        }
        return out;
    }
}
