package com.aicompany.core.llm;

import java.util.List;
import java.util.Map;

/**
 * Respuesta ya normalizada de un turno de chat con el CEO, sin importar
 * qué {@link LlmProvider} la generó (Ollama o NVIDIA NIM). Estructuralmente
 * igual al record privado {@code ModelMessage} que antes vivía dentro de
 * {@code CeoService} — ahora público para que ambos proveedores concretos
 * puedan devolverlo sin que el resto de {@code CeoService} necesite saber
 * cuál respondió.
 */
public record LlmResponse(String content, List<Map<String, Object>> toolCalls) {
}
