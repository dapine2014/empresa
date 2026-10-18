package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestInventoryTest {

    private static final String PLAYTHROUGH = "public sealed class ESC_02_rebota : Playthrough {\n"
            + " public override IEnumerable<Step> Steps(Node main) { yield break; } }";

    @Test
    void playthroughsCountAsTests() {
        var tests = TestInventory.of(Map.of("game/playthroughs/ESC_02_rebota.cs", PLAYTHROUGH,
                "game/Main.cs", "public partial class Main : Node2D { }"));
        assertEquals(Map.of("ESC_02_rebota", false), tests);
    }

    @Test
    void playthroughsUnderAComponentPrefixCountToo() {
        assertTrue(TestInventory.isTestFile("game-client/game/playthroughs/ESC_02_rebota.cs"));
        assertEquals(Map.of("ESC_02_rebota", false),
                TestInventory.of(Map.of("game-client/game/playthroughs/ESC_02_rebota.cs", PLAYTHROUGH)));
    }
}
