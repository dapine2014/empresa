package com.aicompany.core.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenAiCompatibleClientTest {

    private static final String OK = """
            {"choices":[{"message":{"role":"assistant","content":"{\\"summary\\":\\"s\\",\\"files\\":[]}"},
            "finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""";

    private static final List<Map<String, Object>> MESSAGES = List.of(
            Map.of("role", "system", "content", "sys"), Map.of("role", "user", "content", "hola"));

    // Verificado en vivo con integrate.api.nvidia.com: json_schema trunca los strings largos (código cortado);
    // json_object + validación en Java funciona. El "thinking" por defecto se mezcla en content: se apaga.
    @Test
    void sendsJsonObjectModeWithThinkingOffAndTheBearerKey() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer k3y"))
                .andExpect(jsonPath("$.model").value("moonshotai/kimi-k3"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.chat_template_kwargs.thinking").value(false))
                .andExpect(jsonPath("$.max_tokens").value(16384))
                .andExpect(jsonPath("$.messages[1].content").value("hola"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        var client = new OpenAiCompatibleClient(builder.build(), "k3y", Duration.ZERO);

        assertEquals("{\"summary\":\"s\",\"files\":[]}", client.chat("moonshotai/kimi-k3", MESSAGES, true, 16384));
        server.verify();
    }

    @Test
    void rateLimitsAndServerErrorsAreRetried() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo("https://api.test/v1/chat/completions")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo("https://api.test/v1/chat/completions")).andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        var client = new OpenAiCompatibleClient(builder.build(), "k3y", Duration.ZERO);

        assertTrue(client.chat("m", MESSAGES, true, 100).contains("summary"));
        server.verify();
    }

    @Test
    void aMissingKeyFailsWithAClearMessage() {
        var client = new OpenAiCompatibleClient(RestClient.builder().baseUrl("https://api.test/v1").build(), "", Duration.ZERO);
        var ex = assertThrows(IllegalStateException.class, () -> client.chat("m", MESSAGES, true, 100));
        assertTrue(ex.getMessage().contains("NVIDIA_API_KEY"), ex.getMessage());
    }

    @Test
    void aClientErrorIsNotRetried() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(1), requestTo("https://api.test/v1/chat/completions")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        var client = new OpenAiCompatibleClient(builder.build(), "k3y", Duration.ZERO);

        assertThrows(IllegalStateException.class, () -> client.chat("m", MESSAGES, true, 100));
        server.verify();
    }
}
