package com.aicompany.core.model;

/** Una respuesta del chat con varios agentes (spec 2026-09-27 §5): quién habla y qué dijo. */
public record ChatReply(String agentId, String name, String text) {
}
