package com.aicompany.core.agent.validation;

import com.aicompany.core.model.HttpRoute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Spec 2026-10-02 B1 §2: rutas minimal API reales del backend, leídas por Java (nunca declaradas por un modelo).
 * Solo rutas literales: MapGet/MapPost/MapPut/MapPatch/MapDelete("…"), con prefijos de MapGroup("…") encadenados
 * (también con llamadas fluidas como WithTags) o guardados en variables. Revisión final (C1): las variables se resuelven
 * primero en su propio archivo (un archivo por recurso suele repetir "var group"); una variable cuyo prefijo no se
 * conoce (p. ej. un parámetro RouteGroupBuilder) no se toma como raíz: queda en {@code unresolved}. /health es del
 * sandbox, no del contrato de la app (I3).
 */
public final class HttpRouteExtractor {

    public record Extraction(List<HttpRoute> routes, List<String> unresolved) {
    }

    private static final String VERBS = "Get|Post|Put|Patch|Delete";

    /** Un eslabón de la cadena: MapGroup("…") o cualquier llamada fluida que no sea un Map de ruta. */
    private static final String CHAIN = "(?:\\s*\\.\\s*(?:MapGroup\\(\\s*\"[^\"]*\"\\s*\\)"
            + "|(?!Map(?:" + VERBS + "|Group)\\b)\\w+(?:<[^<>]*>)?\\([^()]*\\)))";

    private static final Pattern GROUP_ASSIGNMENT = Pattern.compile(
            "(?:var|RouteGroupBuilder)\\s+([A-Za-z_]\\w*)\\s*=\\s*([A-Za-z_]\\w*)(" + CHAIN + "*?"
                    + "\\s*\\.\\s*MapGroup\\(\\s*\"[^\"]*\"\\s*\\)" + CHAIN + "*)\\s*;");

    private static final Pattern MAP_CALL = Pattern.compile(
            "([A-Za-z_]\\w*)(" + CHAIN + "*)\\s*\\.\\s*Map(" + VERBS + ")\\(\\s*\"([^\"]*)\"");

    private static final Pattern GROUP_LITERAL = Pattern.compile("MapGroup\\(\\s*\"([^\"]*)\"\\s*\\)");

    /** Receptores raíz declarados: el WebApplication o un IEndpointRouteBuilder (parámetro o variable). */
    private static final Pattern ROOT_DECLARATION = Pattern.compile(
            "(?:(?:WebApplication|IEndpointRouteBuilder|IApplicationBuilder)\\s+([A-Za-z_]\\w*))"
                    + "|(?:var\\s+([A-Za-z_]\\w*)\\s*=\\s*\\w+\\.Build\\(\\s*\\))");

    private static final Pattern COMMENT_OR_STRING = Pattern.compile(
            "(\"(?:[^\"\\\\\\n]|\\\\.)*\")|//[^\\n]*|/\\*[\\s\\S]*?\\*/");

    private HttpRouteExtractor() {
    }

    public static List<HttpRoute> extract(Map<String, String> csharpByPath) {
        return extractDetailed(csharpByPath).routes();
    }

    public static Extraction extractDetailed(Map<String, String> csharpByPath) {

        var files = csharpByPath.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .filter(e -> e.getValue() != null)
                .map(e -> withoutComments(e.getValue())).toList();

        var localGroups = new ArrayList<Map<String, String[]>>();
        var localRoots = new ArrayList<Set<String>>();
        var globalGroups = new HashMap<String, List<String[]>>();
        var globalRoots = new HashSet<String>();
        for (var source : files) {
            var groups = new HashMap<String, String[]>();
            var matcher = GROUP_ASSIGNMENT.matcher(source);
            while (matcher.find()) {
                var group = new String[]{matcher.group(2), literalGroups(matcher.group(3))};
                groups.put(matcher.group(1), group);
                globalGroups.computeIfAbsent(matcher.group(1), k -> new ArrayList<>()).add(group);
            }
            var roots = new HashSet<String>();
            var rootMatcher = ROOT_DECLARATION.matcher(source);
            while (rootMatcher.find()) {
                roots.add(rootMatcher.group(1) != null ? rootMatcher.group(1) : rootMatcher.group(2));
            }
            localGroups.add(groups);
            localRoots.add(roots);
            globalRoots.addAll(roots);
        }

        var routes = new LinkedHashSet<HttpRoute>();
        var unresolved = new LinkedHashSet<String>();
        for (int i = 0; i < files.size(); i++) {
            var scope = new Scope(localGroups.get(i), localRoots.get(i), globalGroups, globalRoots);
            var matcher = MAP_CALL.matcher(files.get(i));
            while (matcher.find()) {
                var prefix = scope.prefixOf(matcher.group(1), 0);
                if (prefix == null) {
                    unresolved.add(matcher.group(1));
                    continue;
                }
                var template = normalize(prefix + literalGroups(matcher.group(2)) + "/" + matcher.group(4));
                if (!template.equals("/health")) {
                    routes.add(new HttpRoute(matcher.group(3).toUpperCase(Locale.ROOT), template));
                }
            }
        }

        var sorted = new ArrayList<>(routes);
        sorted.sort(Comparator.comparing(HttpRoute::template).thenComparing(HttpRoute::method));
        return new Extraction(sorted, List.copyOf(unresolved));
    }

    private record Scope(Map<String, String[]> groups, Set<String> roots, Map<String, List<String[]>> globalGroups,
                         Set<String> globalRoots) {

        /** Prefijo de la variable, o null si no se puede saber. "app" es la raíz por convención de ASP.NET. */
        String prefixOf(String variable, int depth) {
            if (depth > 10) {
                return null;
            }
            var group = groups.get(variable);
            if (group == null && !roots.contains(variable)) {
                var global = globalGroups.get(variable);
                group = global != null && global.size() == 1 ? global.get(0) : null;
            }
            if (group != null) {
                var base = prefixOf(group[0], depth + 1);
                return base == null ? null : base + group[1];
            }
            if (roots.contains(variable) || "app".equals(variable)
                    || (globalRoots.contains(variable) && !globalGroups.containsKey(variable))) {
                return "";
            }
            return null;
        }
    }

    private static String withoutComments(String source) {
        var matcher = COMMENT_OR_STRING.matcher(source);
        var out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, matcher.group(1) != null
                    ? java.util.regex.Matcher.quoteReplacement(matcher.group(1)) : "");
        }
        matcher.appendTail(out);
        return out.toString();
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
