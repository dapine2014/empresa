package com.aicompany.core.delivery;

import java.util.List;

/** Hechos de la entrega (spec 2026-10-17): lo único que {@link DeliveryDocument} y {@link DockerTemplates} leen. */
public record DeliveryFacts(
        String missionId, String productName, String clientSummary, String experience /*APP|GAME|NONE*/,
        String stackProfile, int increment, int totalIncrements, String verifiedAt, String commitSha,
        String repoPath, String zipPath,
        List<Item> contractItems, List<Story> stories, List<Scenario> scenarios,
        List<Control> controls, List<Level> levels, List<String> screens,
        int testsPassed, int testsTotal, int correctionRounds,
        List<TargetResult> targets, List<String> limitations,
        List<Database> databases, List<String> migrations, double costUsd, List<String> agents) {

    public record Item(String id, String kind, String name, String description) { }

    public record Story(String id, int increment, String text) { }

    public record Scenario(String id, String title, int increment, String status /*VERIFICADO|SIN TEST*/) { }

    public record Control(String action, String key, String effect) { }

    public record Level(String item, String name, String parameters, String advanceRule) { }

    public record TargetResult(String name, String status /*PASS|FAIL|TIMEOUT|SKIPPED*/, String reason) { }

    /** Nunca lleva contraseña. */
    public record Database(String name, String environment, String host, int port, String database, String username) { }
}
