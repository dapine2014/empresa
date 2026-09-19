package com.aicompany.core.llm;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NvidiaNimLlmProviderTest {

    private final NvidiaNimLlmProvider provider = new NvidiaNimLlmProvider(
            "https://integrate.api.nvidia.com/v1",
            "fake-api-key",
            "openai/gpt-oss-20b",
            8192,
            JsonMapper.builder().build()
    );

    @Test
    void parsesPlainTextResponse() {
        var raw = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "Estado actual de Forjai."
                      }
                    }
                  ]
                }
                """;

        var result = provider.parseResponse(raw);

        assertEquals("Estado actual de Forjai.", result.content());
        assertTrue(result.toolCalls().isEmpty());
    }

    @Test
    void decodesToolCallArgumentsFromJsonString() {
        var raw = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "function": {
                              "name": "query_company_memory",
                              "arguments": "{\\"topic\\": \\"COMPANY_STATUS\\"}"
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        var result = provider.parseResponse(raw);

        assertNull(result.content());
        assertEquals(1, result.toolCalls().size());

        @SuppressWarnings("unchecked")
        var function = (Map<String, Object>) result.toolCalls().get(0).get("function");
        assertEquals("query_company_memory", function.get("name"));

        @SuppressWarnings("unchecked")
        var arguments = (Map<String, Object>) function.get("arguments");
        assertEquals("COMPANY_STATUS", arguments.get("topic"));
    }

    @Test
    void returnsNullContentWhenTruncated() {
        var raw = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null
                      },
                      "finish_reason": "length"
                    }
                  ]
                }
                """;

        var result = provider.parseResponse(raw);

        assertNull(result.content());
        assertTrue(result.toolCalls().isEmpty());
    }

    @Test
    void returnsEmptyResponseWhenNoChoices() {
        var result = provider.parseResponse("{\"choices\": []}");

        assertNull(result.content());
        assertTrue(result.toolCalls().isEmpty());
    }

    @Test
    void preservesToolCallIdAndTypeFromRealNvidiaResponse() {
        var raw = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "id": "call_abc123",
                            "type": "function",
                            "function": {
                              "name": "query_company_memory",
                              "arguments": "{\\"topic\\": \\"COMPANY_STATUS\\"}"
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        var result = provider.parseResponse(raw);

        assertEquals(1, result.toolCalls().size());
        assertEquals("call_abc123", result.toolCalls().get(0).get("id"));
        assertEquals("function", result.toolCalls().get(0).get("type"));
    }

    @Test
    void synthesizesToolCallIdAndPropagatesItToTheFollowingToolMessage() {

        var messages = List.<Map<String, Object>>of(
                Map.of(
                        "role", "assistant",
                        "content", "",
                        "tool_calls", List.of(Map.of(
                                "function", Map.of(
                                        "name", "query_company_memory",
                                        "arguments", Map.of("topic", "COMPANY_STATUS")
                                )
                        ))
                ),
                Map.of("role", "tool", "content", "resultado real")
        );

        var normalized = provider.normalizeOutgoingMessages(messages);

        @SuppressWarnings("unchecked")
        var toolCalls = (List<Map<String, Object>>) normalized.get(0).get("tool_calls");
        var toolCall = toolCalls.get(0);
        var toolCallId = (String) toolCall.get("id");

        assertEquals("function", toolCall.get("type"));
        assertTrue(toolCallId != null && !toolCallId.isBlank());
        assertEquals(toolCallId, normalized.get(1).get("tool_call_id"));
    }

    @Test
    void rethrowsAndLogsOnNetworkOrParsingFailureWithoutSwallowingTheException() {

        var providerWithUnreachableHost = new NvidiaNimLlmProvider(
                "http://localhost:1", "fake-api-key", "openai/gpt-oss-20b", 8192,
                JsonMapper.builder().build()
        );

        assertThrows(RuntimeException.class, () -> providerWithUnreachableHost.chat(
                "CEO_CHAT",
                List.of(Map.of("role", "user", "content", "hola")),
                null
        ));
    }

    @Test
    void normalizesOutgoingToolCallArgumentsFromMapToJsonString() {
        var messages = List.<Map<String, Object>>of(
                Map.of(
                        "role", "assistant",
                        "content", "",
                        "tool_calls", List.of(Map.of(
                                "function", Map.of(
                                        "name", "query_company_memory",
                                        "arguments", Map.of("topic", "COMPANY_STATUS")
                                )
                        ))
                )
        );

        var normalized = provider.normalizeOutgoingMessages(messages);

        @SuppressWarnings("unchecked")
        var toolCalls = (List<Map<String, Object>>) normalized.get(0).get("tool_calls");
        @SuppressWarnings("unchecked")
        var function = (Map<String, Object>) toolCalls.get(0).get("function");

        assertInstanceOf(String.class, function.get("arguments"));
        assertTrue(((String) function.get("arguments")).contains("COMPANY_STATUS"));
    }

    @Test
    void leavesMessagesWithoutToolCallsUnchanged() {
        var messages = List.<Map<String, Object>>of(
                Map.of("role", "user", "content", "hola")
        );

        var normalized = provider.normalizeOutgoingMessages(messages);

        assertEquals(messages, normalized);
    }

    @Test
    void rejectsCallWhenApiKeyMissing() {
        var withoutKey = new NvidiaNimLlmProvider(
                "https://integrate.api.nvidia.com/v1", "", "openai/gpt-oss-20b", 8192,
                JsonMapper.builder().build()
        );

        var ex = assertThrows(IllegalStateException.class,
                () -> withoutKey.chat(
                        "CEO_CHAT",
                        List.of(Map.of("role", "user", "content", "hola")),
                        null
                ));

        assertTrue(ex.getMessage().contains("NVIDIA_API_KEY"));
    }
}
