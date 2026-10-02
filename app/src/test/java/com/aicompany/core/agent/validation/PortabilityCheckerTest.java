package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortabilityCheckerTest {

    // Spec 2026-10-02 B1 §1: el mismo código sirve para web, Android e iOS solo sin librerías solo-web.
    @Test
    void webOnlyImportsAreRejectedInAnyLayer() {
        var check = PortabilityChecker.check(Map.of(
                "lib/clientes/presentation/pantalla.dart", "import 'dart:html';\nimport 'package:flutter/material.dart';",
                "lib/main.dart", "import 'package:web/web.dart' as web;",
                "lib/clientes/domain/cliente.dart", "class Cliente {}"));
        assertFalse(check.passed());
        assertEquals("PORTABILITY", check.check());
        assertTrue(check.detail().contains("lib/clientes/presentation/pantalla.dart → dart:html"), check.detail());
        assertTrue(check.detail().contains("lib/main.dart → package:web/web.dart"), check.detail());
    }

    @Test
    void portableCodePasses() {
        assertTrue(PortabilityChecker.check(Map.of("lib/main.dart",
                "import 'package:flutter/material.dart';\nimport 'package:http/http.dart' as http;")).passed());
    }
}
