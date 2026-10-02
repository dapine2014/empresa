package com.aicompany.core.controller;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.AgentAvailability;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Encender y apagar agentes desde Agents (decisión del fundador, 2026-10-01). Alex no se apaga. */
@RestController
@RequestMapping("/api/company/agents")
public class AgentEnabledController {

    public record EnabledCommand(Boolean enabled) {
    }

    public record EnabledResponse(String agentId, boolean enabled) {
    }

    private final AgentAvailability availability;
    private final CompanyEventPublisher events;

    public AgentEnabledController(AgentAvailability availability, CompanyEventPublisher events) {
        this.availability = availability;
        this.events = events;
    }

    @PutMapping("/{id}/enabled")
    public EnabledResponse update(@PathVariable("id") String id, @RequestBody EnabledCommand command) {
        if (command == null || command.enabled() == null) {
            throw new IllegalArgumentException("Falta enabled (true o false).");
        }
        availability.setEnabled(id, command.enabled());
        events.publish("EMPRESA_AGENT_ENABLED_CHANGED", null, null, "human",
                Map.of("agentId", id, "enabled", command.enabled()));
        return new EnabledResponse(id, command.enabled());
    }
}
