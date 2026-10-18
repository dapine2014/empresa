package com.aicompany.core.delivery;

import com.aicompany.core.database.ConnectionContract;

import java.util.List;

/** Plantillas fijas de docker/ y base-de-datos/ de la entrega. Nunca llevan una contraseña real. */
public final class DockerTemplates {

    private static final String PASSWORD_NOTE = "# La contraseña está en Forjai → Settings → Bases de datos.";

    private DockerTemplates() { }

    /** Prefijo de las variables de una conexión: el mismo que lee el código generado ({@link ConnectionContract}). */
    static String envPrefix(String connectionName) {
        return ConnectionContract.prefix(connectionName == null ? "" : connectionName);
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
                var p = envPrefix(d.name());
                sb.append('\n').append("# ").append(d.name()).append(" (").append(d.environment()).append(")\n")
                        .append(p).append("HOST=").append(d.host()).append('\n')
                        .append(p).append("PORT=").append(d.port()).append('\n')
                        .append(p).append("NAME=").append(d.database()).append('\n')
                        .append(p).append("USER=").append(d.username()).append('\n')
                        .append(p).append("PASSWORD=\n")
                        .append(p).append("SSLMODE=").append(d.sslMode() == null ? "" : d.sslMode()).append('\n');
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
                    DB_APP_SSLMODE=disable
                    """);
        }
        return sb.toString();
    }

    /**
     * base-de-datos/LEEME.md (revisión final, 8): las migraciones en orden con el comando para aplicarlas y cómo
     * apuntar la app a una base real. {@code migrations} ya viene en orden de versión.
     */
    public static String databaseReadme(List<String> migrations, List<DeliveryFacts.Database> dbs,
                                        boolean ownPostgres) {
        var sb = new StringBuilder("# Base de datos\n\n## Migraciones (en este orden)\n\n");
        if (migrations == null || migrations.isEmpty()) {
            sb.append("no disponible\n");
        } else {
            migrations.forEach(m -> sb.append("- ").append(m).append('\n'));
            sb.append("\nAplícalas una sola vez y en este orden. Desde esta carpeta (base-de-datos/), con la URL de la "
                    + "base (postgresql://USUARIO:CONTRASEÑA@HOST:PUERTO/BASE):\n\n```\n");
            migrations.forEach(m -> sb.append("psql \"$URL\" -v ON_ERROR_STOP=1 -f migraciones/").append(m).append('\n'));
            sb.append("```\n");
            if (ownPostgres) {
                sb.append("\nCon el Postgres propio del docker-compose (desde docker/, con `docker compose up -d` "
                        + "corriendo):\n\n```\n");
                migrations.forEach(m -> sb.append("docker compose exec -T db psql -U app -d app -v ON_ERROR_STOP=1 < "
                        + "../base-de-datos/migraciones/").append(m).append('\n'));
                sb.append("```\n");
            }
        }
        sb.append("\n## Conexiones\n\n");
        if (dbs != null && !dbs.isEmpty()) {
            for (var d : dbs) {
                sb.append("- ").append(d.name()).append(" (").append(d.environment()).append("): ")
                        .append(d.host()).append(':').append(d.port()).append('/').append(d.database())
                        .append(", usuario ").append(d.username()).append(" — variables ")
                        .append(envPrefix(d.name())).append("*\n");
            }
            sb.append("\nLa contraseña está en Forjai → Settings → Bases de datos.\n");
        }
        if (ownPostgres) {
            sb.append("Postgres propio: lo levanta docker/docker-compose.yml; define DB_APP_PASSWORD en docker/.env.\n");
        } else if (dbs == null || dbs.isEmpty()) {
            sb.append("Sin conexiones.\n");
        }
        var prefixes = dbs == null || dbs.isEmpty() ? List.of("DB_APP_")
                : dbs.stream().map(d -> envPrefix(d.name())).distinct().toList();
        sb.append("\n## Apuntar a una base real\n\n")
                .append("1. En docker/.env (copia de docker/.env.example) edita ")
                .append(String.join(", ", prefixes.stream().map(p -> p + "HOST, " + p + "PORT, " + p + "NAME, "
                        + p + "USER y " + p + "PASSWORD").toList()))
                .append(" con los datos de la base real.\n");
        if (ownPostgres) {
            sb.append("2. En docker/docker-compose.yml quita el servicio `db`, su `depends_on` y el volumen `db-data`.\n")
                    .append("3. Aplica las migraciones en la base real con los comandos `psql` de arriba.\n");
        } else {
            sb.append("2. Aplica las migraciones en la base real con los comandos `psql` de arriba.\n");
        }
        return sb.toString();
    }
}
