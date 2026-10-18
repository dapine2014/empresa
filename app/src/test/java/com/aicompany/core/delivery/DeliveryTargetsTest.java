package com.aicompany.core.delivery;

import com.aicompany.core.agent.model.ClientContract;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryTargetsTest {
    @Test void aGameGetsTheFourPlatformsAndExplainsWeb() {
        var plan = DeliveryTargets.plan("GODOT_DOTNET_GAME", null, false, false);
        assertEquals(List.of("windows", "macos", "linux", "android-apk"), plan.targets());
        assertTrue(plan.limitations().contains(DeliveryTargets.NO_WEB_CSHARP));
        assertTrue(plan.limitations().contains(DeliveryTargets.MACOS_UNSIGNED));
    }

    @Test void lastIncrementWithKeyAddsTheAab() {
        assertEquals(List.of("windows", "macos", "linux", "android-apk", "android-aab"),
                DeliveryTargets.plan("GODOT_DOTNET_GAME", null, true, true).targets());
    }

    @Test void lastIncrementWithoutKeyHasNoAab() {
        var plan = DeliveryTargets.plan("GODOT_DOTNET_GAME", null, true, false);
        assertFalse(plan.targets().contains("android-aab"));
        assertTrue(plan.limitations().contains(DeliveryTargets.NO_AAB_WITHOUT_KEY));
    }

    @Test void theContractNarrowsThePlatforms() {
        var plan = DeliveryTargets.plan("GODOT_DOTNET_GAME",
                new ClientContract.Delivery(List.of("WINDOWS", "WEB")), false, false);
        assertEquals(List.of("windows"), plan.targets());
        assertTrue(plan.limitations().contains(DeliveryTargets.NO_WEB_CSHARP));
    }

    @Test void appsGetTheirBuild() {
        assertEquals(List.of("web"), DeliveryTargets.plan("FLUTTER_WEB_APP", null, true, false).targets());
        assertEquals(List.of("app"), DeliveryTargets.plan("DOTNET_APP", null, true, false).targets());
    }
}
