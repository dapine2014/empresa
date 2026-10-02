package com.aicompany.core.database;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** JDBC real (spec §1 y §3). TLS según la conexión; el CA de VERIFY_FULL va a un archivo temporal privado. */
@Component
public class JdbcPostgresConnector implements PostgresConnector {

    @Override
    public Connection open(DatabaseConnection c, String password, String databaseOverride) throws SQLException {
        var database = databaseOverride == null ? c.database() : databaseOverride;
        var props = new Properties();
        props.setProperty("user", c.username());
        props.setProperty("password", password);
        props.setProperty("connectTimeout", "10");
        props.setProperty("ApplicationName", "forjai");
        switch (c.tls()) {
            case "DISABLE" -> props.setProperty("sslmode", "disable");
            case "REQUIRE" -> props.setProperty("sslmode", "require");
            case "VERIFY_FULL" -> {
                props.setProperty("sslmode", "verify-full");
                props.setProperty("sslrootcert", caFile(c.caCertPem()));
            }
            default -> throw new SQLException("Modo TLS desconocido: " + c.tls());
        }
        return DriverManager.getConnection("jdbc:postgresql://" + c.host() + ":" + c.port() + "/" + database, props);
    }

    private static String caFile(String pem) throws SQLException {
        try {
            var file = Files.createTempFile("forjai-ca-", ".pem");
            file.toFile().deleteOnExit();
            Files.writeString(file, pem);
            return file.toString();
        } catch (java.io.IOException ex) {
            throw new SQLException("No se pudo preparar el certificado CA.", ex);
        }
    }
}
