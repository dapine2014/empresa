package com.aicompany.core.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verificado en vivo (MISSION-TEAM-VERIFY-7): sin timeout de lectura, una
 * generación de Ollama en bucle dejó el hilo de la tarea esperando más de
 * una hora. Una respuesta que tarda más que el timeout tiene que fallar
 * (y reintentarse), no bloquear la misión.
 */
class OllamaClientTimeoutTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aResponseSlowerThanTheReadTimeoutFails() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            var body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var client = new CoreConfig().ollamaClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(300));

        assertThrows(Exception.class, () -> client.post().uri("/api/chat").body("{}").retrieve().body(String.class));
    }
}
