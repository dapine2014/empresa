package com.forjai.sandbox;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PackageTargetTest {

    @Test
    void parsesOnlyKnownTargetsForTheProfile() {
        assertEquals(Optional.of(PackageTarget.WINDOWS), PackageTarget.parse("windows", ExecutionProfile.GODOT_DOTNET_GAME));
        assertTrue(PackageTarget.parse("web", ExecutionProfile.GODOT_DOTNET_GAME).isEmpty());
        assertEquals(Optional.of(PackageTarget.WEB), PackageTarget.parse("web", ExecutionProfile.FLUTTER_WEB_APP));
        assertEquals(Optional.of(PackageTarget.APP), PackageTarget.parse("app", ExecutionProfile.DOTNET_APP));
        assertTrue(PackageTarget.parse("rm -rf /", ExecutionProfile.DOTNET_APP).isEmpty());
    }
}
