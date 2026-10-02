package com.aicompany.core.agent.validation;

import com.aicompany.core.model.HttpRoute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Spec 2026-10-02 B1 §2: rutas minimal API reales del backend, leídas por Java (nunca declaradas por un modelo).
 * Solo rutas literales: MapGet/MapPost/MapPut/MapPatch/MapDelete("…"), con prefijos de MapGroup("…") encadenados o
 * guardados en variables (también en otros archivos). Una variable sin MapGroup conocido cuenta como raíz.
 */
public final class HttpRouteExtractor {

    private static final String GROUP_CALL = "\\s*\\.\\s*MapGroup\\(\\s*\"[^\"]*\"\\s*\\)";

    private static final Pattern GROUP_ASSIGNMENT = Pattern.compile(
            "(?:var|RouteGroupBuilder)\\s+([A-Za-z_]\\w*)\\s*=\\s*([A-Za-z_]\\w*)((?:" + GROUP_CALL + ")+)\\s*;");

    private static final Pattern MAP_CALL = Pattern.compile(
            "([A-Za-z_]\\w*)((?:" + GROUP_CALL + ")*)\\s*\\.\\s*Map(Get|Post|Put|Patch|Delete)\\(\\s*\"([^\"]*)\"");

    private static final Pattern GROUP_LITERAL = Pattern.compile("MapGroup\\(\\s*\"([^\"]*)\"\\s*\\)");

    private HttpRouteExtractor() {
    }

    public static List<HttpRoute> extract(Map<String, String> csharpByPath) {

        var sources = csharpByPath.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue).filter(Objects::nonNull).toList();

        // Variable → (variable base, prefijos propios). Se resuelve después, porque puede venir de otro archivo.
        var groups = new HashMap<String, String[]>();
        for (var source : sources) {
            var matcher = GROUP_ASSIGNMENT.matcher(source);
            while (matcher.find()) {
                groups.put(matcher.group(1), new String[]{matcher.group(2), literalGroups(matcher.group(3))});
            }
        }

        var routes = new LinkedHashSet<HttpRoute>();
        for (var source : sources) {
            var matcher = MAP_CALL.matcher(source);
            while (matcher.find()) {
                var prefix = prefixOf(matcher.group(1), groups, 0) + literalGroups(matcher.group(2));
                routes.add(new HttpRoute(matcher.group(3).toUpperCase(Locale.ROOT),
                        normalize(prefix + "/" + matcher.group(4))));
            }
        }

        var sorted = new ArrayList<>(routes);
        sorted.sort(Comparator.comparing(HttpRoute::template).thenComparing(HttpRoute::method));
        return sorted;
    }

    private static String prefixOf(String variable, Map<String, String[]> groups, int depth) {
        var group = groups.get(variable);
        if (group == null || depth > 10) {
            return "";
        }
        return prefixOf(group[0], groups, depth + 1) + group[1];
    }

    private static String literalGroups(String chain) {
        if (chain == null || chain.isBlank()) {
            return "";
        }
        var out = new StringBuilder();
        var matcher = GROUP_LITERAL.matcher(chain);
        while (matcher.find()) {
            out.append('/').append(matcher.group(1));
        }
        return out.toString();
    }

    /** "/" inicial, sin "//" ni "/" final, y {id:guid} / {id?} / {*resto} → {id} / {resto}. */
    static String normalize(String template) {
        var path = template.replaceAll("\\{\\*?([A-Za-z_]\\w*)[^}]*}", "{$1}").replaceAll("/+", "/");
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
