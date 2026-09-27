package com.aicompany.core.model;

/** Un paquete de un ecosistema (NUGET | PUB) en una versión exacta. */
public record DependencyRef(String ecosystem, String name, String version) {
    public String id() {
        return ecosystem + ":" + name.toLowerCase(java.util.Locale.ROOT) + "@" + version;
    }
}
