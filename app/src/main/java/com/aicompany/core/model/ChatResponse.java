package com.aicompany.core.model;

import java.util.List;

/** {@code agent}/{@code response} se conservan por compatibilidad; {@code replies} trae cada hablante (spec 2026-09-27 §5). */
public record ChatResponse(String agent, String response, List<ChatReply> replies) {

    public ChatResponse(String agent, String response) {
        this(agent, response, List.of(new ChatReply("ceo", agent, response)));
    }
}
