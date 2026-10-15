package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.RequirementsSpec.Experience;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.TeamSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Spec 2026-10-14 Kael §1.4: el tipo de experiencia que aprobó el fundador manda sobre el plan de Neo. */
public final class ExperienceRule {

    private static final Pattern LINE = Pattern.compile("(?m)^" + Pattern.quote(RequirementsText.EXPERIENCE_LINE)
            + "(APP|GAME|NONE)\\s*$");

    private ExperienceRule() {
    }

    public static Optional<Experience> declared(String instruction) {
        if (instruction == null) {
            return Optional.empty();
        }
        var m = LINE.matcher(instruction);
        return m.find() ? Experience.of(m.group(1)) : Optional.empty();
    }

    public static List<String> errors(String instruction, TeamPlan plan, TeamSnapshot team) {
        var experience = declared(instruction);
        if (experience.isEmpty() || plan == null) {
            return List.of();
        }
        var agents = plan.tasksOrEmpty().stream().filter(Objects::nonNull).map(TeamPlan.PlannedTask::agentId).toList();
        var errors = new ArrayList<String>();
        var hasGame = plan.hasComponent(StackProfile.GODOT_DOTNET_GAME);
        var hasApp = plan.hasComponent(StackProfile.FLUTTER_WEB_APP);
        switch (experience.get()) {
            case GAME -> {
                if (!hasGame) errors.add("El fundador aprobó un JUEGO: usa stackProfile GODOT_DOTNET_GAME.");
                requireRole(team, agents, "GAME_DEV", "el juego lo programa", errors);
                requireRole(team, agents, "QA", "las partidas y los tests los escribe", errors);
            }
            case APP -> {
                if (!hasApp) errors.add("El fundador aprobó una APP con pantallas: incluye FLUTTER_WEB_APP en components.");
                requireRole(team, agents, "UI_UX", "las pantallas las programa", errors);
            }
            case NONE -> {
                if (hasApp || hasGame) errors.add("El fundador aprobó un producto SIN interfaz (solo API o servicio): "
                        + "quita FLUTTER_WEB_APP y GODOT_DOTNET_GAME.");
            }
        }
        return errors;
    }

    private static void requireRole(TeamSnapshot team, List<String> agents, String role, String why, List<String> errors) {
        var member = team.members().stream().filter(m -> role.equals(m.roleCode())).findFirst();
        if (member.isPresent() && !agents.contains(member.get().agentId())) {
            errors.add("Falta " + member.get().agentId() + " (" + role + "): " + why + " " + member.get().agentId() + ".");
        }
    }
}
