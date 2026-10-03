package com.aicompany.core.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verificado en vivo (MISSION-B1-INV-1791031663, 2026-10-03): una llamada a kimi-k3 quedó colgada 5 h 27 min; el
 * read-timeout es por inactividad y NVIDIA mantenía la conexión. Cada llamada remota tiene un plazo total.
 */
class OpenAiCompatibleClientDeadlineTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String slowServer(long delayMs) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            var body = "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void aCallLongerThanTheTotalDeadlineIsAnAvailabilityFailure() throws Exception {
        var rest = RestClient.builder().baseUrl(slowServer(5_000)).requestFactory(new JdkClientHttpRequestFactory()).build();
        var client = new OpenAiCompatibleClient(rest, rest, "key", Duration.ZERO, Duration.ofMillis(500));

        var started = System.nanoTime();
        assertThrows(RemoteUnavailableException.class,
                () -> client.complete("m", List.of(Map.of("role", "user", "content", "hola")), null, false, 10));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 3_000, "no esperó al servidor lento");
    }

    @Test
    void aCallWithinTheDeadlineWorks() throws Exception {
        var rest = RestClient.builder().baseUrl(slowServer(50)).requestFactory(new JdkClientHttpRequestFactory()).build();
        var client = new OpenAiCompatibleClient(rest, rest, "key", Duration.ZERO, Duration.ofSeconds(5));
        assertEquals("ok", client.chat("m", List.of(Map.of("role", "user", "content", "hola")), false, 10));
    }
}
