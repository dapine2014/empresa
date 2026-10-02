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

    private static java.sql.Connection okConnection() throws SQLException {
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.Statement.class);
        doReturn(statement).when(connection).createStatement();
        return connection;
    }

    private static DatabaseConnectionCommand command(String password) {
        return new DatabaseConnectionCommand("citas-dev-aws", "POSTGRESQL", "db.rds.amazonaws.com", 5432, "citas",
                "admin_citas", password, "REQUIRE", null, "TEST");
    }

    @Test
    void aWorkingConnectionIsSavedEncryptedWithOnlyAHint() throws Exception {
        var ok = okConnection();
        doReturn(ok).when(connector).open(any(), eq("S3cret-9876"), isNull());
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
        doThrow(new SQLException("password authentication failed")).when(connector).open(any(), anyString(), isNull());

        var ex = assertThrows(IllegalArgumentException.class, () -> service.create(command("mala")));

        assertTrue(ex.getMessage().contains("password authentication failed"), ex.getMessage());
        verify(memory, never()).save(any(), any());
    }

    @Test
    void aDatabaseThatDoesNotExistYetIsCheckedAgainstTheMaintenanceDatabase() throws Exception {
        var ok = okConnection();
        when(memory.byName(anyString())).thenReturn(Optional.empty());
        doThrow(new SQLException("database \"citas\" does not exist", "3D000")).when(connector).open(any(), anyString(), isNull());
        doReturn(ok).when(connector).open(any(), anyString(), eq("postgres"));

        assertEquals("citas-dev-aws", service.create(command("S3cret-9876")).name());
    }

    @Test
    void theTestErrorNeverContainsThePassword() throws Exception {
        when(memory.byName(anyString())).thenReturn(Optional.empty());
        doThrow(new SQLException("FATAL: no pg_hba.conf entry; url=jdbc:postgresql://x?password=S3cret-9876"))
                .when(connector).open(any(), anyString(), isNull());

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
        var ok = okConnection();
        var existing = new DatabaseConnection("C1", "citas-dev-aws", "POSTGRESQL", "h", 5432, "citas", "u",
                "REQUIRE", null, "TEST", "****9876", null, null);
        when(memory.byId("C1")).thenReturn(Optional.of(existing));
        when(memory.cipherText("C1")).thenReturn(Optional.of(cipher.encrypt("S3cret-9876")));
        doReturn(ok).when(connector).open(any(), eq("S3cret-9876"), isNull());

        service.update("C1", command(""));

        var cipherText = ArgumentCaptor.forClass(String.class);
        verify(memory).save(any(), cipherText.capture());
        assertEquals("S3cret-9876", cipher.decrypt(cipherText.getValue()));
    }
}
