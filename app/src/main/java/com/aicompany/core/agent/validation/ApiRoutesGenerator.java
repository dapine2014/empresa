package com.aicompany.core.agent.validation;

import com.aicompany.core.model.HttpRoute;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Spec 2026-10-02 B1 §2: el contrato de rutas que usa la app Flutter, escrito por Java desde las rutas reales del
 * backend. Si el backend cambia una ruta, este archivo cambia y la app deja de compilar: el compilador hace cumplir
 * el contrato. Determinista: mismas rutas → mismo archivo.
 */
public final class ApiRoutesGenerator {

    public static final String PATH = "lib/api/api_routes.dart";

    private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z_]\\w*)}");
    private static final Set<String> DART_RESERVED = Set.of("class", "default", "new", "this", "is", "in", "var",
            "final", "const", "null", "true", "false", "void", "return", "switch", "case", "do", "if", "else", "for",
            "while", "with", "super", "try", "catch", "throw", "enum", "extends", "assert", "break", "continue");

    private ApiRoutesGenerator() {
    }

    public static String generate(List<HttpRoute> routes) {
        var out = new StringBuilder("""
                // Generado por Forjai desde las rutas reales de backend/. No editar: se regenera en cada cambio de la API.
                class ApiRoute {
                  final String method;
                  final String path;
                  const ApiRoute(this.method, this.path);
                }

                class ApiRoutes {
                """);
        var used = new HashSet<String>();
        for (var route : routes) {
            var name = unique(baseName(route), used);
            var params = new ArrayList<String>();
            var seen = new HashSet<String>();
            var matcher = PARAM.matcher(route.template());
            while (matcher.find()) {
                params.add(unique(dartIdentifier(matcher.group(1)), seen));
            }
            if (params.isEmpty()) {
                out.append("  static const ApiRoute ").append(name).append(" = ApiRoute('").append(route.method())
                        .append("', '").append(route.template()).append("');\n");
            } else {
                var path = route.template();
                for (var param : params) {
                    path = path.replaceFirst("\\{[A-Za-z_]\\w*}",
                            Matcher.quoteReplacement("${Uri.encodeComponent(" + param + ")}"));
                }
                out.append("  static ApiRoute ").append(name).append("(")
                        .append(params.stream().map(p -> "String " + p).collect(Collectors.joining(", ")))
                        .append(") => ApiRoute('").append(route.method()).append("', '").append(path).append("');\n");
            }
        }
        return out.append("}\n").toString();
    }

    private static String baseName(HttpRoute route) {
        var endsWithParam = route.template().endsWith("}");
        var verb = switch (route.method()) {
            case "GET" -> endsWithParam ? "obtener" : "listar";
            case "POST" -> "crear";
            case "PUT" -> "actualizar";
            case "PATCH" -> "modificar";
            case "DELETE" -> "eliminar";
            default -> route.method().toLowerCase(Locale.ROOT);
        };
        var literal = new StringBuilder();
        for (var segment : route.template().split("/")) {
            if (segment.isEmpty() || segment.startsWith("{")) {
                continue;
            }
            for (var word : segment.split("[^A-Za-z0-9]+")) {
                if (!word.isEmpty()) {
                    literal.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
                }
            }
        }
        return verb + (literal.isEmpty() ? "Raiz" : literal.toString());
    }

    private static String dartIdentifier(String name) {
        var id = name.replaceAll("[^A-Za-z0-9_]", "_");
        if (Character.isDigit(id.charAt(0))) {
            id = "p" + id;
        }
        return DART_RESERVED.contains(id) ? id + "Value" : id;
    }

    private static String unique(String name, Set<String> used) {
        var candidate = name;
        for (int i = 2; used.contains(candidate); i++) {
            candidate = name + i;
        }
        used.add(candidate);
        return candidate;
    }
}
