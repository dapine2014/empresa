package com.aicompany.core.model;

import com.aicompany.core.agent.model.TeamPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ComponentTest {

    @Test
    void oneComponentLivesAtTheRootLikeToday() {
        var components = Component.of(List.of(StackProfile.FLUTTER_WEB_APP));
        assertEquals(List.of(new Component(StackProfile.FLUTTER_WEB_APP, "")), components);
        assertEquals("lib/main.dart", components.get(0).prefix("lib/main.dart"));
        assertEquals(Optional.of("lib/main.dart"), components.get(0).strip("lib/main.dart"));
    }

    // Spec 2026-10-02 B1 §1: carpetas fijas por perfil y orden fijo (backend antes que app), sin importar el de Neo.
    @Test
    void severalComponentsGetFixedDirsInFixedOrder() {
        var components = Component.of(List.of(StackProfile.FLUTTER_WEB_APP, StackProfile.DOTNET_APP,
                StackProfile.FLUTTER_WEB_APP));
        assertEquals(List.of(new Component(StackProfile.DOTNET_APP, "backend"),
                new Component(StackProfile.FLUTTER_WEB_APP, "app")), components);
        var app = components.get(1);
        assertEquals("app/lib/main.dart", app.prefix("lib/main.dart"));
        assertEquals(Optional.of("lib/main.dart"), app.strip("app/lib/main.dart"));
        assertEquals(Optional.empty(), app.strip("backend/src/X.Api/Program.cs"));
        assertEquals(Optional.empty(), app.strip("application/x.dart"));
        assertTrue(app.owns("app"));
    }

    @Test
    void contextNamesFollowEachProfileConvention() {
        var backend = new Component(StackProfile.DOTNET_APP, "backend");
        var app = new Component(StackProfile.FLUTTER_WEB_APP, "app");
        assertEquals(List.of("GestionClientes"), backend.contexts(List.of("GestionClientes")));
        assertEquals(List.of("gestion_clientes"), app.contexts(List.of("GestionClientes")));
        assertEquals(List.of("GestionClientes"), backend.contexts(List.of("gestion_clientes")));
    }

    // Review Focus 2: Neo repite stackProfile dentro de components o los manda desordenados.
    @Test
    void planComponentsAreDeduplicatedAndFallBackToStackProfile() {
        var multi = plan("FLUTTER_WEB_APP", List.of("FLUTTER_WEB_APP", "DOTNET_APP"));
        assertEquals(List.of(StackProfile.DOTNET_APP, StackProfile.FLUTTER_WEB_APP), multi.componentProfiles());
        assertEquals(StackProfile.DOTNET_APP, multi.profile().orElseThrow());
        assertTrue(multi.hasComponent(StackProfile.FLUTTER_WEB_APP));

        var legacy = plan("DOTNET_APP", null);
        assertEquals(List.of(new Component(StackProfile.DOTNET_APP, "")), legacy.resolvedComponents());
        assertEquals(StackProfile.DOTNET_APP, legacy.profile().orElseThrow());
    }

    private static TeamPlan plan(String stackProfile, List<String> components) {
        return new TeamPlan("App", null, null, List.of(), List.of(), stackProfile, List.of(), List.of(), null,
                components);
    }
}
