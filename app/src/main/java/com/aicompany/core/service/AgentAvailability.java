package com.aicompany.core.service;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agentes encendidos y apagados (decisión del fundador, 2026-10-01): mientras Forjai se entrena solo trabajan Alex
 * y el Development Group. Un agente apagado nunca recibe una llamada al modelo: las misiones, la búsqueda de
 * clientes, la propuesta semanal y las menciones del chat lo chequean aquí antes de llamar. El valor real vive en
 * Neo4j ({@code Agent.enabled}) y se edita en Agents; {@link #defaultEnabled} solo siembra el primer arranque.
 */
@Service
public class AgentAvailability {

    public static final String CEO = "ceo";

    /** Los 6 agentes que ejecuta una discovery (MissionExecutor). */
    public static final List<String> DISCOVERY_AGENTS = List.of(CEO, "sales", "product", "finance", "engineering", "qa");

    private static final Set<String> DEFAULT_ENABLED = Set.of(CEO,
            "product-owner", "engineering", "backend", "devops", "frontend-ui",
            "interaction-design", "specialist-3d", "product", "qa", "delivery");

    private final CompanyMemoryService memory;

    public AgentAvailability(CompanyMemoryService memory) {
        this.memory = memory;
    }

    public static boolean defaultEnabled(String agentId) {
        return DEFAULT_ENABLED.contains(agentId);
    }

    public boolean isEnabled(String agentId) {
        return memory.enabledAgentIds().contains(agentId);
    }

    /** Lanza si algún agente está apagado, nombrándolos a todos; {@code what} dice qué se intentó hacer. */
    public void requireEnabled(Collection<String> agentIds, String what) {
        var enabled = memory.enabledAgentIds();
        var off = agentIds.stream().distinct().filter(id -> !enabled.contains(id)).toList();
        if (!off.isEmpty()) {
            throw new IllegalStateException("No se puede " + what + ": "
                    + (off.size() == 1 ? "está apagado " : "están apagados ")
                    + off.stream().map(this::label).collect(Collectors.joining(", "))
                    + ". Actívalos en Agents si quieres que trabajen.");
        }
    }

    /** Alex no se apaga: es el interlocutor del chat y consolida las misiones (decisión del fundador). */
    public void setEnabled(String agentId, boolean enabled) {
        if (memory.agentName(agentId).isEmpty()) {
            throw new IllegalArgumentException("No existe el agente " + agentId + ".");
        }
        if (CEO.equals(agentId) && !enabled) {
            throw new IllegalArgumentException("Alex (ceo) no se puede apagar: es el interlocutor del chat y "
                    + "consolida las misiones.");
        }
        memory.setAgentEnabled(agentId, enabled);
    }

    public String label(String agentId) {
        return memory.agentName(agentId).orElse(agentId) + " (" + agentId + ")";
    }
}
