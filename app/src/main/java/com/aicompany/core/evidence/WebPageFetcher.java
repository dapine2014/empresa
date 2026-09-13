package com.aicompany.core.evidence;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Recupera el contenido de una URL real, con protección SSRF: nunca debe
 * poder usarse para que un agente (indirectamente, vía un modelo que
 * "decide" qué URL pedir) alcance la red interna, el localhost de este
 * mismo host, o endpoints de metadata de nube (169.254.169.254).
 *
 * Limitación conocida y no resuelta aquí: esta validación resuelve el host
 * una vez, antes de conectar (protege contra el caso simple); no protege
 * contra DNS rebinding (el DNS podría resolver a una IP pública en el
 * momento de esta validación y a una privada en el momento real de la
 * conexión). Resolverlo de forma robusta requeriría fijar la conexión a la
 * IP ya resuelta (pinning), que Java/Spring no exponen de forma directa
 * sin un `Resolver`/`SocketFactory` a medida — no implementado en esta
 * primera versión.
 */
@Component
public class WebPageFetcher {

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final RestClient client;

    public WebPageFetcher() {

        var httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // Nunca seguir redirects automáticamente: un servidor
                // controlado por el atacante podría responder 200 a la
                // validación y redirigir a una IP privada.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    public String fetch(String url) {

        var uri = parseAndValidate(url);

        return client.get()
                .uri(uri)
                .header("User-Agent", "AI-Company-EvidenceBot/1.0")
                .exchange((request, response) -> {

                    var status = response.getStatusCode();

                    if (!isSuccess(status)) {
                        throw new IllegalStateException(
                                "La URL respondió con estado "
                                        + status.value()
                                        + ": "
                                        + url
                        );
                    }

                    return readBounded(response.getBody(), url);
                });
    }

    private boolean isSuccess(HttpStatusCode status) {
        return status.is2xxSuccessful();
    }

    private String readBounded(
            java.io.InputStream body,
            String url) throws IOException {

        var buffer = new ByteArrayOutputStream();
        var chunk = new byte[8192];
        var total = 0;
        int read;

        while ((read = body.read(chunk)) != -1) {

            total += read;

            if (total > MAX_RESPONSE_BYTES) {
                throw new IllegalStateException(
                        "La respuesta de " + url
                                + " excede el límite de "
                                + MAX_RESPONSE_BYTES
                                + " bytes"
                );
            }

            buffer.write(chunk, 0, read);
        }

        return buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * Sin modificador de acceso a propósito: permite probar la protección
     * SSRF (localhost, IPs privadas, link-local) sin necesidad de red real.
     */
    URI parseAndValidate(String url) {

        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url es obligatoria");
        }

        URI uri;

        try {
            uri = URI.create(url);
        } catch (Exception ex) {
            throw new IllegalArgumentException("URL malformada: " + url, ex);
        }

        var scheme = uri.getScheme();

        if (scheme == null
                || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {

            throw new IllegalArgumentException(
                    "Esquema no permitido (solo http/https): " + url);
        }

        var host = uri.getHost();

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL sin host válido: " + url);
        }

        if ("localhost".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException(
                    "No se permite acceder a localhost: " + url);
        }

        InetAddress address;

        try {
            address = InetAddress.getByName(host);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "No se pudo resolver el host: " + host, ex);
        }

        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {

            throw new IllegalArgumentException(
                    "No se permite acceder a direcciones privadas/locales: "
                            + host
                            + " ("
                            + address.getHostAddress()
                            + ")"
            );
        }

        return uri;
    }
}
