package com.forjai.sandbox;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** Entrega del producto (spec 2026-10-17 §1): targets fijos del paso package; nunca un comando libre. */
public enum PackageTarget {
    WINDOWS("windows"), MACOS("macos"), LINUX("linux"), ANDROID_APK("android-apk"), ANDROID_AAB("android-aab"),
    WEB("web"), APP("app");

    private static final Set<PackageTarget> GAME = Set.of(WINDOWS, MACOS, LINUX, ANDROID_APK, ANDROID_AAB);
    private final String id;

    PackageTarget(String id) { this.id = id; }

    public String id() { return id; }

    public boolean allowedFor(ExecutionProfile profile) {
        return switch (profile) {
            case GODOT_DOTNET_GAME -> GAME.contains(this);
            case FLUTTER_WEB_APP -> this == WEB;
            case DOTNET_APP -> this == APP;
        };
    }

    public static Optional<PackageTarget> parse(String id, ExecutionProfile profile) {
        return Arrays.stream(values()).filter(t -> t.id.equals(id) && t.allowedFor(profile)).findFirst();
    }
}
