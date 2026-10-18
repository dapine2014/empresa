package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.TeamPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verificado en vivo (MISSION-B1-INV-1790974335, MISSION-B1-AGENDA-1790996608): Neo eligió solo API para "una app para
 * llevar el inventario" y decidió distinto con el mismo pedido. Lo explícito lo decide Java.
 */
class RequestedComponentsTest {

    private static TeamPlan plan(String... components) {
        return new TeamPlan("x", null, null, List.of(), List.of(), components[0], List.of(), List.of(), null,
                List.of(components));
    }

    @Test
    void anExplicitAppNeedsTheFlutterComponent() {
        var errors = RequestedComponents.errors("Una app para llevar el inventario de una tienda", plan("DOTNET_APP"));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("FLUTTER_WEB_APP"), errors.get(0));
        assertTrue(errors.get(0).contains("\"app\""), errors.get(0));
        assertEquals(List.of(), RequestedComponents.errors("Una aplicación con pantallas para reservar",
                plan("DOTNET_APP", "FLUTTER_WEB_APP")));
    }

    @Test
    void apiOnlyRequestsMustNotGetAnApp() {
        assertEquals(List.of(), RequestedComponents.errors("Una API .NET de citas guardando en PostgreSQL", plan("DOTNET_APP")));
        var errors = RequestedComponents.errors("Solo una API para la app móvil de otro equipo, sin pantallas",
                plan("DOTNET_APP", "FLUTTER_WEB_APP"));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("solo"), errors.get(0));
    }

    @Test
    void ambiguousRequestsAreLeftToNeo() {
        assertEquals(List.of(), RequestedComponents.errors("Una agenda de reservas para un consultorio", plan("DOTNET_APP")));
        assertEquals(List.of(), RequestedComponents.errors("Una agenda de reservas para un consultorio",
                plan("DOTNET_APP", "FLUTTER_WEB_APP")));
    }
}
