package com.aicompany.core.database;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.SecretCipher;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Conexiones del fundador (spec 2026-10-02 §1): validadas, probadas antes de guardar y cifradas con SecretCipher. */
@Service
public class DatabaseConnectionService {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{1,40}");
    private static final Set<String> ENGINES = Set.of("POSTGRESQL");
    private static final Set<String> TLS = Set.of("DISABLE", "REQUIRE", "VERIFY_FULL");
    private static final Set<String> ENVIRONMENTS = Set.of("TEST", "PRODUCTION");

    private final DatabaseConnectionMemoryService memory;
    private final PostgresConnector connector;
    private final SecretCipher cipher;
    private final CompanyEventPublisher events;

    public DatabaseConnectionService(DatabaseConnectionMemoryService memory, PostgresConnector connector,
                                     SecretCipher cipher, CompanyEventPublisher events) {
        this.memory = memory;
        this.connector = connector;
        this.cipher = cipher;
        this.events = events;
    }

    public List<DatabaseConnection> list() {
        return memory.all();
    }

    public DatabaseConnection create(DatabaseConnectionCommand command) {
        validate(command, true);
        if (memory.byName(command.name()).isPresent()) {
            throw new IllegalArgumentException("Ya existe una conexión llamada " + command.name() + ".");
        }
        return store(UUID.randomUUID().toString(), command, command.password());
    }

    public DatabaseConnection update(String id, DatabaseConnectionCommand command) {
        var existing = memory.byId(id).orElseThrow(() -> new IllegalArgumentException("No existe la conexión " + id + "."));
        var keep = command.password() == null || command.password().isEmpty();
        validate(command, !keep);
        var password = keep ? cipher.decrypt(memory.cipherText(id).orElseThrow()) : command.password();
        if (!existing.name().equals(command.name()) && memory.byName(command.name()).isPresent()) {
            throw new IllegalArgumentException("Ya existe una conexión llamada " + command.name() + ".");
        }
        return store(id, command, password);
    }

    public String test(String id) {
        var c = memory.byId(id).orElseThrow(() -> new IllegalArgumentException("No existe la conexión " + id + "."));
        var result = check(c, password(c));
        memory.recordCheck(id, result);
        return result;
    }

    /** Valida nombres sin escribir nada (antes de crear la misión). */
    public void requireExisting(List<String> names) {
        resolveIds(names);
    }

    /** Asocia por nombre exacto (spec §1); falla con la lista de nombres válidos. */
    public void linkToMission(String missionId, List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        memory.linkMission(missionId, resolveIds(names));
    }

    public List<DatabaseConnection> connectionsOf(String missionId) {
        return memory.connectionsOf(missionId);
    }

    private List<String> resolveIds(List<String> names) {
        var ids = new java.util.ArrayList<String>();
        for (var name : names == null ? List.<String>of() : names) {
            var c = memory.byName(name).orElseThrow(() -> new IllegalArgumentException("No existe la base \"" + name
                    + "\". Bases cargadas: " + memory.all().stream().map(DatabaseConnection::name).toList()));
            ids.add(c.id());
        }
        return ids;
    }

    public void delete(String id) {
        if (memory.usedByActiveMission(id)) {
            throw new IllegalStateException("La conexión está en uso por una misión en curso: espera a que termine.");
        }
        memory.delete(id);
        events.publish("EMPRESA_DATABASE_CONNECTION_DELETED", null, null, "human", Map.of("connectionId", id));
    }

    /** Solo para DatabaseSchemaApplier: nunca se devuelve por la API ni se registra. */
    public String password(DatabaseConnection c) {
        return cipher.decrypt(memory.cipherText(c.id()).orElseThrow(
                () -> new IllegalStateException("La conexión " + c.name() + " no tiene clave guardada.")));
    }

    private DatabaseConnection store(String id, DatabaseConnectionCommand command, String password) {
        if (!cipher.available()) {
            throw new IllegalStateException("Falta FORJAI_SECRETS_KEY en .env: sin ella no se pueden guardar conexiones.");
        }
        var candidate = new DatabaseConnection(id, command.name(), command.engine(), command.host().strip(),
                command.port() == null ? 5432 : command.port(), command.database().strip(), command.username().strip(),
                command.tls(), blankToNull(command.caCertPem()), command.environment(), hint(password), null, null);
        var result = check(candidate, password);
        if (!result.startsWith("OK")) {
            throw new IllegalArgumentException("La conexión no funcionó y no se guardó: " + result);
        }
        var saved = new DatabaseConnection(candidate.id(), candidate.name(), candidate.engine(), candidate.host(),
                candidate.port(), candidate.database(), candidate.username(), candidate.tls(), candidate.caCertPem(),
                candidate.environment(), candidate.passwordHint(), Instant.now().toString(), result);
        memory.save(saved, cipher.encrypt(password));
        events.publish("EMPRESA_DATABASE_CONNECTION_SAVED", null, null, "human",
                Map.of("connectionId", id, "name", saved.name(), "engine", saved.engine()));
        return saved;
    }

    /** SELECT 1 contra la base; si todavía no existe (3D000), contra la base de mantenimiento "postgres". */
    private String check(DatabaseConnection c, String password) {
        try (var connection = connector.open(c, password, null)) {
            connection.createStatement().execute("SELECT 1");
            return "OK";
        } catch (SQLException ex) {
            if ("3D000".equals(ex.getSQLState())) {
                try (var connection = connector.open(c, password, "postgres")) {
                    connection.createStatement().execute("SELECT 1");
                    return "OK (la base " + c.database() + " todavía no existe; Diego la creará)";
                } catch (SQLException inner) {
                    return redact(inner.getMessage(), password);
                }
            }
            return redact(ex.getMessage(), password);
        }
    }

    private void validate(DatabaseConnectionCommand c, boolean passwordRequired) {
        require(c.name() != null && NAME.matcher(c.name()).matches(),
                "El nombre usa minúsculas, números y guiones (p. ej. citas-dev-aws).");
        require(ENGINES.contains(c.engine()), "Motor no soportado todavía: " + c.engine() + " (hoy: POSTGRESQL).");
        require(c.host() != null && !c.host().isBlank(), "Falta el host.");
        require(c.port() == null || (c.port() > 0 && c.port() < 65536), "Puerto inválido.");
        require(c.database() != null && c.database().matches("[A-Za-z_][A-Za-z0-9_]{0,62}"), "Nombre de base inválido.");
        require(c.username() != null && !c.username().isBlank(), "Falta el usuario.");
        require(!passwordRequired || (c.password() != null && !c.password().isEmpty()), "Falta la clave.");
        require(TLS.contains(c.tls()), "TLS debe ser DISABLE, REQUIRE o VERIFY_FULL.");
        require(!"VERIFY_FULL".equals(c.tls()) || (c.caCertPem() != null && c.caCertPem().contains("BEGIN CERTIFICATE")),
                "VERIFY_FULL necesita el certificado CA en PEM (para AWS RDS, el bundle de AWS).");
        require(ENVIRONMENTS.contains(c.environment()), "El entorno es TEST o PRODUCTION.");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }

    static String redact(String message, String password) {
        var text = message == null ? "Error desconocido" : message;
        return password == null || password.isEmpty() ? text : text.replace(password, "****");
    }

    private static String hint(String password) {
        return "****" + (password.length() <= 4 ? "" : password.substring(password.length() - 4));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
