package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.model.HttpRoute;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Spec 2026-10-02 B1 §2: la app llama a la API solo a través de ApiRoutes (generado por Java) y con la URL base de
 * API_BASE_URL. Rechaza, con reintento, un string que empiece por una ruta del backend o que apunte a localhost.
 */
public final class ApiContractGate {

    private static final Pattern STRING = Pattern.compile("(['\"])(.*?)\\1");
    private static final Pattern LOCALHOST = Pattern.compile("https?://(localhost|127\\.0\\.0\\.1)");

    private static final Pattern HTTP_CLIENT = Pattern.compile("package:http/|Uri\\.(?:parse|http|https)\\(|\\bhttp\\.(?:get|post|put|patch|delete|Client)\\b");

    private ApiContractGate() {
    }

    private static boolean isTest(String path) {
        return path.startsWith("test/") || path.contains("/test/");
    }

    public static List<String> check(List<GeneratedFile> files, List<HttpRoute> routes) {

        var firstSegments = new LinkedHashSet<String>();
        for (var route : routes) {
            var segments = route.template().split("/");
            if (segments.length > 1 && !segments[1].isEmpty() && !segments[1].startsWith("{")) {
                firstSegments.add(segments[1].toLowerCase(Locale.ROOT));
            }
        }

        var errors = new ArrayList<String>();
        for (var file : files == null ? List.<GeneratedFile>of() : files) {
            if (file == null || file.path() == null || !file.path().endsWith(".dart")
                    || file.path().endsWith(ApiRoutesGenerator.PATH) || isTest(file.path())) {
                continue;
            }
            var content = Objects.requireNonNullElse(file.content(), "");
            if (LOCALHOST.matcher(content).find()) {
                errors.add(file.path() + ": no escribas la URL de la API (localhost); usa "
                        + "const String.fromEnvironment('API_BASE_URL') como base y ApiRoutes para las rutas.");
                continue;
            }
            // Revisión final (I4): solo un archivo que llama por HTTP puede escribir rutas de la API; las rutas de
            // navegación (MaterialApp, Navigator) de cualquier otro archivo no son llamadas a la API.
            if (!HTTP_CLIENT.matcher(content).find()) {
                continue;
            }
            var found = new LinkedHashSet<String>();
            var matcher = STRING.matcher(content);
            while (matcher.find()) {
                var literal = matcher.group(2).replaceAll("^\\$\\{?\\w+}?", "");
                if (literal.startsWith("/") && literal.length() > 1) {
                    var first = literal.substring(1).split("[/?$]")[0].toLowerCase(Locale.ROOT);
                    if (firstSegments.contains(first)) {
                        found.add(literal);
                    }
                }
            }
            if (!found.isEmpty()) {
                errors.add(file.path() + ": rutas de la API escritas a mano " + found + ". Usa ApiRoutes (de "
                        + "lib/api/api_routes.dart, generado por Forjai desde el backend), p. ej. "
                        + "ApiRoutes.obtenerClientes(id).path, nunca el texto de la ruta.");
            }
        }
        return errors;
    }
}
