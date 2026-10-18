package com.aicompany.core.delivery;

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
    }
}
