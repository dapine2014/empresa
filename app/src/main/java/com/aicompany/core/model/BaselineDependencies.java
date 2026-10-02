package com.aicompany.core.model;

import java.util.Set;

/** Paquetes que ya trae cada imagen del sandbox (sandbox/images/*); cuentan como aprobados. */
public final class BaselineDependencies {

    private BaselineDependencies() {
    }

    private static final Set<String> IDS = Set.of(
            "NUGET:xunit@2.5.3", "NUGET:microsoft.net.test.sdk@17.8.0", "NUGET:xunit.runner.visualstudio@2.5.3",
            "NUGET:coverlet.collector@6.0.0", "NUGET:swashbuckle.aspnetcore@6.6.2",
            "NUGET:microsoft.aspnetcore.openapi@8.0.31", "NUGET:godot.net.sdk@4.3.0",
            "PUB:cupertino_icons@1.0.8", "PUB:flutter_lints@4.0.0",
            // Spec 2026-10-02 §2.5: persistencia de Diego (DBA), precargada en sandbox/images/dotnet-app.
            "NUGET:npgsql@8.0.5");

    public static boolean contains(DependencyRef ref) {
        return IDS.contains(ref.id());
    }
}
