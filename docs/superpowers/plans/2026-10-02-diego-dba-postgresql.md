# Diego como DBA (conexiones y PostgreSQL) — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** El fundador carga conexiones PostgreSQL (cifradas, nunca al modelo); Diego diseña migraciones SQL versionadas y la persistencia con Npgsql; Java aplica las migraciones en el servidor del fundador (incluido AWS RDS con TLS) al terminar una misión `VERIFIED` o a pedido.

**Architecture:** Paquete nuevo `com.aicompany.core.database`: conexiones (`DatabaseConnectionService` + memoria Neo4j + `PostgresConnector` JDBC), contrato de variables (`ConnectionContract`, puro), migraciones (`MigrationSet`, puro) y aplicador (`DatabaseSchemaApplier`, JDBC) orquestado por `DatabaseApplyService`. El plan de Neo gana `database`; tres gates puros (`MigrationFilesGate`, `SecretLiteralGate`, `DatabaseContractGate`) se suman a la verificación de cada archivo generado. Command Center y chat exponen todo sin mostrar nunca la clave.

**Tech Stack:** Java 21, Spring Boot 4.1, Neo4j driver, JDBC `org.postgresql:postgresql`, Testcontainers (solo tests), React + TS, .NET 8 + Npgsql en la imagen del sandbox.

**Spec:** `docs/superpowers/specs/2026-10-02-diego-dba-postgresql-design.md`

## Global Constraints

- La clave nunca llega a un modelo, a logs, a evidencias, a eventos, al chat ni a la API (solo `****` + últimos 4).
- Sin bases de datos en el sandbox; los tests de las misiones usan fakes en memoria.
- Nombres de conexión `[a-z0-9][a-z0-9-]{1,40}`; contrato `DB_<NOMBRE>_{HOST,PORT,NAME,USER,PASSWORD,SSLMODE}` (`-` → `_`, mayúsculas).
- Migraciones `db/postgres/migrations/V<n>__<descripcion>.sql`, `n` desde 1 sin huecos, `descripcion` `[a-z0-9_]+`.
- TLS ∈ `DISABLE | REQUIRE | VERIFY_FULL` (este último exige CA). Timeouts: conexión 10 s; migración 5 min.
- Solo se aplica el esquema si el código está `VERIFIED`. Solo el fundador crea, edita o borra conexiones.
- Jackson 3, sin `@Async`, Cypher a mano, mensajes en español.

## Review Focus

1. **La clave aparece en algún texto de salida** (excepción de JDBC que repite la URL, `toString` de un record, evento): nunca. Test en Task 1 (`theTestErrorNeverContainsThePassword`) y Task 7 (`theApplyResultNeverContainsThePassword`).
2. **Una migración ya aplicada se editó en una ronda de evidencia**: no se aplica nada y se informa la versión. Test en Task 5 (`anEditedAppliedMigrationStopsEverything`).
3. **Una clave pegada en el chat**: no queda en el historial que va al modelo. Test en Task 9 (`aPastedPasswordIsNotStored`).
4. **Una conexión borrada mientras una misión en curso la usa**: rechazado. Test en Task 2 (`aConnectionUsedByARunningMissionCannotBeDeleted`).
5. **Código con una cadena de conexión literal en `appsettings.json`**: rechazado con reintento al dueño. Test en Task 4 (`aConnectionStringInAppSettingsIsRejected`).

---

### Task 1: Conexiones: modelo, contrato, cifrado y prueba de conexión

**Files:**
- Modify: `app/pom.xml` (dependencia `org.postgresql:postgresql`, versión gestionada por Spring Boot)
- Create (paquete `app/src/main/java/com/aicompany/core/database/`): `DatabaseConnection.java`, `DatabaseConnectionCommand.java`, `ConnectionContract.java`, `PostgresConnector.java`, `JdbcPostgresConnector.java`, `DatabaseConnectionMemoryService.java`, `DatabaseConnectionService.java`, `DatabaseConnectionController.java`
- Test: `app/src/test/java/com/aicompany/core/database/ConnectionContractTest.java`, `DatabaseConnectionServiceTest.java`

**Interfaces:**
- Produces:
  - `record DatabaseConnection(String id, String name, String engine, String host, int port, String database, String username, String tls, String caCertPem, String environment, String passwordHint, String lastCheckedAt, String lastCheckResult)`.
  - `record DatabaseConnectionCommand(String name, String engine, String host, Integer port, String database, String username, String password, String tls, String caCertPem, String environment)`.
  - `ConnectionContract.prefix(String name) → "DB_CITAS_DEV_AWS_"`, `variables(String name) → List<String>` (6 nombres en orden HOST, PORT, NAME, USER, PASSWORD, SSLMODE), `describe(DatabaseConnection) → String` (nombre, motor, entorno, TLS y variables; nunca host, usuario ni clave).
  - `interface PostgresConnector { java.sql.Connection open(DatabaseConnection c, String password, String databaseOverride) throws java.sql.SQLException; }` (`databaseOverride` null = `c.database()`).
  - `DatabaseConnectionMemoryService`: `save(DatabaseConnection c, String cipherText)`, `List<DatabaseConnection> all()`, `Optional<DatabaseConnection> byName(String)`, `Optional<DatabaseConnection> byId(String)`, `Optional<String> cipherText(String id)`, `void delete(String id)`, `void recordCheck(String id, String result)`.
  - `DatabaseConnectionService`: `List<DatabaseConnection> list()`, `DatabaseConnection create(DatabaseConnectionCommand)`, `DatabaseConnection update(String id, DatabaseConnectionCommand)`, `String test(String id)`, `void delete(String id)`, `String password(DatabaseConnection)` (solo para el aplicador, Task 7).

- [ ] **Step 1: Write the failing tests**

`ConnectionContractTest.java`:

```java
package com.aicompany.core.database;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionContractTest {

    private static DatabaseConnection conn() {
        return new DatabaseConnection("C1", "citas-dev-aws", "POSTGRESQL", "db.secreto.rds.amazonaws.com", 5432, "citas",
                "admin_citas", "REQUIRE", null, "TEST", "****9876", null, null);
    }

    @Test
    void theContractIsDerivedFromTheName() {
        assertEquals("DB_CITAS_DEV_AWS_", ConnectionContract.prefix("citas-dev-aws"));
        assertEquals(List.of("DB_CITAS_DEV_AWS_HOST", "DB_CITAS_DEV_AWS_PORT", "DB_CITAS_DEV_AWS_NAME",
                "DB_CITAS_DEV_AWS_USER", "DB_CITAS_DEV_AWS_PASSWORD", "DB_CITAS_DEV_AWS_SSLMODE"),
                ConnectionContract.variables("citas-dev-aws"));
    }

    @Test
    void theDescriptionForAgentsNeverHasHostUserOrPassword() {
        var text = ConnectionContract.describe(conn());
        assertTrue(text.contains("citas-dev-aws") && text.contains("POSTGRESQL") && text.contains("DB_CITAS_DEV_AWS_HOST"), text);
        assertFalse(text.contains("rds.amazonaws.com"), text);
        assertFalse(text.contains("admin_citas"), text);
        assertFalse(text.contains("9876"), text);
    }
}
```

`DatabaseConnectionServiceTest.java`:

```java
package com.aicompany.core.database;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.SecretCipher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseConnectionServiceTest {

    private final DatabaseConnectionMemoryService memory = mock(DatabaseConnectionMemoryService.class);
    private final PostgresConnector connector = mock(PostgresConnector.class);
    private final SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final DatabaseConnectionService service = new DatabaseConnectionService(memory, connector, cipher, events);

    private static DatabaseConnectionCommand command(String password) {
        return new DatabaseConnectionCommand("citas-dev-aws", "POSTGRESQL", "db.rds.amazonaws.com", 5432, "citas",
                "admin_citas", password, "REQUIRE", null, "TEST");
    }

    @Test
    void aWorkingConnectionIsSavedEncryptedWithOnlyAHint() throws Exception {
        when(connector.open(any(), eq("S3cret-9876"), isNull())).thenReturn(mock(java.sql.Connection.class));
        when(memory.byName("citas-dev-aws")).thenReturn(Optional.empty());

        var saved = service.create(command("S3cret-9876"));

        var cipherText = ArgumentCaptor.forClass(String.class);
        verify(memory).save(any(), cipherText.capture());
        assertNotEquals("S3cret-9876", cipherText.getValue());
        assertEquals("S3cret-9876", cipher.decrypt(cipherText.getValue()));
        assertEquals("****9876", saved.passwordHint());
        verify(events).publish(eq("EMPRESA_DATABASE_CONNECTION_SAVED"), isNull(), isNull(), eq("human"), anyMap());
    }

    @Test
    void aConnectionThatFailsIsNotSaved() throws Exception {
        when(memory.byName(anyString())).thenReturn(Optional.empty());
        when(connector.open(any(), anyString(), isNull())).thenThrow(new SQLException("password authentication failed"));

        var ex = assertThrows(IllegalArgumentException.class, () -> service.create(command("mala")));

        assertTrue(ex.getMessage().contains("password authentication failed"), ex.getMessage());
        verify(memory, never()).save(any(), any());
    }

    @Test
    void aDatabaseThatDoesNotExistYetIsCheckedAgainstTheMaintenanceDatabase() throws Exception {
        when(memory.byName(anyString())).thenReturn(Optional.empty());
        when(connector.open(any(), anyString(), isNull())).thenThrow(new SQLException("database \"citas\" does not exist", "3D000"));
        when(connector.open(any(), anyString(), eq("postgres"))).thenReturn(mock(java.sql.Connection.class));

        assertEquals("citas-dev-aws", service.create(command("S3cret-9876")).name());
    }

    @Test
    void theTestErrorNeverContainsThePassword() throws Exception {
        when(memory.byName(anyString())).thenReturn(Optional.empty());
        when(connector.open(any(), anyString(), isNull()))
                .thenThrow(new SQLException("FATAL: no pg_hba.conf entry; url=jdbc:postgresql://x?password=S3cret-9876"));

        var ex = assertThrows(IllegalArgumentException.class, () -> service.create(command("S3cret-9876")));

        assertFalse(ex.getMessage().contains("S3cret-9876"), ex.getMessage());
    }

    @Test
    void invalidFieldsAreRejectedBeforeConnecting() {
        assertThrows(IllegalArgumentException.class, () -> service.create(new DatabaseConnectionCommand("Citas DEV",
                "POSTGRESQL", "h", 5432, "d", "u", "p", "REQUIRE", null, "TEST")));
        assertThrows(IllegalArgumentException.class, () -> service.create(new DatabaseConnectionCommand("citas",
                "MYSQL", "h", 5432, "d", "u", "p", "REQUIRE", null, "TEST")));
        assertThrows(IllegalArgumentException.class, () -> service.create(new DatabaseConnectionCommand("citas",
                "POSTGRESQL", "h", 5432, "d", "u", "p", "VERIFY_FULL", null, "TEST")));
        verifyNoInteractions(connector);
    }

    @Test
    void aDuplicatedNameIsRejected() {
        when(memory.byName("citas-dev-aws")).thenReturn(Optional.of(new DatabaseConnection("C1", "citas-dev-aws",
                "POSTGRESQL", "h", 5432, "d", "u", "REQUIRE", null, "TEST", "****1234", null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.create(command("x")));
    }

    @Test
    void anEmptyPasswordOnUpdateKeepsTheStoredOne() throws Exception {
        var existing = new DatabaseConnection("C1", "citas-dev-aws", "POSTGRESQL", "h", 5432, "citas", "u",
                "REQUIRE", null, "TEST", "****9876", null, null);
        when(memory.byId("C1")).thenReturn(Optional.of(existing));
        when(memory.cipherText("C1")).thenReturn(Optional.of(cipher.encrypt("S3cret-9876")));
        when(connector.open(any(), eq("S3cret-9876"), isNull())).thenReturn(mock(java.sql.Connection.class));

        service.update("C1", command(""));

        var cipherText = ArgumentCaptor.forClass(String.class);
        verify(memory).save(any(), cipherText.capture());
        assertEquals("S3cret-9876", cipher.decrypt(cipherText.getValue()));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn -q test -Dtest='ConnectionContractTest,DatabaseConnectionServiceTest'`
Expected: FAIL de compilación (el paquete `database` no existe).

- [ ] **Step 3: Implement**

`app/pom.xml`, dentro de `<dependencies>`:

```xml
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
        </dependency>
```

`DatabaseConnection.java` / `DatabaseConnectionCommand.java`: los records de "Interfaces" (paquete `com.aicompany.core.database`). `DatabaseConnection` sobrescribe `toString()` para no exponer host ni usuario por accidente:

```java
    @Override
    public String toString() {
        return "DatabaseConnection[" + name + ", " + engine + ", " + environment + "]";
    }
```

`ConnectionContract.java`:

```java
package com.aicompany.core.database;

import java.util.List;
import java.util.Locale;

/**
 * Contrato de variables de una conexión (spec 2026-10-02 §1): lo único que ven Neo, Diego, Iris y Vera. Java lo
 * deriva del nombre; el código generado solo puede leer estas variables, nunca valores reales.
 */
public final class ConnectionContract {

    private static final List<String> SUFFIXES = List.of("HOST", "PORT", "NAME", "USER", "PASSWORD", "SSLMODE");

    private ConnectionContract() {
    }

    public static String prefix(String name) {
        return "DB_" + name.toUpperCase(Locale.ROOT).replace('-', '_') + "_";
    }

    public static List<String> variables(String name) {
        return SUFFIXES.stream().map(s -> prefix(name) + s).toList();
    }

    public static String describe(DatabaseConnection c) {
        return "Base \"" + c.name() + "\" (" + c.engine() + ", entorno " + c.environment() + ", TLS " + c.tls()
                + "). Variables de entorno (las ÚNICAS que puede leer el código; nunca escribas valores): "
                + String.join(", ", variables(c.name()));
    }
}
```

`PostgresConnector.java`: la interfaz de "Interfaces". `JdbcPostgresConnector.java`:

```java
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
```

`DatabaseConnectionMemoryService.java` (Cypher a mano, mismo estilo que `ApiKeyMemoryService`): nodo `(:DatabaseConnection)` con todas las propiedades del record salvo `passwordHint`, más `cipherText` y `passwordHint`. `save` hace `MERGE (c:DatabaseConnection {id:$id}) SET c += $props`. `all()` ordena por `name`. Constraint `database_connection_id` y `database_connection_name` únicos: agregarlos en `CompanyMemoryService` junto a los demás `CREATE CONSTRAINT … IF NOT EXISTS`.

`DatabaseConnectionService.java`:

```java
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

    public void delete(String id) {
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
```

`DatabaseConnectionController.java` (`/api/company/databases`): `GET` → `list()`; `POST` → `create`; `PUT /{id}` → `update`; `POST /{id}/test` → `Map.of("result", test(id))`; `DELETE /{id}` → `delete` (el chequeo de "en uso" se agrega en la Task 2). Ningún endpoint devuelve `cipherText`.

- [ ] **Step 4: Run tests**

Run: `cd app && mvn -q test -Dtest='ConnectionContractTest,DatabaseConnectionServiceTest'`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/pom.xml app/src/main/java/com/aicompany/core/database app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java app/src/test/java/com/aicompany/core/database
git commit -m "DBA: conexiones de base de datos del fundador (cifradas, probadas antes de guardar, contrato de variables)"
```

---

### Task 2: Conexiones de una misión

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/MissionCommand.java` (campo `databases`)
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java` (overload de `start` con `List<String> databaseNames`; setter opcional de `DatabaseConnectionService`)
- Modify: `app/src/main/java/com/aicompany/core/database/DatabaseConnectionMemoryService.java` (`linkMission`, `connectionsOf`, `usedByActiveMission`)
- Modify: `DatabaseConnectionService.delete` (rechazo si está en uso) y el controller de misiones que llama a `start`
- Test: `MissionServiceTest`, `DatabaseConnectionServiceTest`

**Interfaces:**
- Produces: `MissionCommand.databasesOrEmpty() → List<String>`; `MissionService.start(String missionId, String instruction, String environment, FinancialCriteriaCommand fc, String teamId, List<String> databaseNames)`; `DatabaseConnectionMemoryService.linkMission(String missionId, List<String> connectionIds)`, `List<DatabaseConnection> connectionsOf(String missionId)`, `boolean usedByActiveMission(String connectionId)` (misión en un estado distinto de `COMPLETED|CANCELLED|FAILED|AWAITING_INVESTOR`).

- [ ] **Step 1: Write the failing tests**

En `MissionServiceTest`:

```java
    @Test
    void theDatabasesAreLinkedBeforeTheMissionStartsRunning() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        when(executor.executeAsync(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(teamMemory.snapshot("TEAM-DEVELOPMENT")).thenReturn(activeEngineering());
        when(memory.find("MISSION-9")).thenReturn(Optional.of(new MissionResponse("MISSION-9", MissionStatus.CREATED,
                "PRODUCTION", 0, "Creada", "Misión recibida", Instant.now(), null, "TEAM-DEVELOPMENT")));
        var databases = mock(com.aicompany.core.database.DatabaseConnectionService.class);
        var service = new MissionService(memory, executor, mock(CompanyEventPublisher.class), teamMemory, workspace);
        service.setDatabaseConnections(databases);

        service.start("MISSION-9", "API de citas", "PRODUCTION", null, "TEAM-DEVELOPMENT", List.of("citas-dev-aws"));

        var order = inOrder(databases, executor);
        order.verify(databases).linkToMission("MISSION-9", List.of("citas-dev-aws"));
        order.verify(executor).executeAsync("MISSION-9", "API de citas");
    }
```

En `DatabaseConnectionServiceTest`:

```java
    @Test
    void aConnectionUsedByARunningMissionCannotBeDeleted() {
        when(memory.usedByActiveMission("C1")).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> service.delete("C1"));
        verify(memory, never()).delete(any());
    }

    @Test
    void linkingAnUnknownConnectionNameFailsWithTheAvailableNames() {
        when(memory.byName("nadie")).thenReturn(Optional.empty());
        when(memory.all()).thenReturn(List.of(new DatabaseConnection("C1", "citas-dev-aws", "POSTGRESQL", "h", 5432,
                "d", "u", "REQUIRE", null, "TEST", "****1", null, null)));
        var ex = assertThrows(IllegalArgumentException.class, () -> service.linkToMission("M-1", List.of("nadie")));
        assertTrue(ex.getMessage().contains("citas-dev-aws"), ex.getMessage());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn -q test -Dtest='MissionServiceTest,DatabaseConnectionServiceTest'`
Expected: FAIL de compilación (`setDatabaseConnections`, `linkToMission`, overload de `start`).

- [ ] **Step 3: Implement**

- `MissionCommand`: agregar `List<String> databases` como último componente y `databasesOrEmpty()`. Actualizar los `new MissionCommand(` existentes (`grep -rn "new MissionCommand(" app/src`) agregando `null`.
- `DatabaseConnectionService`:

```java
    /** Asocia por nombre exacto (spec §1); falla con la lista de nombres válidos. */
    public void linkToMission(String missionId, List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        var ids = new java.util.ArrayList<String>();
        for (var name : names) {
            var c = memory.byName(name).orElseThrow(() -> new IllegalArgumentException("No existe la base \"" + name
                    + "\". Bases cargadas: " + memory.all().stream().map(DatabaseConnection::name).toList()));
            ids.add(c.id());
        }
        memory.linkMission(missionId, ids);
    }

    public List<DatabaseConnection> connectionsOf(String missionId) {
        return memory.connectionsOf(missionId);
    }
```

  y en `delete`, antes de borrar: `if (memory.usedByActiveMission(id)) throw new IllegalStateException("La conexión está en uso por una misión en curso.");`
- `DatabaseConnectionMemoryService.linkMission`: `MATCH (m:Mission {id:$missionId}) UNWIND $ids AS cid MATCH (c:DatabaseConnection {id:cid}) MERGE (m)-[:USES_DATABASE]->(c)`.
- `MissionService`: setter opcional `setDatabaseConnections(DatabaseConnectionService)` (`@Autowired(required = false)`, mismo patrón que `setAgentAvailability`); el `start` de 5 argumentos delega al de 6 con `List.of()`; en el de 6, después de `memory.ensureMission(...)` y antes de `executor.executeAsync(...)`: `if (databaseConnections != null) databaseConnections.linkToMission(missionId, databaseNames);`. Validar nombres **antes** de `ensureMission` (llamar a `linkToMission` después de crear la misión puede dejar una misión creada con nombre inválido; por eso, primero `databaseConnections.requireExisting(names)` — método que hace el mismo chequeo sin escribir — y luego `ensureMission` y `linkToMission`).
- El controller que recibe `MissionCommand` pasa `command.databasesOrEmpty()`.

- [ ] **Step 4: Run tests**

Run: `cd app && mvn -q test -Dtest='MissionServiceTest,DatabaseConnectionServiceTest,MissionControllerTest'` (si `MissionControllerTest` no existe, omitirlo)
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "DBA: una misión usa conexiones por nombre; no se borra una conexión en uso"
```

---

### Task 3: El plan de Neo declara la base

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/model/TeamPlan.java` (componente `DatabaseNeed database`)
- Modify: `app/src/main/java/com/aicompany/core/agent/model/TeamPlanSchema.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/TeamPlanValidator.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/TeamPlanResolver.java` (Diego recibe `db/postgres/migrations`)
- Modify: `app/src/main/java/com/aicompany/core/model/StackProfile.java` (`db/postgres/migrations` en los extras de `DOTNET_APP`)
- Modify: `app/src/main/java/com/aicompany/core/service/TeamWorkPlanner.java` (prompt con las conexiones de la misión)
- Test: `TeamPlanValidatorTest`, `TeamPlanResolverTest`, `TeamWorkPlannerTest`

**Interfaces:**
- Produces: `record TeamPlan.DatabaseNeed(String engine, String connectionName)`; `TeamPlan.databaseOrNull()`; constructores existentes de `TeamPlan` siguen (con `database = null`). `TeamPlanValidator.validate(TeamPlan, TeamSnapshot, TeamExecutionMode, List<DatabaseConnection> missionConnections)` (el de 3 argumentos delega con `List.of()`). `TeamWorkPlanner.plan(...)` recibe las conexiones vía `DatabaseConnectionService.connectionsOf(missionId)` (setter opcional).

- [ ] **Step 1: Write the failing tests**

En `TeamPlanValidatorTest` (fixture `engineeringTeam()` ya tiene `devops`/`DATA_ARCHITECT`; agregar un helper con un plan `DOTNET_APP`):

```java
    private static final List<com.aicompany.core.database.DatabaseConnection> CITAS = List.of(
            new com.aicompany.core.database.DatabaseConnection("C1", "citas-dev-aws", "POSTGRESQL", "h", 5432, "citas",
                    "u", "REQUIRE", null, "TEST", "****1", null, null));

    private static TeamPlan dotnetWithDatabase(String connectionName, boolean withDiego) {
        var tasks = new ArrayList<>(List.of(
                new PlannedTask("backend", "WORK", "API", "API de citas", List.of("backend"),
                        List.of("src/Citas.Domain", "src/Citas.Application", "src/Citas.Api")),
                new PlannedTask("qa", "WORK", "ACCEPTANCE_TESTS", "Tests", List.of("tests"), List.of("tests/Citas.Tests")),
                new PlannedTask("qa", "VALIDATION", "CODE_REVIEW", "Revisar", List.of("qa"), List.of())));
        if (withDiego) {
            tasks.add(new PlannedTask("devops", "WORK", "DATABASE", "Modelo y persistencia", List.of("persistence"),
                    List.of("src/Citas.Infrastructure", "db/postgres/migrations")));
        }
        return new TeamPlan("Citas", null, null, tasks, List.of(), "DOTNET_APP",
                List.of(new TeamPlan.BoundedContext("Citas", "Citas")), GLOSSARY,
                new TeamPlan.DatabaseNeed("POSTGRESQL", connectionName));
    }

    @Test
    void aPlanWithADatabaseNeedsDiego() {
        var errors = validator.validate(dotnetWithDatabase("citas-dev-aws", false), engineeringTeam(),
                TeamExecutionMode.DEVELOPMENT, CITAS);
        assertTrue(errors.stream().anyMatch(e -> e.contains("devops") && e.contains("base de datos")), errors.toString());
    }

    @Test
    void theConnectionMustBeOneOfTheMission() {
        var errors = validator.validate(dotnetWithDatabase("otra", true), engineeringTeam(),
                TeamExecutionMode.DEVELOPMENT, CITAS);
        assertTrue(errors.stream().anyMatch(e -> e.contains("otra") && e.contains("citas-dev-aws")), errors.toString());
    }

    @Test
    void anEmptyConnectionNameIsAllowedWhenTheMissionHasNone() {
        var errors = validator.validate(dotnetWithDatabase("", true), engineeringTeam(), TeamExecutionMode.DEVELOPMENT,
                List.of());
        assertTrue(errors.stream().noneMatch(e -> e.contains("conexión")), errors.toString());
    }

    @Test
    void aDatabaseNeedsTheDotnetProfile() {
        var flutter = new TeamPlan("Hola", null, null, validTasks(), List.of(), "FLUTTER_WEB_APP", CONTEXTS, GLOSSARY,
                new TeamPlan.DatabaseNeed("POSTGRESQL", ""));
        var errors = validateDev(flutter);
        assertTrue(errors.stream().anyMatch(e -> e.contains("DOTNET_APP")), errors.toString());
    }
```

En `TeamPlanResolverTest`:

```java
    @Test
    void theDataArchitectGetsTheMigrationsFolderWhenThePlanHasADatabase() {
        var plan = new TeamPlan("Citas", null, null, List.of(
                t("backend", "WORK", "backend"), t("devops", "WORK", "persistence"), t("qa", "WORK", "tests")),
                List.of(), "DOTNET_APP", List.of(new TeamPlan.BoundedContext("Citas", "desc")),
                List.of(new TeamPlan.GlossaryTerm("a", "b"), new TeamPlan.GlossaryTerm("c", "d"),
                        new TeamPlan.GlossaryTerm("e", "f")),
                new TeamPlan.DatabaseNeed("POSTGRESQL", "citas-dev-aws"));

        var result = resolver.resolve(plan, DEVELOPMENT);

        assertEquals(List.of("src/Citas.Infrastructure", "db/postgres/migrations"), of(result.plan(), "devops").get(0).ownedPaths());
    }
```

En `TeamWorkPlannerTest`:

```java
    @Test
    void theLeaderSeesTheMissionConnectionsWithoutSecrets() {
        when(teamMemory.snapshot("TEAM-DEVELOPMENT")).thenReturn(engineering());
        var databases = mock(com.aicompany.core.database.DatabaseConnectionService.class);
        when(databases.connectionsOf("M-1")).thenReturn(List.of(new com.aicompany.core.database.DatabaseConnection(
                "C1", "citas-dev-aws", "POSTGRESQL", "db.secreto.rds.amazonaws.com", 5432, "citas", "admin",
                "REQUIRE", null, "TEST", "****9876", null, null)));
        planner.setDatabaseConnections(databases);
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceoService.planTeamWork(anyString(), prompt.capture(), anyString(), anyString())).thenReturn(dddPlan());

        planner.plan("M-1", "TEAM-DEVELOPMENT", "API de citas", TeamExecutionMode.DEVELOPMENT);

        assertTrue(prompt.getValue().contains("citas-dev-aws"), prompt.getValue());
        assertFalse(prompt.getValue().contains("rds.amazonaws.com"), prompt.getValue());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn -q test -Dtest='TeamPlanValidatorTest,TeamPlanResolverTest,TeamWorkPlannerTest'`
Expected: FAIL de compilación (`DatabaseNeed`, overloads).

- [ ] **Step 3: Implement**

- `TeamPlan`: agregar `DatabaseNeed database` como último componente; record anidado `public record DatabaseNeed(String engine, String connectionName) {}`; `databaseOrNull()`. Todos los constructores secundarios y los `new TeamPlan(` con 8 argumentos (`grep -rn "new TeamPlan(" app/src`) pasan a 9 con `null` o propagan `plan.database()` (en `TeamPlanResolver`, `TeamWorkPlanner.normalizeActions` y `MissionExecutor` — esos tres deben propagar, no poner `null`).
- `TeamPlanSchema.SCHEMA`: `"database", Map.of("type", "object", "properties", Map.of("engine", Map.of("type", "string"), "connectionName", Map.of("type", "string")), "required", List.of("engine", "connectionName"))` (no obligatorio en el plan).
- `StackProfile.DOTNET_APP`: agregar `"db/postgres/migrations"` a la lista de extras.
- `TeamPlanResolver`: después de calcular las rutas de cada tarea, si `plan.databaseOrNull() != null`, al dueño de INFRASTRUCTURE (el agente con rol `DATA_ARCHITECT`, o si no está, el que recibió INFRASTRUCTURE) se le agrega `db/postgres/migrations`.
- `TeamPlanValidator`: overload de 4 argumentos; en `validateDevelopmentRules`, si `database != null`:

```java
        var db = plan.databaseOrNull();
        if (db != null) {
            if (!"POSTGRESQL".equals(db.engine())) {
                errors.add("database.engine \"" + db.engine() + "\" no está soportado (hoy: POSTGRESQL).");
            }
            if (!"DOTNET_APP".equals(plan.stackProfile())) {
                errors.add("Una base de datos necesita un backend: usa stackProfile DOTNET_APP.");
            }
            var hasDiego = plan.workTasks().stream().anyMatch(t -> membersById.containsKey(t.agentId())
                    && "DATA_ARCHITECT".equals(membersById.get(t.agentId()).roleCode()));
            if (!hasDiego) {
                errors.add("El plan declara base de datos: incluye al DATA_ARCHITECT (devops) para diseñarla.");
            }
            var names = connections.stream().filter(c -> c.engine().equals(db.engine()))
                    .map(com.aicompany.core.database.DatabaseConnection::name).toList();
            var name = db.connectionName() == null ? "" : db.connectionName();
            if (!name.isEmpty() && !names.contains(name)) {
                errors.add("database.connectionName \"" + name + "\" no es una conexión de esta misión. Conexiones: "
                        + names + " (o \"\" si no hay).");
            }
        }
```

  (los mensajes contienen "devops"/"base de datos", "DOTNET_APP" y la lista de nombres, como piden los tests).
- `TeamWorkPlanner`: setter opcional `setDatabaseConnections(DatabaseConnectionService)`; en `plan(...)` obtener `connections = databaseConnections == null ? List.of() : databaseConnections.connectionsOf(missionId)`; pasarlas al validador; en la rama DEVELOPMENT del prompt agregar:

```java
                - database: si el producto guarda datos, declara {"engine": "POSTGRESQL", "connectionName": "<nombre>"}
                  con una de estas conexiones de la misión (o "" si no hay ninguna): %s. Con base de datos el stack es
                  DOTNET_APP e incluye al DATA_ARCHITECT. Si no guarda datos, omite database.
```

  con la lista armada por `ConnectionContract.describe` (sin host ni usuario).

- [ ] **Step 4: Run tests**

Run: `cd app && mvn -q test -Dtest='TeamPlanValidatorTest,TeamPlanResolverTest,TeamWorkPlannerTest,MissionExecutorTeamTest,DevelopmentTeamStrategyTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "DBA: el plan de Neo declara la base; Diego recibe las migraciones y el validador exige backend y conexión válida"
```

---

### Task 4: Gates de migraciones, secretos y contrato

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/MigrationFilesGate.java`, `SecretLiteralGate.java`, `DatabaseContractGate.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/DevelopmentRuntime.java` (overload de `generate` con `Function<DevelopmentResult, List<String>> extraChecks`)
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (arma los checks y el bloque de base en el prompt)
- Test: `MigrationFilesGateTest`, `SecretLiteralGateTest`, `DatabaseContractGateTest`, `DevelopmentRuntimeTest`

**Interfaces:**
- Produces:
  - `MigrationFilesGate.check(List<GeneratedFile> files, List<String> alreadyInRepo) → List<String>` (las rutas ya commiteadas cuentan para la secuencia).
  - `SecretLiteralGate.check(List<GeneratedFile> files, List<String> forbiddenLiterals) → List<String>`.
  - `DatabaseContractGate.check(List<GeneratedFile> files, boolean planHasDatabase, List<String> allowedVariables) → List<String>`.
  - `DevelopmentRuntime.generate(taskId, missionId, agentId, prompt, ownedPaths, expectedProjects, requiredPaths, Function<DevelopmentResult, List<String>> extraChecks)`.

- [ ] **Step 1: Write the failing tests**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MigrationFilesGateTest {

    private static GeneratedFile f(String path, String content) {
        return new GeneratedFile(path, content);
    }

    @Test
    void consecutiveWellNamedMigrationsPass() {
        assertEquals(List.of(), MigrationFilesGate.check(List.of(
                f("db/postgres/migrations/V1__crear_citas.sql", "CREATE TABLE cita (id uuid primary key);"),
                f("db/postgres/migrations/V2__indice_fecha.sql", "CREATE INDEX ix_cita_fecha ON cita (fecha);")), List.of()));
    }

    @Test
    void badNamesGapsAndPsqlCommandsAreRejected() {
        assertFalse(MigrationFilesGate.check(List.of(f("db/postgres/migrations/crear.sql", "x;")), List.of()).isEmpty());
        assertFalse(MigrationFilesGate.check(List.of(f("db/postgres/migrations/V2__a.sql", "x;")), List.of()).isEmpty());
        assertFalse(MigrationFilesGate.check(List.of(f("db/postgres/migrations/V1__a.sql", "\\connect citas\nx;")), List.of()).isEmpty());
        assertFalse(MigrationFilesGate.check(List.of(f("db/postgres/migrations/V1__a.sql", "  ")), List.of()).isEmpty());
    }

    @Test
    void theSequenceContinuesFromWhatIsAlreadyInTheRepository() {
        assertEquals(List.of(), MigrationFilesGate.check(List.of(f("db/postgres/migrations/V3__mas.sql", "x;")),
                List.of("db/postgres/migrations/V1__a.sql", "db/postgres/migrations/V2__b.sql")));
    }
}
```

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecretLiteralGateTest {

    @Test
    void aConnectionStringInAppSettingsIsRejected() {
        var errors = SecretLiteralGate.check(List.of(new GeneratedFile("src/Citas.Api/appsettings.json",
                "{\"ConnectionStrings\":{\"Db\":\"Host=localhost;Username=postgres;Password=postgres\"}}")), List.of());
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("appsettings.json"), errors.toString());
    }

    @Test
    void aPostgresUriWithCredentialsIsRejected() {
        assertFalse(SecretLiteralGate.check(List.of(new GeneratedFile("src/A.cs",
                "var u = \"postgresql://admin:clave@db:5432/citas\";")), List.of()).isEmpty());
    }

    @Test
    void theRealHostOrUserOfAMissionConnectionIsRejected() {
        assertFalse(SecretLiteralGate.check(List.of(new GeneratedFile("src/A.cs",
                "// conecta a db.secreto.rds.amazonaws.com")), List.of("db.secreto.rds.amazonaws.com", "admin_citas")).isEmpty());
    }

    @Test
    void readingTheContractVariablesIsFine() {
        assertEquals(List.of(), SecretLiteralGate.check(List.of(new GeneratedFile("src/A.cs",
                "var pwd = Environment.GetEnvironmentVariable(\"DB_CITAS_PASSWORD\");\nvar s = $\"Password={pwd}\";")),
                List.of()));
    }
}
```

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseContractGateTest {

    private static final List<String> CONTRACT = List.of("DB_CITAS_HOST", "DB_CITAS_PORT", "DB_CITAS_NAME",
            "DB_CITAS_USER", "DB_CITAS_PASSWORD", "DB_CITAS_SSLMODE");

    @Test
    void readingAVariableOutsideTheContractIsRejected() {
        assertFalse(DatabaseContractGate.check(List.of(new GeneratedFile("src/A.cs",
                "Environment.GetEnvironmentVariable(\"DB_OTRA_HOST\")")), true, CONTRACT).isEmpty());
    }

    @Test
    void migrationsWithoutADeclaredDatabaseAreRejected() {
        assertFalse(DatabaseContractGate.check(List.of(new GeneratedFile("db/postgres/migrations/V1__a.sql", "x;")),
                false, List.of()).isEmpty());
    }

    @Test
    void theContractVariablesPass() {
        assertEquals(List.of(), DatabaseContractGate.check(List.of(new GeneratedFile("src/A.cs",
                "Environment.GetEnvironmentVariable(\"DB_CITAS_HOST\")")), true, CONTRACT));
    }
}
```

En `DevelopmentRuntimeTest`:

```java
    @Test
    void extraChecksAreRetriedWithTheirMessage() throws Exception {
        when(ceoService.generateDevelopmentArtifact(eq("devops"), anyString(), anyString(), anyString()))
                .thenReturn(dev("web/game/a.js"))
                .thenReturn(dev("web/game/b.js"));

        runtime.generate("T-1", "MISSION-1", "devops", "prompt", List.of("web/game"), List.of(), List.of("web/game"),
                r -> r.files().get(0).path().endsWith("a.js") ? List.of("motivo del chequeo extra") : List.of()).get();

        verify(ceoService).generateDevelopmentArtifact(eq("devops"), contains("motivo del chequeo extra"), anyString(), anyString());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn -q test -Dtest='MigrationFilesGateTest,SecretLiteralGateTest,DatabaseContractGateTest,DevelopmentRuntimeTest'`
Expected: FAIL de compilación.

- [ ] **Step 3: Implement**

`MigrationFilesGate`:

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Migraciones de Diego (spec 2026-10-02 §2.4): nombre, secuencia sin huecos, contenido y sin metacomandos de psql. */
public final class MigrationFilesGate {

    public static final String ROOT = "db/postgres/migrations/";
    private static final Pattern NAME = Pattern.compile("V([1-9][0-9]*)__[a-z0-9_]+\\.sql");

    private MigrationFilesGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files, List<String> alreadyInRepo) {
        var errors = new ArrayList<String>();
        var versions = new TreeSet<Integer>();
        for (var path : alreadyInRepo) {
            version(path).ifPresent(versions::add);
        }
        for (var file : files.stream().filter(Objects::nonNull).toList()) {
            if (file.path() == null || !file.path().startsWith(ROOT)) {
                continue;
            }
            var name = file.path().substring(ROOT.length());
            var matcher = NAME.matcher(name);
            if (!matcher.matches()) {
                errors.add("Migración \"" + file.path() + "\": el nombre debe ser V<n>__<descripcion>.sql (minúsculas y _).");
                continue;
            }
            versions.add(Integer.parseInt(matcher.group(1)));
            if (file.content() == null || file.content().isBlank()) {
                errors.add("Migración \"" + file.path() + "\" vacía.");
            } else if (file.content().lines().anyMatch(l -> l.strip().startsWith("\\"))) {
                errors.add("Migración \"" + file.path() + "\": sin metacomandos de psql (\\connect, \\c…); Forjai la aplica con JDBC.");
            }
        }
        var expected = 1;
        for (var v : versions) {
            if (v != expected) {
                errors.add("Las migraciones deben ir de V1 en adelante sin huecos: falta V" + expected + ".");
                break;
            }
            expected++;
        }
        return errors;
    }

    static java.util.Optional<Integer> version(String path) {
        if (path == null || !path.startsWith(ROOT)) {
            return java.util.Optional.empty();
        }
        var m = NAME.matcher(path.substring(ROOT.length()));
        return m.matches() ? java.util.Optional.of(Integer.parseInt(m.group(1))) : java.util.Optional.empty();
    }
}
```

`SecretLiteralGate`:

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Ningún secreto en el código (spec 2026-10-02 §2.4): la app lee solo las variables del contrato. */
public final class SecretLiteralGate {

    // Password=<valor literal> (no una interpolación {…} ni vacío).
    private static final Pattern PASSWORD_LITERAL = Pattern.compile("(?i)(password|pwd)\\s*=\\s*(?![{\"$;]|\\s*$)[^;\"\\s]+");
    private static final Pattern URI_WITH_CREDENTIALS = Pattern.compile("(?i)postgres(ql)?://[^:/@\\s]+:[^@\\s]+@");

    private SecretLiteralGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files, List<String> forbiddenLiterals) {
        var errors = new ArrayList<String>();
        for (var file : files.stream().filter(Objects::nonNull).toList()) {
            var content = file.content() == null ? "" : file.content();
            var found = PASSWORD_LITERAL.matcher(content).find() || URI_WITH_CREDENTIALS.matcher(content).find()
                    || forbiddenLiterals.stream().anyMatch(l -> l != null && !l.isBlank() && content.contains(l));
            if (found) {
                errors.add("Secreto o dato de conexión real en " + file.path() + ": la app lee la conexión SOLO de las "
                        + "variables de entorno del contrato (DB_<NOMBRE>_*); no escribas claves, hosts ni cadenas de conexión.");
            }
        }
        return errors;
    }
}
```

`DatabaseContractGate`:

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** El código solo usa el contrato de variables; hay migraciones solo si el plan declaró base (spec §2.4). */
public final class DatabaseContractGate {

    private static final Pattern DB_VARIABLE = Pattern.compile("\\bDB_[A-Z0-9_]+_(HOST|PORT|NAME|USER|PASSWORD|SSLMODE)\\b");

    private DatabaseContractGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files, boolean planHasDatabase,
                                     List<String> allowedVariables) {
        var errors = new ArrayList<String>();
        for (var file : files.stream().filter(Objects::nonNull).toList()) {
            if (file.path() != null && file.path().startsWith(MigrationFilesGate.ROOT) && !planHasDatabase) {
                errors.add("Hay migraciones (" + file.path() + ") pero el plan no declaró base de datos.");
            }
            var m = DB_VARIABLE.matcher(file.content() == null ? "" : file.content());
            while (m.find()) {
                if (!allowedVariables.contains(m.group())) {
                    errors.add("La variable " + m.group() + " en " + file.path() + " no es del contrato: usa "
                            + allowedVariables + ".");
                }
            }
        }
        return errors;
    }
}
```

`DevelopmentRuntime`: nuevo overload que recibe `extraChecks` y, en `verifyGenerated`, suma `extraChecks.apply(result)` a `retryable` (los overloads existentes delegan con `r -> List.of()`).

`DevelopmentTeamStrategy`: con `plan.databaseOrNull()` y las conexiones de la misión (setter opcional `setDatabaseConnections`), arma:

```java
        var database = plan.databaseOrNull();
        var connection = database == null || database.connectionName() == null || database.connectionName().isBlank()
                ? null : connections.stream().filter(c -> c.name().equals(database.connectionName())).findFirst().orElse(null);
        var contract = connection == null ? List.<String>of() : ConnectionContract.variables(connection.name());
        var forbidden = connections.stream().flatMap(c -> java.util.stream.Stream.of(c.host(), c.username())).toList();
        java.util.function.Function<DevelopmentResult, List<String>> extraChecks = r -> {
            var errors = new ArrayList<String>();
            errors.addAll(MigrationFilesGate.check(r.files(), migrationsInRepo(missionId, generationHead)));
            errors.addAll(SecretLiteralGate.check(r.files(), forbidden));
            errors.addAll(DatabaseContractGate.check(r.files(), database != null, contract));
            return errors;
        };
```

  (`migrationsInRepo` = `workspace.filesAtCommit(...)` filtrado por `MigrationFilesGate.ROOT`, `List.of()` si no hay commit) y lo pasa en las dos llamadas a `runtime.generate` de la generación y en la de corrección. Si hay base, `buildWorkPrompt` agrega:

```java
        BASE DE DATOS: %s
        - Diego (DATA_ARCHITECT): migraciones en db/postgres/migrations/V<n>__<descripcion>.sql (consecutivas desde V1;
          en una ronda nueva, una migración nueva, nunca editar una existente) y repositorios con Npgsql
          (NpgsqlDataSource) en infraestructura, leyendo SOLO esas variables.
        - Iris (BACKEND): interfaces de repositorios en domain/application; el arranque de la API arma la conexión con
          esas variables, conecta al primer uso y /health NO depende de la base. Incluye .env.example con los nombres.
        - Vera (QA): tests con fakes en memoria escritos a mano de las interfaces; nunca una base real.
```

  con `ConnectionContract.describe(connection)` o, si no hay conexión, "PostgreSQL (la conexión la cargará el fundador; usa el contrato DB_APP_*)" y `contract = ConnectionContract.variables("app")`.

- [ ] **Step 4: Run tests**

Run: `cd app && mvn -q test -Dtest='MigrationFilesGateTest,SecretLiteralGateTest,DatabaseContractGateTest,DevelopmentRuntimeTest,DevelopmentTeamStrategyTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "DBA: chequeos de migraciones, de secretos en el código y del contrato de variables, con reintento al dueño"
```

---

### Task 5: Migraciones pendientes (lógica pura)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/database/MigrationSet.java`
- Test: `app/src/test/java/com/aicompany/core/database/MigrationSetTest.java`

**Interfaces:**
- Produces: `record Migration(int version, String description, String sql, String checksum)` (anidado en `MigrationSet`); `record AppliedMigration(int version, String checksum)`; `static List<Migration> MigrationSet.parse(Map<String, String> contentsByPath)` (solo `db/postgres/migrations/V*`, ordenadas); `static Plan MigrationSet.pending(List<Migration> all, List<AppliedMigration> history)` con `record Plan(List<Migration> toApply, String error)` (`error` ≠ null → no aplicar nada).

- [ ] **Step 1: Write the failing test**

```java
package com.aicompany.core.database;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MigrationSetTest {

    private static final Map<String, String> FILES = Map.of(
            "db/postgres/migrations/V2__indice.sql", "CREATE INDEX i ON cita (fecha);",
            "db/postgres/migrations/V1__crear.sql", "CREATE TABLE cita (id uuid primary key, fecha date);",
            "src/Citas.Api/Program.cs", "class P {}");

    @Test
    void migrationsAreParsedInOrderWithAChecksum() {
        var all = MigrationSet.parse(FILES);
        assertEquals(List.of(1, 2), all.stream().map(MigrationSet.Migration::version).toList());
        assertEquals(64, all.get(0).checksum().length());
        assertEquals("crear", all.get(0).description());
    }

    @Test
    void onlyThePendingOnesAreApplied() {
        var all = MigrationSet.parse(FILES);
        var plan = MigrationSet.pending(all, List.of(new MigrationSet.AppliedMigration(1, all.get(0).checksum())));
        assertNull(plan.error());
        assertEquals(List.of(2), plan.toApply().stream().map(MigrationSet.Migration::version).toList());
    }

    @Test
    void anEditedAppliedMigrationStopsEverything() {
        var all = MigrationSet.parse(FILES);
        var plan = MigrationSet.pending(all, List.of(new MigrationSet.AppliedMigration(1, "otro-checksum")));
        assertTrue(plan.toApply().isEmpty());
        assertTrue(plan.error().contains("V1"), plan.error());
    }

    @Test
    void anAppliedMigrationMissingFromTheRepositoryIsReported() {
        var plan = MigrationSet.pending(MigrationSet.parse(Map.of()),
                List.of(new MigrationSet.AppliedMigration(1, "x")));
        assertTrue(plan.error().contains("V1"), plan.error());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && mvn -q test -Dtest=MigrationSetTest`
Expected: FAIL de compilación.

- [ ] **Step 3: Implement**

```java
package com.aicompany.core.database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Migraciones versionadas como Flyway, sin Flyway (spec 2026-10-02 §3). Puro: nunca toca la red. */
public final class MigrationSet {

    private static final Pattern PATH = Pattern.compile("db/postgres/migrations/V([1-9][0-9]*)__([a-z0-9_]+)\\.sql");

    public record Migration(int version, String description, String sql, String checksum) {
    }

    public record AppliedMigration(int version, String checksum) {
    }

    public record Plan(List<Migration> toApply, String error) {
    }

    private MigrationSet() {
    }

    public static List<Migration> parse(Map<String, String> contentsByPath) {
        var result = new ArrayList<Migration>();
        contentsByPath.forEach((path, sql) -> {
            var m = PATH.matcher(path);
            if (m.matches()) {
                result.add(new Migration(Integer.parseInt(m.group(1)), m.group(2), sql, sha256(sql)));
            }
        });
        result.sort(Comparator.comparingInt(Migration::version));
        return result;
    }

    public static Plan pending(List<Migration> all, List<AppliedMigration> history) {
        var byVersion = new java.util.HashMap<Integer, Migration>();
        all.forEach(m -> byVersion.put(m.version(), m));
        for (var applied : history) {
            var current = byVersion.get(applied.version());
            if (current == null) {
                return new Plan(List.of(), "V" + applied.version() + " está aplicada en la base pero ya no está en el "
                        + "repositorio: no se aplica nada.");
            }
            if (!current.checksum().equals(applied.checksum())) {
                return new Plan(List.of(), "V" + applied.version() + " se editó después de aplicarse: el cambio va en "
                        + "una migración nueva. No se aplicó nada.");
            }
        }
        var appliedVersions = history.stream().map(AppliedMigration::version).toList();
        return new Plan(all.stream().filter(m -> !appliedVersions.contains(m.version())).toList(), null);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
```

- [ ] **Step 4: Run test**

Run: `cd app && mvn -q test -Dtest=MigrationSetTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/database/MigrationSet.java app/src/test/java/com/aicompany/core/database/MigrationSetTest.java
git commit -m "DBA: migraciones pendientes con checksum (una editada después de aplicarse frena todo)"
```

---

### Task 6: Aplicador JDBC con prueba de integración real

**Files:**
- Create: `app/src/main/java/com/aicompany/core/database/DatabaseSchemaApplier.java`
- Modify: `app/pom.xml` (test: `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`)
- Test: `app/src/test/java/com/aicompany/core/database/DatabaseSchemaApplierIT.java`

**Interfaces:**
- Consumes: `PostgresConnector`, `MigrationSet` (Tasks 1 y 5).
- Produces: `record ApplyResult(List<Integer> applied, Integer failedVersion, String error, boolean createdDatabase)`; `DatabaseSchemaApplier.apply(DatabaseConnection c, String password, List<MigrationSet.Migration> migrations, String missionId, String commitSha) → ApplyResult`. Nunca lanza por errores del servidor: los devuelve en `error` (sin la clave).

- [ ] **Step 1: Write the failing test** (integración real; se salta sin Docker/Podman)

```java
package com.aicompany.core.database;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 2026-10-02 Testing: PostgreSQL efímero solo para este test; nunca en el sandbox de las misiones. */
@Testcontainers(disabledWithoutDocker = true)
class DatabaseSchemaApplierIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private final DatabaseSchemaApplier applier = new DatabaseSchemaApplier(new JdbcPostgresConnector());

    private DatabaseConnection conn(String database) {
        return new DatabaseConnection("C1", "citas-it", "POSTGRESQL", PG.getHost(), PG.getMappedPort(5432), database,
                PG.getUsername(), "DISABLE", null, "TEST", "****", null, null);
    }

    private static List<MigrationSet.Migration> migrations(Map<String, String> files) {
        return MigrationSet.parse(files);
    }

    @Test
    void createsTheDatabaseAppliesInOrderAndKeepsTheHistory() {
        var files = Map.of(
                "db/postgres/migrations/V1__crear.sql", "CREATE TABLE cita (id serial primary key, fecha date not null);",
                "db/postgres/migrations/V2__indice.sql", "CREATE INDEX ix_cita_fecha ON cita (fecha);");

        var first = applier.apply(conn("citas_it"), PG.getPassword(), migrations(files), "M-1", "abc");
        var second = applier.apply(conn("citas_it"), PG.getPassword(), migrations(files), "M-1", "abc");

        assertTrue(first.createdDatabase());
        assertEquals(List.of(1, 2), first.applied());
        assertNull(first.error());
        assertEquals(List.of(), second.applied());
        assertNull(second.error());
    }

    @Test
    void aFailingMigrationStopsThereAndKeepsThePreviousOnes() {
        var files = Map.of(
                "db/postgres/migrations/V1__ok.sql", "CREATE TABLE a (id int);",
                "db/postgres/migrations/V2__mal.sql", "CREATE TABL b (id int);",
                "db/postgres/migrations/V3__nunca.sql", "CREATE TABLE c (id int);");

        var result = applier.apply(conn("parcial_it"), PG.getPassword(), migrations(files), "M-2", "abc");

        assertEquals(List.of(1), result.applied());
        assertEquals(2, result.failedVersion());
        assertTrue(result.error().contains("syntax"), result.error());
    }

    @Test
    void theApplyResultNeverContainsThePassword() {
        var bad = new DatabaseConnection("C1", "citas-it", "POSTGRESQL", PG.getHost(), PG.getMappedPort(5432), "x",
                PG.getUsername(), "DISABLE", null, "TEST", "****", null, null);
        var result = applier.apply(bad, "clave-incorrecta-123", migrations(Map.of()), "M-3", "abc");
        assertNotNull(result.error());
        assertFalse(result.error().contains("clave-incorrecta-123"), result.error());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && mvn -q test -Dtest=DatabaseSchemaApplierIT`
Expected: FAIL de compilación (`DatabaseSchemaApplier`, dependencias de Testcontainers). Para correr con Podman sin root: `export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true` antes de `mvn`.

- [ ] **Step 3: Implement**

`app/pom.xml` (scope `test`, versiones gestionadas por el BOM de Spring Boot; si no, `1.20.x`):

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
```

`DatabaseSchemaApplier.java`:

```java
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
                    + DatabaseConnectionService.redact(ex.getMessage(), password), false);
        }
        try (var connection = connector.open(c, password, null)) {
            connection.setAutoCommit(true);
            try (var st = connection.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS forjai_schema_history (version int primary key, "
                        + "description text not null, checksum char(64) not null, applied_at timestamptz not null default now(), "
                        + "mission_id text, commit_sha text)");
            }
            var history = new ArrayList<MigrationSet.AppliedMigration>();
            try (var st = connection.createStatement();
                 var rs = st.executeQuery("SELECT version, checksum FROM forjai_schema_history ORDER BY version")) {
                while (rs.next()) {
                    history.add(new MigrationSet.AppliedMigration(rs.getInt(1), rs.getString(2)));
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
                            + DatabaseConnectionService.redact(ex.getMessage(), password), created);
                }
            }
            return new ApplyResult(applied, null, null, created);
        } catch (SQLException ex) {
            return new ApplyResult(applied, null, DatabaseConnectionService.redact(ex.getMessage(), password), created);
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
```

(`c.database()` ya validado en la Task 1 con `[A-Za-z_][A-Za-z0-9_]{0,62}`: el `CREATE DATABASE` no admite parámetros.)

- [ ] **Step 4: Run test**

Run: `cd app && DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true mvn -q test -Dtest=DatabaseSchemaApplierIT`
Expected: PASS (3 tests). Sin Podman/Docker: los 3 se saltan (no fallan).

- [ ] **Step 5: Commit**

```bash
git add app/pom.xml app/src/main/java/com/aicompany/core/database/DatabaseSchemaApplier.java app/src/test/java/com/aicompany/core/database/DatabaseSchemaApplierIT.java
git commit -m "DBA: aplicador JDBC (crea la base, historial, transacción por migración) con prueba real en PostgreSQL efímero"
```

---

### Task 7: Aplicar el esquema de una misión

**Files:**
- Create: `app/src/main/java/com/aicompany/core/database/DatabaseApplyService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (aplica tras `VERIFIED` y lo suma al estado verificable)
- Create: `app/src/main/java/com/aicompany/core/controller/MissionDatabaseController.java` (`POST /api/company/missions/{id}/database/apply`, `GET /api/company/missions/{id}/database`)
- Test: `app/src/test/java/com/aicompany/core/database/DatabaseApplyServiceTest.java`, `DevelopmentTeamStrategyTest`

**Interfaces:**
- Consumes: `DatabaseConnectionService.connectionsOf/password`, `DatabaseSchemaApplier.apply`, `MigrationSet.parse`, `DevelopmentWorkspaceService.filesAtCommit/readFileAtCommit/headSha`, `MissionMemoryService.lastTeamPlanJson/createTask/updateTask/recordEvidence`.
- Produces: `record MissionDatabaseStatus(String connectionName, List<MigrationLine> migrations, String lastResult)` con `record MigrationLine(int version, String description, String state)` (`APPLIED|PENDING|FAILED`); `DatabaseApplyService.apply(String missionId, String commitSha, TeamPlan plan) → String` (texto para el estado verificable), `DatabaseApplyService.applyLatest(String missionId) → String` (a pedido; exige VALIDATION `VERIFIED`), `DatabaseApplyService.status(String missionId) → Optional<MissionDatabaseStatus>`.

- [ ] **Step 1: Write the failing tests**

```java
package com.aicompany.core.database;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.DevelopmentWorkspaceService;
import com.aicompany.core.service.MissionMemoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseApplyServiceTest {

    private final DatabaseConnectionService connections = mock(DatabaseConnectionService.class);
    private final DatabaseSchemaApplier applier = mock(DatabaseSchemaApplier.class);
    private final DevelopmentWorkspaceService workspace = mock(DevelopmentWorkspaceService.class);
    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final DatabaseApplyService service = new DatabaseApplyService(connections, applier, workspace, memory, events);

    private static final DatabaseConnection CITAS = new DatabaseConnection("C1", "citas-dev-aws", "POSTGRESQL",
            "db.secreto.rds.amazonaws.com", 5432, "citas", "admin", "REQUIRE", null, "TEST", "****9876", null, null);

    private static TeamPlan plan(String connectionName) {
        return new TeamPlan("Citas", null, null, List.of(), List.of(), "DOTNET_APP", List.of(), List.of(),
                new TeamPlan.DatabaseNeed("POSTGRESQL", connectionName));
    }

    @Test
    void appliesTheMigrationsOfTheVerifiedCommitAndRecordsEverything() throws Exception {
        when(connections.connectionsOf("M-1")).thenReturn(List.of(CITAS));
        when(connections.password(CITAS)).thenReturn("S3cret-9876");
        when(workspace.filesAtCommit("M-1", "abc")).thenReturn(List.of("db/postgres/migrations/V1__crear.sql", "src/A.cs"));
        when(workspace.readFileAtCommit("M-1", "abc", "db/postgres/migrations/V1__crear.sql")).thenReturn("CREATE TABLE a (id int);");
        when(applier.apply(eq(CITAS), eq("S3cret-9876"), anyList(), eq("M-1"), eq("abc")))
                .thenReturn(new DatabaseSchemaApplier.ApplyResult(List.of(1), null, null, true));

        var text = service.apply("M-1", "abc", plan("citas-dev-aws"));

        assertTrue(text.contains("V1") && text.contains("citas-dev-aws"), text);
        assertFalse(text.contains("S3cret-9876") || text.contains("rds.amazonaws.com"), text);
        verify(memory).createTask("M-1-DATABASE", "M-1", "forjai", "DATABASE_APPLY");
        verify(memory).updateTask(eq("M-1-DATABASE"), eq("COMPLETED"), anyString());
        verify(memory).recordEvidence(eq("M-1-DATABASE"), eq("M-1"), eq("forjai"), anyList());
        verify(events).publish(eq("EMPRESA_DATABASE_SCHEMA_APPLIED"), eq("M-1"), eq("M-1-DATABASE"), eq("forjai"), anyMap());
    }

    @Test
    void withoutAConnectionTheMissionGetsAPendingNote() {
        when(connections.connectionsOf("M-1")).thenReturn(List.of());
        var text = service.apply("M-1", "abc", plan(""));
        assertTrue(text.contains("Falta la conexión PostgreSQL") && text.contains("aplica el esquema de M-1"), text);
        verifyNoInteractions(applier);
    }

    @Test
    void aServerErrorIsRecordedWithoutThePassword() throws Exception {
        when(connections.connectionsOf("M-1")).thenReturn(List.of(CITAS));
        when(connections.password(CITAS)).thenReturn("S3cret-9876");
        when(workspace.filesAtCommit("M-1", "abc")).thenReturn(List.of());
        when(applier.apply(any(), any(), anyList(), any(), any()))
                .thenReturn(new DatabaseSchemaApplier.ApplyResult(List.of(), 1, "V1 falló: permission denied", false));

        var text = service.apply("M-1", "abc", plan("citas-dev-aws"));

        assertTrue(text.contains("Esquema no aplicado") && text.contains("permission denied"), text);
        verify(memory).updateTask(eq("M-1-DATABASE"), eq("FAILED"), contains("permission denied"));
        verify(events).publish(eq("EMPRESA_DATABASE_SCHEMA_FAILED"), eq("M-1"), eq("M-1-DATABASE"), eq("forjai"), anyMap());
    }

    @Test
    void onDemandItRefusesWhenTheCodeIsNotVerified() {
        when(memory.lastValidationStatus("M-1")).thenReturn(java.util.Optional.of("FAILED"));
        assertThrows(IllegalStateException.class, () -> service.applyLatest("M-1"));
    }
}
```

En `DevelopmentTeamStrategyTest` (usa `stubHappyPath()` y `cleanReview()`; plan con `DatabaseNeed`):

```java
    @Test
    void aVerifiedMissionWithADatabaseAppliesTheSchemaAndReportsIt() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));
        var apply = mock(com.aicompany.core.database.DatabaseApplyService.class);
        when(apply.apply(eq("M-1"), anyString(), any())).thenReturn("Base de datos citas-dev-aws: aplicadas V1.");
        strategy.setDatabaseApply(apply);
        var base = context();
        var plan = new TeamPlan(base.plan().summary(), null, null, base.plan().tasksOrEmpty(), List.of(),
                base.plan().stackProfile(), base.plan().boundedContexts(), base.plan().ubiquitousLanguage(),
                new TeamPlan.DatabaseNeed("POSTGRESQL", "citas-dev-aws"));

        var result = (TeamExecutionResult.Development) strategy.execute(
                new TeamMissionContext("M-1", "crear un juego", base.team(), plan), progress);

        verify(apply).apply(eq("M-1"), anyString(), eq(plan));
        assertTrue(result.verifiableState().contains("aplicadas V1"), result.verifiableState());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn -q test -Dtest='DatabaseApplyServiceTest,DevelopmentTeamStrategyTest'`
Expected: FAIL de compilación.

- [ ] **Step 3: Implement**

- `MissionMemoryService.lastValidationStatus(String missionId) → Optional<String>`: `MATCH (t:AgentTask {missionId:$id, kind:'VALIDATION'}) WHERE t.validationStatus IS NOT NULL RETURN t.validationStatus ORDER BY t.updatedAt DESC LIMIT 1` (y `lastVerifiedCommit(String missionId) → Optional<String>`: el `commitSha` más reciente entre tareas WORK de la misión, o `workspace.headSha` si no hay).
- `DatabaseApplyService.apply(missionId, commitSha, plan)`:
  1. Sin `plan.databaseOrNull()` → `""`.
  2. Busca la conexión por `connectionName` entre `connectionsOf(missionId)`; si no hay → devuelve "Falta la conexión PostgreSQL para crear la base: cárgala en Bases de datos y escribe 'aplica el esquema de <missionId>'." (sin tarea).
  3. Lee `filesAtCommit` filtrando `MigrationFilesGate.ROOT`, arma el mapa con `readFileAtCommit`, `MigrationSet.parse`.
  4. `createTask("<missionId>-DATABASE", missionId, "forjai", "DATABASE_APPLY")`, `updateTask(..., "RUNNING", "Aplicando migraciones en <nombre>.")`.
  5. `applier.apply(...)`; texto: éxito → "Base de datos <nombre> (<entorno>): aplicadas V1, V2" (+ " — base creada" si `createdDatabase`; "sin migraciones nuevas" si `applied` vacío); error → "Esquema no aplicado en <nombre>: <error>".
  6. `updateTask(... COMPLETED|FAILED, texto)`, `recordEvidence` con un `AgentResult.Evidence` por versión aplicada (`description` "Migración V<n> aplicada", `source` `database:<nombre>@<commit>/V<n>`, `sourceType` `INTERNAL`, `verified=true`), evento `EMPRESA_DATABASE_SCHEMA_APPLIED|FAILED` con `{connectionName, applied, failedVersion?, error?}`.
  7. Devuelve el texto. Nunca incluye host, usuario ni clave.
- `applyLatest(missionId)`: exige `lastValidationStatus = VERIFIED` (si no, `IllegalStateException("El código de <id> no está VERIFIED: no se aplica el esquema.")`), parsea el plan de `lastTeamPlanJson` y usa `lastVerifiedCommit`.
- `status(missionId)`: plan + conexión + migraciones del último commit + historial (si la conexión existe, una consulta de solo lectura a `forjai_schema_history`; si falla, `lastResult` con el error) → `MissionDatabaseStatus`.
- `DevelopmentTeamStrategy`: setter opcional `setDatabaseApply(DatabaseApplyService)`; después de `StaticValidationStatus.compute`, si `status == VERIFIED && plan.databaseOrNull() != null && databaseApply != null`, `var dbText = databaseApply.apply(missionId, headSha, plan)` y se agrega al final de `verifiableState(...)` como sección "Base de datos: …"; si no está VERIFIED y hay base, se agrega "Base de datos: no se aplicó (el código no está VERIFIED)".
- `MissionDatabaseController`: `POST /api/company/missions/{id}/database/apply` → `Map.of("result", applyLatest(id))`; `GET /api/company/missions/{id}/database` → `status(id)` o 404.
- `.env.example`: lo genera Iris (prompt de la Task 4); no hay código extra.

- [ ] **Step 4: Run tests**

Run: `cd app && mvn -q test -Dtest='DatabaseApplyServiceTest,DevelopmentTeamStrategyTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "DBA: aplicar el esquema de una misión VERIFIED (automático y a pedido), con tarea, evidencia y eventos"
```

---

### Task 8: Npgsql en el sandbox y en la política de dependencias

**Files:**
- Modify: `sandbox/images/dotnet-app/Dockerfile`
- Modify: `app/src/main/java/com/aicompany/core/model/BaselineDependencies.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/LicenseClassifier.java`
- Modify: `app/src/main/java/com/aicompany/core/model/StackProfile.java` (`executionContract()` de `DOTNET_APP` menciona Npgsql y su versión)
- Test: `BaselineDependenciesTest` (o el test existente de dependencias), `LicenseClassifierTest`

**Interfaces:**
- Produces: `NUGET:npgsql@8.0.5` en `BaselineDependencies`; `PostgreSQL` en `LicenseClassifier.ALLOWED`.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void npgsqlIsPreapprovedForTheDataArchitect() {
        assertTrue(BaselineDependencies.contains(new DependencyRef("NUGET", "npgsql", "8.0.5")));
    }
```

(en el test de `BaselineDependencies`; si no existe, crear `app/src/test/java/com/aicompany/core/model/BaselineDependenciesTest.java` con ese test; ajustar el constructor de `DependencyRef` al que exista: `grep -n "record DependencyRef" -A3 app/src/main/java/com/aicompany/core/model/DependencyRef.java`).

```java
    @Test
    void thePostgresqlLicenseIsPermissive() {
        assertTrue(LicenseClassifier.ALLOWED.contains("PostgreSQL"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn -q test -Dtest='BaselineDependenciesTest,LicenseClassifierTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

- `BaselineDependencies.IDS`: agregar `"NUGET:npgsql@8.0.5"` y sus dependencias transitivas que no vengan con el SDK (verificar con el paso de imagen: `dotnet list package --include-transitive` en el seed).
- `LicenseClassifier.ALLOWED`: agregar `"PostgreSQL"`; actualizar el javadoc y el mensaje de `DependencyPolicy` ("MIT, Apache-2.0, BSD-2/3-Clause, ISC, Zlib, PostgreSQL").
- `Dockerfile`, en el `RUN` del seed, antes del `rm -rf /opt/seed`:

```dockerfile
    && dotnet add /opt/seed/SeedApi package Npgsql --version 8.0.5 --package-directory /opt/nuget-packages \
    && dotnet restore /opt/seed/SeedApi --packages /opt/nuget-packages \
```

- `StackProfile.executionContract()` de `DOTNET_APP`: agregar "Npgsql 8.0.5 disponible sin red para la persistencia PostgreSQL (la conexión se lee de las variables del contrato)".

- [ ] **Step 4: Run tests and rebuild the image**

Run: `cd app && mvn -q test -Dtest='BaselineDependenciesTest,LicenseClassifierTest,StackProfileTest'`
Expected: PASS.

Run: `bash sandbox/build-images.sh` (o solo la imagen de .NET con el comando que use ese script)
Expected: la imagen `localhost/forjai-sandbox/dotnet-app:1` se construye; `podman run --rm localhost/forjai-sandbox/dotnet-app:1 ls /opt/nuget-packages/npgsql` lista `8.0.5`.

- [ ] **Step 5: Commit**

```bash
git add sandbox/images/dotnet-app/Dockerfile app/src
git commit -m "DBA: Npgsql precargado en el sandbox y preaprobado; licencia PostgreSQL permitida"
```

---

### Task 9: Chat

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `app/src/main/java/com/aicompany/core/service/ConversationMemoryService.java` (redacción de claves)
- Test: `ChatIntentRouterTest`, `ConversationMemoryServiceRedactionTest`

**Interfaces:**
- Consumes: `DatabaseConnectionService.list/linkToMission`, `DatabaseApplyService.applyLatest/status`, `MissionService.start(..., List<String> databaseNames)` (setters opcionales en el router).
- Produces: `static String ConversationMemoryService.redactSecrets(String text)`.

- [ ] **Step 1: Write the failing tests**

```java
package com.aicompany.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryServiceRedactionTest {

    @Test
    void aPastedPasswordIsNotStored() {
        var text = ConversationMemoryService.redactSecrets(
                "usa Host=db.x.com;Username=admin;Password=S3cret-9876 y también postgres://admin:otra-clave@db:5432/c");
        assertFalse(text.contains("S3cret-9876"), text);
        assertFalse(text.contains("otra-clave"), text);
        assertTrue(text.contains("[clave omitida]"), text);
    }

    @Test
    void normalMessagesAreUntouched() {
        assertEquals("¿qué bases de datos hay?", ConversationMemoryService.redactSecrets("¿qué bases de datos hay?"));
    }
}
```

En `ChatIntentRouterTest` (con mocks `databaseConnections` y `databaseApply` inyectados por setter en el `router`):

```java
    @Test
    void listsTheDatabasesWithoutSecrets() {
        router.setDatabaseConnections(databaseConnections);
        when(databaseConnections.list()).thenReturn(List.of(new com.aicompany.core.database.DatabaseConnection("C1",
                "citas-dev-aws", "POSTGRESQL", "db.secreto.rds.amazonaws.com", 5432, "citas", "admin", "REQUIRE", null,
                "TEST", "****9876", "2026-10-02T00:00:00Z", "OK")));

        var response = router.route("¿qué bases de datos hay?");

        assertTrue(response.contains("citas-dev-aws") && response.contains("****9876"), response);
        assertFalse(response.contains("S3cret"), response);
        verifyNoInteractions(ceoService);
    }

    @Test
    void applyTheSchemaIsAFounderCommand() {
        router.setDatabaseApply(databaseApply);
        when(databaseApply.applyLatest("MISSION-12")).thenReturn("Base de datos citas-dev-aws (TEST): aplicadas V1, V2");

        var response = router.route("aplica el esquema de MISSION-12");

        assertTrue(response.contains("aplicadas V1, V2"), response);
        verifyNoInteractions(ceoService);
    }

    @Test
    void aMissionStartCanNameItsDatabase() {
        router.setDatabaseConnections(databaseConnections);
        var message = "CEO, inicia una misión para TEAM-DEVELOPMENT para crear una API de citas, usa la base citas-dev-aws";
        when(missionService.start(anyString(), eq(message), eq("PRODUCTION"), isNull(), eq("TEAM-DEVELOPMENT"),
                eq(List.of("citas-dev-aws")))).thenReturn(created("MISSION-1", "TEAM-DEVELOPMENT"));

        router.route(message);

        verify(missionService).start(anyString(), eq(message), eq("PRODUCTION"), isNull(), eq("TEAM-DEVELOPMENT"),
                eq(List.of("citas-dev-aws")));
    }
```

(`created(...)` es el helper que ya usa el archivo para respuestas de misión.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn -q test -Dtest='ConversationMemoryServiceRedactionTest,ChatIntentRouterTest'`
Expected: FAIL de compilación.

- [ ] **Step 3: Implement**

- `ConversationMemoryService.redactSecrets`: reemplaza `(?i)(password|pwd)\s*=\s*[^;\s]+` por `$1=[clave omitida]` y `(?i)(postgres(ql)?|mongodb(\+srv)?)://([^:/@\s]+):[^@\s]+@` por `$1://$4:[clave omitida]@`; `recordMessage` guarda `redactSecrets(content)`.
- `ChatIntentRouter` (con la gobernanza, antes de las menciones; setters opcionales `setDatabaseConnections`, `setDatabaseApply`):
  - `\baplica(r)?\s+el\s+esquema\s+de\s+(MISSION-[\w-]+)` → `databaseApply.applyLatest(id)` (errores → su mensaje).
  - Consulta "¿qué bases de datos hay?" / "bases de datos" + "hay|cargadas|lista" → lista en Java: `nombre (motor, entorno, TLS) — base <database>, usuario …, clave ****1234, última prueba: <resultado>` (sin host ni usuario completos: mostrar `usuario` sí, el fundador lo cargó; nunca la clave). Si no hay: "No hay bases de datos cargadas. Se cargan en Settings → Bases de datos."
  - "¿cómo va la base de MISSION-X?" → `databaseApply.status(id)` formateado (migraciones con estado y último resultado).
  - Arranque de misión: si el mensaje contiene `usa (la )?base ([a-z0-9-]+)`, se pasa `List.of(nombre)` a `missionService.start(..., databaseNames)` (el de 6 argumentos); si el nombre no existe, la respuesta es el mensaje de `linkToMission`/`requireExisting` con los nombres válidos y no se crea la misión.
  - Si `redactSecrets(message)` cambió el mensaje, la respuesta agrega: "Detecté una clave en tu mensaje y no la guardé: las conexiones se cargan en Settings → Bases de datos."

- [ ] **Step 4: Run tests**

Run: `cd app && mvn -q test -Dtest='ConversationMemoryServiceRedactionTest,ChatIntentRouterTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "DBA: chat (bases cargadas, usar una base al iniciar, aplicar esquema, estado) y claves nunca guardadas en la conversación"
```

---

### Task 10: Command Center

**Files:**
- Modify: `app/frontend/src/api/types.ts`, `app/frontend/src/api/client.ts`
- Create: `app/frontend/src/components/DatabasesSettings.tsx`
- Modify: `app/frontend/src/pages/SettingsPage.tsx` (incluye `<DatabasesSettings />`)
- Modify: `app/frontend/src/pages/MissionsPage.tsx` (selector de bases en el formulario; sección "Base de datos" en el detalle de una misión con `teamId`)

**Interfaces:**
- Consumes: `/api/company/databases` (Task 1/2), `/api/company/missions/{id}/database` y `/database/apply` (Task 7), `MissionCommand.databases` (Task 2).

- [ ] **Step 1: Types and client**

`types.ts`:

```ts
export interface DatabaseConnectionInfo {
  id: string
  name: string
  engine: string
  host: string
  port: number
  database: string
  username: string
  tls: 'DISABLE' | 'REQUIRE' | 'VERIFY_FULL'
  environment: 'TEST' | 'PRODUCTION'
  passwordHint: string
  lastCheckedAt: string | null
  lastCheckResult: string | null
}

export interface DatabaseConnectionForm {
  name: string
  engine: 'POSTGRESQL'
  host: string
  port: number
  database: string
  username: string
  password: string
  tls: 'DISABLE' | 'REQUIRE' | 'VERIFY_FULL'
  caCertPem: string
  environment: 'TEST' | 'PRODUCTION'
}

export interface MissionDatabaseStatus {
  connectionName: string | null
  migrations: { version: number; description: string; state: 'APPLIED' | 'PENDING' | 'FAILED' }[]
  lastResult: string | null
}
```

`client.ts` (dentro de `api`):

```ts
  databases: () => request<DatabaseConnectionInfo[]>('/api/company/databases'),
  createDatabase: (form: DatabaseConnectionForm) =>
    request<DatabaseConnectionInfo>('/api/company/databases', { method: 'POST', body: JSON.stringify(form) }),
  updateDatabase: (id: string, form: DatabaseConnectionForm) =>
    request<DatabaseConnectionInfo>(`/api/company/databases/${id}`, { method: 'PUT', body: JSON.stringify(form) }),
  testDatabase: (id: string) => request<{ result: string }>(`/api/company/databases/${id}/test`, { method: 'POST' }),
  deleteDatabase: (id: string) => request<unknown>(`/api/company/databases/${id}`, { method: 'DELETE' }),
  missionDatabase: (missionId: string) => request<MissionDatabaseStatus>(`/api/company/missions/${missionId}/database`),
  applyMissionDatabase: (missionId: string) =>
    request<{ result: string }>(`/api/company/missions/${missionId}/database/apply`, { method: 'POST' }),
```

y el tipo del comando de misión gana `databases?: string[]`.

- [ ] **Step 2: `DatabasesSettings.tsx`**

```tsx
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { DatabaseConnectionForm, DatabaseConnectionInfo } from '../api/types'

const EMPTY: DatabaseConnectionForm = {
  name: '', engine: 'POSTGRESQL', host: '', port: 5432, database: '', username: '', password: '',
  tls: 'REQUIRE', caCertPem: '', environment: 'TEST',
}

/** Spec 2026-10-02 §4: conexiones del fundador; la clave nunca vuelve del servidor (solo ****1234). */
export function DatabasesSettings() {
  const queryClient = useQueryClient()
  const list = useQuery({ queryKey: ['databases'], queryFn: api.databases })
  const [form, setForm] = useState<DatabaseConnectionForm>(EMPTY)
  const [editing, setEditing] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () => (editing ? api.updateDatabase(editing, form) : api.createDatabase(form)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['databases'] })
      setForm(EMPTY)
      setEditing(null)
    },
  })
  const test = useMutation({ mutationFn: (id: string) => api.testDatabase(id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['databases'] }) })
  const remove = useMutation({ mutationFn: (id: string) => api.deleteDatabase(id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['databases'] }) })
  const edit = (c: DatabaseConnectionInfo) => {
    setEditing(c.id)
    setForm({ name: c.name, engine: 'POSTGRESQL', host: c.host, port: c.port, database: c.database,
      username: c.username, password: '', tls: c.tls, caCertPem: '', environment: c.environment })
  }
  const set = (key: keyof DatabaseConnectionForm) => (e: { target: { value: string } }) =>
    setForm({ ...form, [key]: key === 'port' ? Number(e.target.value) : e.target.value })

  return (
    <section>
      <h2>Bases de datos</h2>
      <p className="hint">Diego crea las bases con estas credenciales. La clave se guarda cifrada y nunca llega a un modelo.</p>
      <table>
        <tbody>
          {(list.data ?? []).map((c) => (
            <tr key={c.id}>
              <td>{c.name}</td>
              <td>{c.engine} · {c.environment} · TLS {c.tls}</td>
              <td>{c.username}@{c.host}:{c.port}/{c.database}</td>
              <td>{c.passwordHint}</td>
              <td>{c.lastCheckResult ?? '—'}</td>
              <td>
                <button onClick={() => test.mutate(c.id)}>Probar</button>
                <button onClick={() => edit(c)}>Editar</button>
                <button onClick={() => remove.mutate(c.id)}>Eliminar</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {remove.isError && <p className="error">{remove.error.message}</p>}
      <div className="decision-form">
        <h3>{editing ? 'Editar conexión' : 'Agregar conexión'}</h3>
        <input placeholder="nombre (p. ej. citas-dev-aws)" value={form.name} onChange={set('name')} />
        <input placeholder="host" value={form.host} onChange={set('host')} />
        <input type="number" placeholder="puerto" value={form.port} onChange={set('port')} />
        <input placeholder="base de datos" value={form.database} onChange={set('database')} />
        <input placeholder="usuario" value={form.username} onChange={set('username')} />
        <input type="password" placeholder={editing ? 'clave (vacía = conservar)' : 'clave'} value={form.password}
          onChange={set('password')} />
        <select value={form.tls} onChange={set('tls')}>
          <option value="REQUIRE">TLS obligatorio (AWS RDS)</option>
          <option value="VERIFY_FULL">TLS con verificación (requiere CA)</option>
          <option value="DISABLE">Sin TLS</option>
        </select>
        {form.tls === 'VERIFY_FULL' && (
          <textarea placeholder="Certificado CA en PEM" value={form.caCertPem} onChange={set('caCertPem')} />
        )}
        <select value={form.environment} onChange={set('environment')}>
          <option value="TEST">TEST</option>
          <option value="PRODUCTION">PRODUCTION</option>
        </select>
        <button disabled={save.isPending} onClick={() => save.mutate()}>Probar y guardar</button>
        {editing && <button onClick={() => { setEditing(null); setForm(EMPTY) }}>Cancelar</button>}
        {save.isError && <p className="error">{save.error.message}</p>}
        {test.isSuccess && <p className="hint">{test.data.result}</p>}
      </div>
    </section>
  )
}
```

`SettingsPage.tsx`: importar y renderizar `<DatabasesSettings />` como sección nueva.

- [ ] **Step 3: Missions**

En el formulario de inicio (`MissionsPage.tsx`), cuando el equipo es `TEAM-DEVELOPMENT`, un `<select multiple>` con `api.databases()` cuyos valores (nombres) van en `databases` del comando. En el detalle de una misión con `teamId`, una sección "Base de datos" con `useQuery(['missionDatabase', id], () => api.missionDatabase(id))` (ignorar 404), una lista `V<n> <descripcion> — <estado>`, `lastResult` y un botón "Aplicar esquema" que llama a `api.applyMissionDatabase(id)` y muestra `result` o el error.

- [ ] **Step 4: Verify**

Run: `cd app/frontend && npm run lint && npm run build`
Expected: sin errores nuevos (el aviso existente de `SettingsPage.tsx` puede seguir).

- [ ] **Step 5: Commit**

```bash
git add app/frontend/src
git commit -m "DBA: Command Center (Settings → Bases de datos, bases en Missions y botón Aplicar esquema)"
```

---

### Task 11: Suite completa y documentación

**Files:**
- Modify: `CLAUDE.md`, `docs/EVENTS.md`, `docs/FALTANTES.md`

- [ ] **Step 1: Suite completa**

Run: `cd app && mvn test` (con `DOCKER_HOST` de Podman si se quiere correr el IT)
Expected: BUILD SUCCESS.

Run: `cd app/frontend && npm run lint && npm run build`
Expected: sin errores.

- [ ] **Step 2: Documentación**

- `CLAUDE.md`: sección nueva "Diego como DBA" con: conexiones (`DatabaseConnectionService`, cifrado, prueba antes de guardar, contrato `DB_<NOMBRE>_*`, nunca al modelo), plan con `database` (DOTNET_APP + DATA_ARCHITECT), gates (`MigrationFilesGate`, `SecretLiteralGate`, `DatabaseContractGate`), `DatabaseSchemaApplier` (`forjai_schema_history`, checksum, transacción por migración, solo con `VERIFIED`, automático y a pedido), Npgsql preaprobado, chat y Settings; y que el sandbox sigue sin bases (tests con fakes).
- `docs/EVENTS.md`: `EMPRESA_DATABASE_CONNECTION_SAVED|DELETED` y `EMPRESA_DATABASE_SCHEMA_APPLIED|FAILED` con sus `data`.
- `docs/FALTANTES.md`: marcar `[x]` "Diego como DBA: conexiones y PostgreSQL" (con el PR al mergear).

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md docs/EVENTS.md docs/FALTANTES.md
git commit -m "Docs: Diego como DBA (conexiones y PostgreSQL)"
```
