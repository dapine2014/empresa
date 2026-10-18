package com.aicompany.core.delivery;

import java.util.List;
import java.util.Locale;

/** Plantillas fijas de docker/ y base-de-datos/ de la entrega. Nunca llevan una contraseña real. */
public final class DockerTemplates {

    private static final String PASSWORD_NOTE = "# La contraseña está en Forjai → Settings → Bases de datos.";

    private DockerTemplates() { }

    public static String envName(String connectionName) {
        return "DB_" + (connectionName == null ? "" : connectionName).toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "_");
    }

    private static boolean isFlutter(String stackProfile) {
        return "FLUTTER_WEB_APP".equals(stackProfile);
    }

    public static String dockerfile(String stackProfile) {
        if (isFlutter(stackProfile)) {
            return """
                    FROM nginx:1.27-alpine
                    COPY app/ /usr/share/nginx/html/
                    EXPOSE 80
                    """;
        }
        return """
                FROM mcr.microsoft.com/dotnet/aspnet:8.0
                WORKDIR /app
                COPY app/ .
                ENV ASPNETCORE_URLS=http://+:8080
                EXPOSE 8080
                ENTRYPOINT ["sh","-c","exec dotnet $(cat forjai-entry.txt)"]
                """;
    }

    public static String compose(String stackProfile, List<DeliveryFacts.Database> dbs, boolean hasMigrations) {
        var port = isFlutter(stackProfile) ? "8080:80" : "8080:8080";
        var sb = new StringBuilder("""
                services:
                  app:
                    build:
                      context: ..
                      dockerfile: docker/Dockerfile
                    env_file: .env
                    ports:
                      - "%s"
                """.formatted(port));
        if (hasMigrations && (dbs == null || dbs.isEmpty())) {
            sb.append("""
                        depends_on:
                          - db
                      db:
                        image: postgres:16
                        environment:
                          POSTGRES_DB: app
                          POSTGRES_USER: app
                          POSTGRES_PASSWORD: ${DB_APP_PASSWORD}
                        volumes:
                          - db-data:/var/lib/postgresql/data
                    volumes:
                      db-data:
                    """);
        }
        return sb.toString();
    }

    public static String envExample(List<DeliveryFacts.Database> dbs, boolean ownPostgres) {
        var sb = new StringBuilder("# Copia este archivo a .env y completa lo que falta.\n");
        if (dbs != null && !dbs.isEmpty()) {
            sb.append(PASSWORD_NOTE).append('\n');
            for (var d : dbs) {
                var p = envName(d.name());
                sb.append('\n').append("# ").append(d.name()).append(" (").append(d.environment()).append(")\n")
                        .append(p).append("_HOST=").append(d.host()).append('\n')
                        .append(p).append("_PORT=").append(d.port()).append('\n')
                        .append(p).append("_NAME=").append(d.database()).append('\n')
                        .append(p).append("_USER=").append(d.username()).append('\n')
                        .append(p).append("_PASSWORD=\n");
            }
        }
        if (ownPostgres) {
            sb.append("""

                    # Postgres propio de este docker-compose.
                    DB_APP_HOST=db
                    DB_APP_PORT=5432
                    DB_APP_NAME=app
                    DB_APP_USER=app
                    DB_APP_PASSWORD=cambia-esta-clave   # cámbiala
                    """);
        }
        return sb.toString();
    }

    public static String databaseReadme(List<String> migrations, List<DeliveryFacts.Database> dbs,
                                        boolean ownPostgres) {
        var sb = new StringBuilder("# Base de datos\n\n## Migraciones (en este orden)\n\n");
        if (migrations == null || migrations.isEmpty()) sb.append("no disponible\n");
        else migrations.forEach(m -> sb.append("- ").append(m).append('\n'));
        sb.append("\n## Conexiones\n\n");
        if (dbs != null && !dbs.isEmpty()) {
            for (var d : dbs) {
                sb.append("- ").append(d.name()).append(" (").append(d.environment()).append("): ")
                        .append(d.host()).append(':').append(d.port()).append('/').append(d.database())
                        .append(", usuario ").append(d.username()).append('\n');
            }
            sb.append("\nLa contraseña está en Forjai → Settings → Bases de datos.\n");
        }
        if (ownPostgres) {
            sb.append("Postgres propio: lo levanta docker/docker-compose.yml; define DB_APP_PASSWORD en docker/.env.\n");
        } else if (dbs == null || dbs.isEmpty()) {
            sb.append("Sin conexiones.\n");
        }
        return sb.toString();
    }
}
