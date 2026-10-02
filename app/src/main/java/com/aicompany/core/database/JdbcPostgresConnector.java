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

    /**
     * Revisión final (I-8): connectTimeout cubre el TCP; loginTimeout el handshake TLS y la autenticación;
     * socketTimeout (mayor que el queryTimeout de 300 s del aplicador) cualquier lectura colgada.
     */
    static Properties properties(DatabaseConnection c, String password) {
        var props = new Properties();
        props.setProperty("user", c.username());
        props.setProperty("password", password);
        props.setProperty("connectTimeout", "10");
        props.setProperty("loginTimeout", "15");
        props.setProperty("socketTimeout", "330");
        props.setProperty("ApplicationName", "forjai");
        switch (c.tls()) {
            case "DISABLE" -> props.setProperty("sslmode", "disable");
            case "REQUIRE" -> props.setProperty("sslmode", "require");
            case "VERIFY_FULL" -> props.setProperty("sslmode", "verify-full");
            default -> throw new IllegalArgumentException("Modo TLS desconocido: " + c.tls());
        }
        return props;
    }

    @Override
    public Connection open(DatabaseConnection c, String password, String databaseOverride) throws SQLException {
        var database = databaseOverride == null ? c.database() : databaseOverride;
        var props = properties(c, password);
        java.nio.file.Path ca = null;
        try {
            if ("VERIFY_FULL".equals(c.tls())) {
                ca = caFile(c.caCertPem());
                props.setProperty("sslrootcert", ca.toString());
            }
            return DriverManager.getConnection("jdbc:postgresql://" + c.host() + ":" + c.port() + "/" + database, props);
        } finally {
            // Revisión final (m-2): el CA temporal se borra apenas se usó (pgjdbc lo lee al conectar).
            if (ca != null) {
                try {
                    Files.deleteIfExists(ca);
                } catch (java.io.IOException ignored) {
                    // temporal privado (600): si no se pudo borrar, lo limpia el sistema
                }
            }
        }
    }

    private static java.nio.file.Path caFile(String pem) throws SQLException {
        try {
            var file = Files.createTempFile("forjai-ca-", ".pem");
            Files.writeString(file, pem);
            return file;
        } catch (java.io.IOException ex) {
            throw new SQLException("No se pudo preparar el certificado CA.", ex);
        }
    }
}
