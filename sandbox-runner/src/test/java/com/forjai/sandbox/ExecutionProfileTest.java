package com.forjai.sandbox;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionProfileTest {

    @Test
    void onlyCatalogIdsAreAccepted() {
        assertEquals(Optional.of(ExecutionProfile.DOTNET_APP), ExecutionProfile.parse("DOTNET_APP"));
        assertEquals(Optional.empty(), ExecutionProfile.parse("rm -rf /"));
        assertEquals(Optional.empty(), ExecutionProfile.parse(null));
    }

    @Test
    void everyProfileRunsTheSameFixedStepsInOrder() {
        for (var profile : ExecutionProfile.values()) {
            assertEquals(List.of("restore", "build", "test", "smoke"),
                    profile.steps().stream().map(ExecutionProfile.Step::name).toList());
            assertTrue(profile.image().startsWith("localhost/forjai-sandbox/"));
        }
    }
}
