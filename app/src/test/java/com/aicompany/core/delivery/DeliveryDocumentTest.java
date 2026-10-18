package com.aicompany.core.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeliveryDocumentTest {

    static DeliveryFacts game(List<DeliveryFacts.TargetResult> targets, List<String> limits) {
        return new DeliveryFacts("MISSION-PONG", "Pong de 3 niveles", "Pong 2D con tres niveles", "GAME",
                "GODOT_DOTNET_GAME", 3, 3, "2026-10-17T21:52:00Z", "dd0a0e0" + "0".repeat(33),
                "~/forjai-products/MISSION-PONG", "~/entregas-forja/MISSION-PONG/incremento-3.zip",
                List.of(new DeliveryFacts.Item("MOD-01", "LEVEL", "Nivel 1", "pelota normal hasta 50 puntos")),
                List.of(new DeliveryFacts.Story("HU-01", 1, "Como jugador quiero mover la paleta")),
                List.of(new DeliveryFacts.Scenario("ESC-01", "La pelota rebota", 1, "VERIFICADO")),
                List.of(new DeliveryFacts.Control("move_up", "W", "sube la paleta")),
                List.of(new DeliveryFacts.Level("MOD-01", "Nivel 1", "{velocidad=300}", "REGLA-03")), List.of(),
                47, 47, 6, targets, limits, List.of(), List.of(), 15.27, List.of("Iris", "Kael", "Vera"));
    }

    @Test
    void theGameDocumentHasEveryFixedSection() {
        var md = DeliveryDocument.render(game(List.of(new DeliveryFacts.TargetResult("windows", "PASS", null)),
                List.of(DeliveryTargets.NO_WEB_CSHARP)));
        for (var h : List.of("# Pong de 3 niveles — entrega del incremento 3 de 3", "## 1. Qué es",
                "## 2. Dónde está cada cosa", "## 3. Cómo correrlo", "## 4. Cómo se usa", "## 5. Qué se construyó",
                "## 6. Cómo se verificó", "## 7. Base de datos", "## 8. Limitaciones", "## 9. Costo y equipo")) {
            assertTrue(md.contains(h), h);
        }
        assertTrue(md.contains("juego/windows/Juego.exe"));
        assertTrue(md.contains("| move_up | W | sube la paleta |"));
        assertTrue(md.contains("47 de 47 tests"));
        assertTrue(md.contains("US$ 15.27"));
        assertTrue(md.contains(DeliveryTargets.NO_WEB_CSHARP));
        assertTrue(md.contains("git clone codigo-fuente.bundle"));
        assertTrue(md.contains("Sin base de datos."));
    }

    @Test
    void aFailedTargetIsListedWithItsReasonAndNotAsRunnable() {
        var md = DeliveryDocument.render(game(List.of(new DeliveryFacts.TargetResult("windows", "PASS", null),
                new DeliveryFacts.TargetResult("android-apk", "FAIL", "falta el SDK")), List.of()));
        assertTrue(md.contains("- android-apk: no se generó (falta el SDK)"));
        assertFalse(md.contains("Juego-debug.apk"));
    }

    @Test
    void missingDataSaysNotAvailable() {
        var f = game(List.of(), List.of());
        var md = DeliveryDocument.render(new DeliveryFacts(f.missionId(), null, null, "GAME", f.stackProfile(), 1, 0,
                null, f.commitSha(), f.repoPath(), f.zipPath(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), 0, 0, 0, List.of(), List.of(), List.of(), List.of(), 0, List.of()));
        assertTrue(md.contains("no disponible"));
        assertTrue(md.startsWith("# " + f.missionId() + " — entrega del incremento 1"));
    }

    static DeliveryFacts withDb(DeliveryFacts f, String stack, List<DeliveryFacts.TargetResult> targets,
                                List<DeliveryFacts.Database> dbs, List<String> migrations, int rounds) {
        return new DeliveryFacts(f.missionId(), f.productName(), f.clientSummary(), "APP", stack, f.increment(),
                f.totalIncrements(), f.verifiedAt(), f.commitSha(), f.repoPath(), f.zipPath(), f.contractItems(),
                f.stories(), f.scenarios(), List.of(), List.of(), List.of("Clientes"), f.testsPassed(), f.testsTotal(),
                rounds, targets, List.of(), dbs, migrations, f.costUsd(), f.agents());
    }

    // Revisión final (8): sección 7 con bases y migraciones en orden; entrega de app con docker/.
    @Test
    void anAppDeliveryWithDatabasesListsDockerTheOrderedMigrationsAndTheConnections() {
        var crm = new DeliveryFacts.Database("crm", "TEST", "db.local", 5432, "crm", "app");
        var md = DeliveryDocument.render(withDb(game(List.of(), List.of()), "DOTNET_APP",
                List.of(new DeliveryFacts.TargetResult("app", "PASS", null)), List.of(crm),
                List.of("V1__crear.sql", "V2__datos.sql", "V10__indices.sql"), 2));
        assertTrue(md.contains("```\napp/\ndocker/\nbase-de-datos/\n"), md);
        assertTrue(md.contains("`cd docker && cp .env.example .env && docker compose up -d`"), md);
        var section7 = md.substring(md.indexOf("## 7. Base de datos"), md.indexOf("## 8. Limitaciones"));
        assertTrue(section7.contains("Migraciones (en este orden):\n\n- V1__crear.sql\n- V2__datos.sql\n- V10__indices.sql\n"), section7);
        assertTrue(section7.contains("- crm (TEST): db.local:5432/crm, usuario app"), section7);
        assertTrue(section7.contains("La contraseña está en Forjai → Settings → Bases de datos."), section7);
        assertTrue(section7.contains("base-de-datos/LEEME.md"), section7);
        assertTrue(md.contains("- 2 rondas de corrección"), md);
    }

    // Revisión final (11): una entrega a pedido no inventa las rondas de corrección.
    @Test
    void unknownCorrectionRoundsSayNotAvailable() {
        var md = DeliveryDocument.render(withDb(game(List.of(), List.of()), "DOTNET_APP", List.of(), List.of(), List.of(),
                DeliveryService.ROUNDS_UNKNOWN));
        assertTrue(md.contains("- Rondas de corrección: no disponible"), md);
        assertFalse(md.contains("-1 rondas"), md);
    }
}
