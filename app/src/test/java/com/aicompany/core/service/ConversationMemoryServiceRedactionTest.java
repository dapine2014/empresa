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
