package com.forjai.sandbox;

import java.util.List;
import java.util.regex.Pattern;

/** Pedido de FETCH_DEPENDENCIES / PROMOTE_DEPENDENCIES. Solo nombres y versiones exactas (spec §3). */
public record DependencyRequest(String ecosystem, String jobId, List<Package> packages) {

    public record Package(String name, String version) {
    }

    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$");
    static final Pattern VERSION = Pattern.compile("^\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?$");

    static boolean valid(Package p) {
        return p != null && p.name() != null && p.version() != null
                && NAME.matcher(p.name()).matches() && VERSION.matcher(p.version()).matches();
    }
}
