package com.aicompany.core.llm;

import java.util.List;
import java.util.Map;

/**
 * Abstracción exclusiva del CEO (consolidación de misión + chat general,
 * incluyendo el tool-calling de {@code query_company_memory}) — los
 * agentes delegados (`sales`/`product`/`finance`/`engineering`/`qa`, vía
 * {@code CeoService.executeAgentTask}) siguen llamando a Ollama directo,
 * sin pasar por esta interfaz: el diseño aprobado es "CEO potente,
 * workers locales", no una abstracción total del cliente LLM del
 * proyecto. Deliberadamente sin parámetro `format` (los agentes son los
 * únicos que piden un `AgentResult` con JSON Schema) ni `think`
 * (exclusivo del turno de decisión de herramienta de los agentes) — el
 * CEO nunca usó ninguno de los dos.
 */
public interface LlmProvider {

    LlmResponse chat(
            String operation,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools);
}
