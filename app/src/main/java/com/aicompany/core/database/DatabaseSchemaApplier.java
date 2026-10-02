package com.aicompany.core.database;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Aplica las migraciones de Diego en el servidor del fundador (spec 2026-10-02 §3): crea la base si falta, lleva
 * forjai_schema_history, aplica en orden cada pendiente en su transacción y se detiene en la primera que falla.
 * Nunca lanza por errores del servidor ni incluye la clave en el resultado.
 */
@Component
public class DatabaseSchemaApplier {

    public record ApplyResult(List<Integer> applied, Integer failedVersion, String error, boolean createdDatabase) {
    }

    private static final int STATEMENT_TIMEOUT_SECONDS = 300;

    private final PostgresConnector connector;

    public DatabaseSchemaApplier(PostgresConnector connector) {
        this.connector = connector;
    }

    public ApplyResult apply(DatabaseConnection c, String password, List<MigrationSet.Migration> migrations,
                             String missionId, String commitSha) {
        var applied = new ArrayList<Integer>();
        boolean created;
        try {
            created = ensureDatabase(c, password);
        } catch (SQLException ex) {
            return new ApplyResult(applied, null, "No se pudo crear o abrir la base " + c.database() + ": "
                    + DatabaseConnectionService.redact(ex.getMessage(), c, password), false);
        }
        try (var connection = connector.open(c, password, null)) {
            connection.setAutoCommit(true);
            try (var st = connection.createStatement()) {
                // Revisión final (I-8): ninguna sentencia espera sin límite (locks de otra sesión incluidos).
                st.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
                st.execute("SET lock_timeout = '60s'");
                st.execute("CREATE TABLE IF NOT EXISTS forjai_schema_history (version int primary key, "
                        + "description text not null, checksum char(64) not null, applied_at timestamptz not null default now(), "
                        + "mission_id text, commit_sha text)");
            }
            var history = new ArrayList<MigrationSet.AppliedMigration>();
            try (var st = connection.createStatement()) {
                st.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
                try (var rs = st.executeQuery("SELECT version, checksum FROM forjai_schema_history ORDER BY version")) {
                    while (rs.next()) {
                        history.add(new MigrationSet.AppliedMigration(rs.getInt(1), rs.getString(2)));
                    }
                }
            }
            var plan = MigrationSet.pending(migrations, history);
            if (plan.error() != null) {
                return new ApplyResult(applied, null, plan.error(), created);
            }
            for (var migration : plan.toApply()) {
                try {
                    applyOne(connection, migration, missionId, commitSha);
                    applied.add(migration.version());
                } catch (SQLException ex) {
                    return new ApplyResult(applied, migration.version(), "V" + migration.version() + " falló: "
                            + DatabaseConnectionService.redact(ex.getMessage(), c, password), created);
                }
            }
            return new ApplyResult(applied, null, null, created);
        } catch (SQLException ex) {
            return new ApplyResult(applied, null, DatabaseConnectionService.redact(ex.getMessage(), c, password), created);
        }
    }

    private boolean ensureDatabase(DatabaseConnection c, String password) throws SQLException {
        try (var ignored = connector.open(c, password, null)) {
            return false;
        } catch (SQLException ex) {
            if (!"3D000".equals(ex.getSQLState())) {
                throw ex;
            }
        }
        try (var admin = connector.open(c, password, "postgres"); var st = admin.createStatement()) {
            st.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
            st.execute("CREATE DATABASE \"" + c.database() + "\"");
            return true;
        }
    }

    private static void applyOne(Connection connection, MigrationSet.Migration m, String missionId, String commitSha)
            throws SQLException {
        connection.setAutoCommit(false);
        try (var st = connection.createStatement()) {
            st.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
            st.execute(m.sql());
            try (var ps = connection.prepareStatement("INSERT INTO forjai_schema_history "
                    + "(version, description, checksum, mission_id, commit_sha) VALUES (?, ?, ?, ?, ?)")) {
                ps.setInt(1, m.version());
                ps.setString(2, m.description());
                ps.setString(3, m.checksum());
                ps.setString(4, missionId);
                ps.setString(5, commitSha);
                ps.executeUpdate();
            }
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }
}
