package com.aicompany.core.agent.validation;

import java.util.List;

/**
 * Reglas puras sobre rutas relativas de un workspace de misión: seguridad,
 * pertenencia a los ownedPaths de un agente y solapamiento entre agentes.
 * Un ownedPath puede ser un archivo ("web/index.html") o una carpeta ("web/ui").
 */
public final class OwnedPaths {

    private OwnedPaths() {
    }

    public static String normalize(String path) {

        if (path == null) {
            return "";
        }

        var p = path.strip();

        while (p.startsWith("./")) {
            p = p.substring(2);
        }

        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }

        return p;
    }

    public static boolean isSafe(String path) {

        var p = normalize(path);

        if (p.isEmpty() || p.startsWith("/") || p.matches("^[a-zA-Z]:.*") || p.contains("\\")) {
            return false;
        }

        for (var segment : p.split("/")) {
            if (segment.isEmpty() || segment.equals("..") || segment.equals(".git")) {
                return false;
            }
        }

        return true;
    }

    public static boolean covers(String owned, String path) {

        var o = normalize(owned);
        var q = normalize(path);

        if (o.isEmpty() || q.isEmpty()) {
            return false;
        }

        return q.equals(o) || q.startsWith(o + "/");
    }

    public static boolean coveredByAny(List<String> owned, String path) {
        return owned != null && owned.stream().anyMatch(o -> covers(o, path));
    }

    public static boolean overlap(String a, String b) {
        return covers(a, b) || covers(b, a);
    }
}
