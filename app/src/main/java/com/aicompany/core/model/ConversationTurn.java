package com.aicompany.core.model;

/**
 * Un turno real ya persistido de la conversación con el CEO
 * ({@code role} tal cual lo graba {@code ConversationMemoryService}:
 * {@code "user"} o {@code "ceo"}) — usado para darle memoria de charla
 * real al chat general, distinto del "foco" de {@link LastMentioned}
 * (que resuelve referencias a misiones, no continuidad conversacional).
 */
public record ConversationTurn(String role, String content) {
}
