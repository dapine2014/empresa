package com.aicompany.core.model;

import java.util.List;

/** Resultado de un chequeo determinista de la capa 1 (StaticWorkspaceValidator). */
public record StaticCheck(
        String check,
        String status,
        String detail,
        String sha,
        List<String> paths
) {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";

    public static StaticCheck pass(String check, String detail, String sha, List<String> paths) {
        return new StaticCheck(check, PASS, detail, sha, paths == null ? List.of() : List.copyOf(paths));
    }

    public static StaticCheck fail(String check, String detail, String sha, List<String> paths) {
        return new StaticCheck(check, FAIL, detail, sha, paths == null ? List.of() : List.copyOf(paths));
    }

    public boolean passed() {
        return PASS.equals(status);
    }
}
