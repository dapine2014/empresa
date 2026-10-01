package com.aicompany.core.model;

import com.aicompany.core.model.StackProfile.Layer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoleLayerCatalogTest {

    private static LinkedHashMap<String, String> roles(String... agentAndRole) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < agentAndRole.length; i += 2) {
            map.put(agentAndRole[i], agentAndRole[i + 1]);
        }
        return map;
    }

    @Test
    void flutterWithOnlyUiAndQaGivesUiTheFallbackLayers() {
        var layers = RoleLayerCatalog.assign(roles("frontend-ui", "UI_UX", "qa", "QA"),
                StackProfile.FLUTTER_WEB_APP.layers());

        assertEquals(List.of(Layer.DOMAIN, Layer.APPLICATION, Layer.INFRASTRUCTURE, Layer.PRESENTATION),
                layers.get("frontend-ui"));
        assertEquals(List.of(Layer.TESTS), layers.get("qa"));
    }

    @Test
    void primaryOwnersWinOverFallbacks() {
        var layers = RoleLayerCatalog.assign(
                roles("frontend-ui", "UI_UX", "backend", "BACKEND", "devops", "DATA_ARCHITECT", "qa", "QA"),
                StackProfile.FLUTTER_WEB_APP.layers());

        assertEquals(List.of(Layer.PRESENTATION), layers.get("frontend-ui"));
        assertEquals(List.of(Layer.DOMAIN, Layer.APPLICATION), layers.get("backend"));
        assertEquals(List.of(Layer.INFRASTRUCTURE), layers.get("devops"));
    }

    @Test
    void dotnetBackendTakesInfrastructureWhenNoDataArchitectIsChosen() {
        var layers = RoleLayerCatalog.assign(roles("backend", "BACKEND", "qa", "QA"),
                StackProfile.DOTNET_APP.layers());

        assertEquals(List.of(Layer.DOMAIN, Layer.APPLICATION, Layer.INFRASTRUCTURE, Layer.API), layers.get("backend"));
    }

    @Test
    void rolesThatDoNotWriteCodeGetNoLayers() {
        var layers = RoleLayerCatalog.assign(roles("engineering", "TECH_LEAD", "frontend-ui", "UI_UX"),
                StackProfile.FLUTTER_WEB_APP.layers());

        assertEquals(List.of(), layers.get("engineering"));
        assertFalse(RoleLayerCatalog.of("TECH_LEAD").orElseThrow().writesCode());
        assertFalse(RoleLayerCatalog.of("PRODUCT_OWNER").orElseThrow().writesCode());
    }

    @Test
    void onlyPhaseOneRolesAndProfilesAreEnabled() {
        assertTrue(RoleLayerCatalog.enabledNow("UI_UX"));
        assertTrue(RoleLayerCatalog.enabledNow("DATA_ARCHITECT"));
        assertFalse(RoleLayerCatalog.enabledNow("GAME_DEV"));
        assertFalse(RoleLayerCatalog.enabledNow("SPECIALIST_3D"));
        assertFalse(RoleLayerCatalog.enabledNow("CREATIVE"));
        assertFalse(RoleLayerCatalog.enabledNow("DEVOPS"));
        assertFalse(RoleLayerCatalog.enabledNow("UNKNOWN"));
        assertTrue(StackProfile.FLUTTER_WEB_APP.enabledNow());
        assertTrue(StackProfile.DOTNET_APP.enabledNow());
        assertFalse(StackProfile.GODOT_DOTNET_GAME.enabledNow());
    }
}
