package com.aicompany.core.agent.validation;

import com.aicompany.core.model.StackProfile;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DddLayerCheckerDartTest {

    private static final List<String> CONTEXTS = List.of("pedidos", "catalogo");

    private static Map<String, String> repo(String path, String content) {
        var files = new LinkedHashMap<String, String>();
        files.put("pubspec.yaml", "name: tienda\nenvironment:\n  sdk: '>=3.0.0 <4.0.0'\n");
        files.put(path, content);
        return files;
    }

    private static List<DddLayerChecker.Violation> check(Map<String, String> files) {
        return DddLayerChecker.check(StackProfile.FLUTTER_WEB_APP, CONTEXTS, files);
    }

    @Test
    void domainImportingFlutterIsAViolation() {
        var violations = check(repo("lib/pedidos/domain/pedido.dart", "import 'package:flutter/material.dart';"));
        assertEquals(1, violations.size());
    }

    @Test
    void domainImportingDartUiIsAViolation() {
        assertEquals(1, check(repo("lib/pedidos/domain/pedido.dart", "import 'dart:ui';")).size());
    }

    @Test
    void packageImportsOfTheAppAreClassifiedByPath() {
        var violations = check(repo("lib/pedidos/domain/pedido.dart",
                "import 'package:tienda/pedidos/infrastructure/api.dart';"));
        assertEquals(1, violations.size());
    }

    // Review Focus: un import relativo con ../ que cruza al domain de otro contexto.
    @Test
    void relativeImportsCrossingIntoAnotherContextsDomainAreViolations() {
        var violations = check(repo("lib/pedidos/application/crear_pedido.dart",
                "import '../../catalogo/domain/producto.dart';"));
        assertEquals(1, violations.size());
        assertEquals("../../catalogo/domain/producto.dart", violations.get(0).dependency());
    }

    @Test
    void allowedDependenciesPass() {
        var files = repo("lib/pedidos/presentation/pedidos_page.dart", String.join("\n",
                "import 'package:flutter/material.dart';",
                "import '../application/crear_pedido.dart';",
                "import 'package:tienda/pedidos/domain/pedido.dart';",
                "import 'package:tienda/catalogo/application/buscar_productos.dart';"));
        files.put("lib/pedidos/domain/pedido.dart", "import 'linea_pedido.dart';\nimport 'package:equatable/equatable.dart';");
        assertEquals(List.of(), check(files));
    }

    // Review Focus: lib/main.dart es el composition root y está exento.
    @Test
    void theCompositionRootIsExempt() {
        var files = repo("lib/main.dart", String.join("\n",
                "import 'package:flutter/material.dart';",
                "import 'pedidos/infrastructure/api.dart';",
                "import 'pedidos/domain/pedido.dart';"));
        assertEquals(List.of(), check(files));
    }
}
