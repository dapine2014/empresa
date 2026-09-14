package com.aicompany.core.model;

import java.util.List;

/**
 * "Foco" actual de la conversación del Command Center: qué entidades
 * mencionó la última consulta de listado (p. ej. una lista de misiones)
 * — permite resolver referencias como "esas"/"esos" en el mensaje
 * siguiente sin pasarle historial de texto al LLM. Único {@code type}
 * soportado hoy: {@code "MISSION"}.
 */
public record LastMentioned(String type, List<String> ids) {
}
