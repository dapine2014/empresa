package com.aicompany.core.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DockerTemplatesTest {

    static final DeliveryFacts.Database CRM =
            new DeliveryFacts.Database("crm-test", "TEST", "db.local", 5432, "crm", "app");

    @Test
    void envExampleNeverCarriesThePassword() {
        var env = DockerTemplates.envExample(List.of(CRM), false);
        assertTrue(env.contains("DB_CRM_TEST_HOST=db.local"));
        assertTrue(env.contains("DB_CRM_TEST_PASSWORD=\n"));
        assertTrue(env.contains("Forjai → Settings → Bases de datos"));
    }

    @Test
    void composeAddsPostgresWhenThereAreMigrationsAndNoConnection() {
        var compose = DockerTemplates.compose("DOTNET_APP", List.of(), true);
        assertTrue(compose.contains("image: postgres:16"));
        assertTrue(DockerTemplates.envExample(List.of(), true).contains("cámbiala"));
        assertTrue(DockerTemplates.databaseReadme(List.of("V1__crear_clientes.sql"), List.of(), true)
                .contains("V1__crear_clientes.sql"));
    }

    @Test
    void dockerfilesCopyTheBuiltApp() {
        assertTrue(DockerTemplates.dockerfile("FLUTTER_WEB_APP").contains("FROM nginx:"));
        assertTrue(DockerTemplates.dockerfile("FLUTTER_WEB_APP").contains("COPY app/ /usr/share/nginx/html/"));
        assertTrue(DockerTemplates.dockerfile("DOTNET_APP").contains("FROM mcr.microsoft.com/dotnet/aspnet:8.0"));
        var dotnet = DockerTemplates.dockerfile("DOTNET_APP");
        assertTrue(dotnet.contains("exec dotnet $(cat forjai-entry.txt)"));
        assertFalse(dotnet.contains("ls *.dll"));
    }

    // Revisión final (10): las variables son las que lee el código generado (ConnectionContract).
    @Test
    void envVariablesMatchWhatTheGeneratedCodeReads() {
        var env = DockerTemplates.envExample(List.of(new DeliveryFacts.Database("crm-main", "TEST", "h", 5432, "crm", "u")), false);
        var expected = com.aicompany.core.database.ConnectionContract.prefix("crm-main");
        for (var v : com.aicompany.core.database.ConnectionContract.variables("crm-main")) {
            assertTrue(env.contains(v + "="), v + " en\n" + env);
        }
        assertTrue(env.contains(expected + "HOST=h"), env);
    }

    // Revisión final (8): el LEEME dice cómo aplicar las migraciones en orden y cómo apuntar a una base real.
    @Test
    void theDatabaseReadmeHasOrderedPsqlCommandsAndHowToUseARealDatabase() {
        var readme = DockerTemplates.databaseReadme(List.of("V1__crear.sql", "V2__datos.sql", "V10__indices.sql"), List.of(), true);
        var v1 = readme.indexOf("psql \"$URL\" -v ON_ERROR_STOP=1 -f migraciones/V1__crear.sql");
        var v2 = readme.indexOf("psql \"$URL\" -v ON_ERROR_STOP=1 -f migraciones/V2__datos.sql");
        var v10 = readme.indexOf("psql \"$URL\" -v ON_ERROR_STOP=1 -f migraciones/V10__indices.sql");
        assertTrue(v1 >= 0 && v1 < v2 && v2 < v10, readme);
        assertTrue(readme.contains("docker compose exec -T db psql -U app -d app -v ON_ERROR_STOP=1 < ../base-de-datos/migraciones/V1__crear.sql"), readme);
        assertTrue(readme.contains("## Apuntar a una base real"), readme);
        assertTrue(readme.contains("DB_APP_HOST, DB_APP_PORT, DB_APP_NAME, DB_APP_USER y DB_APP_PASSWORD"), readme);
        assertTrue(readme.contains("quita el servicio `db`"), readme);

        var withConnection = DockerTemplates.databaseReadme(List.of("V1__crear.sql"), List.of(CRM), false);
        assertTrue(withConnection.contains("DB_CRM_TEST_HOST, DB_CRM_TEST_PORT"), withConnection);
        assertFalse(withConnection.contains("quita el servicio"), withConnection);
        assertFalse(withConnection.contains("docker compose exec"), withConnection);
    }
}
