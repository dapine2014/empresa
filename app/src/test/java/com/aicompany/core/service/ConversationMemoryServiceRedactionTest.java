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

    // Revisión final (I-2): los formatos en que un humano pega una clave.
    @Test
    void everyCommonWayOfWritingAPasswordIsRedacted() {
        for (var text : java.util.List.of("la clave: S3cret-9876", "password S3cret-9876", "Password: S3cret-9876",
                "{\"password\": \"S3cret-9876\"}", "contraseña=S3cret-9876", "PGPASSWORD=S3cret-9876",
                "DB_X_PASSWORD=S3cret-9876", "Password=\"S3cret 9876\"", "pwd: S3cret-9876")) {
            var redacted = ConversationMemoryService.redactSecrets(text);
            assertFalse(redacted.contains("S3cret"), text + " -> " + redacted);
            assertTrue(ConversationMemoryService.containsSecret(text), text);
        }
        assertFalse(ConversationMemoryService.containsSecret("¿qué bases de datos hay?"));
        assertFalse(ConversationMemoryService.containsSecret("olvidé mi clave del correo"));
    }
}
