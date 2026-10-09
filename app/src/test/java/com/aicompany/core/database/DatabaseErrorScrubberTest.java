package com.aicompany.core.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Seguimiento Diego ambientes (privacidad): formas reales de mensajes de PostgreSQL. */
class DatabaseErrorScrubberTest {

    @Test
    void invalidInputSyntaxMasksTheValue() {
        var out = DatabaseErrorScrubber.scrub("ERROR: invalid input syntax for type integer: \"x@y.com\"");
        assertEquals("ERROR: invalid input syntax for type integer: \"<valor>\"", out);
    }

    @Test
    void duplicateKeyMasksTheValuesButKeepsTheColumnsAndConstraint() {
        var out = DatabaseErrorScrubber.scrub("ERROR: duplicate key value violates unique constraint \"uq_clientes_email\"\n"
                + "Key (email)=(ana@correo.com) already exists.");
        assertEquals("ERROR: duplicate key value violates unique constraint \"uq_clientes_email\"\n"
                + "Key (email)=(<valor>) already exists.", out);
    }

    @Test
    void valueTooLongKeepsTheTypeButNotQuotedData() {
        var out = DatabaseErrorScrubber.scrub("ERROR: value too long for type character varying(10) 'Maria Fernanda Gomez'");
        assertFalse(out.contains("Maria"), out);
        assertEquals("ERROR: value too long for type character varying(10) '<valor>'", out);
    }

    @Test
    void undefinedTableKeepsTheIdentifier() {
        var msg = "ERROR: relation \"clientes\" does not exist\n  Position: 15";
        assertEquals(msg, DatabaseErrorScrubber.scrub(msg));
    }

    @Test
    void identifiersWithUppercaseDigitsOnlyOrSpacesAreMasked() {
        assertEquals("column \"<valor>\" 'ab' \"<valor>\" \"<valor>\"",
                DatabaseErrorScrubber.scrub("column \"Email\" 'ab' \"123\" \"a b\""));
    }

    @Test
    void nullAndPlainTextPass() {
        assertEquals(null, DatabaseErrorScrubber.scrub(null));
        assertEquals("canceling statement due to lock timeout", DatabaseErrorScrubber.scrub("canceling statement due to lock timeout"));
    }
}
