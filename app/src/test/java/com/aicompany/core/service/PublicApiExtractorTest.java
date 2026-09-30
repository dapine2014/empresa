package com.aicompany.core.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verificado en vivo (MISSION-ORQ-1790736885126, 2026-09-30): el código previo llegaba recortado (18K de 92K) y las
 * capas siguientes inventaban miembros (Exito() en vez de Exitoso(), SembrarAsync(), AddInfrastructureServices()).
 * El contrato de API lo extrae Java y nunca se recorta.
 */
class PublicApiExtractorTest {

    private static final String DOMAIN = """
            namespace Firmas.Domain;

            public sealed record ResultadoValidacion(bool EsValido, IReadOnlyList<string> Errores)
            {
                public static ResultadoValidacion Exitoso() => new(true, Array.Empty<string>());

                public static ResultadoValidacion ConErrores(IEnumerable<string> errores)
                {
                    return new(false, errores.ToList());
                }

                private static void Interno() { }
            }

            public interface IPlantillaRepository
            {
                Task<Plantilla?> ObtenerAsync(Guid id, CancellationToken ct = default);
                Task GuardarAsync(Plantilla plantilla);
            }

            public class Plantilla
            {
                public Plantilla(Guid id, string nombre)
                {
                    Id = id;
                }

                public Guid Id { get; }
                public string Nombre { get; private set; } = "";
            }
            """;

    private static final String INFRA = """
            using Microsoft.Extensions.DependencyInjection;

            namespace Firmas.Infrastructure
            {
                public static class InfrastructureServices
                {
                    public static IServiceCollection AddInfrastructureServices(this IServiceCollection services)
                    {
                        return services;
                    }
                }
            }
            """;

    private static final String DART = """
            import 'package:flutter/material.dart';

            class SignatureService {
              SignatureService(this.repository);

              final SignatureRepository repository;

              Future<Signature> create(String name, {String? title}) async {
                return Signature(name);
              }

              String _privateHelper() => '';
            }

            abstract class SignatureRepository {
              Future<void> save(Signature signature);
            }
            """;

    @Test
    void csharpSignaturesKeepExactNamesAndDropBodies() {
        var api = PublicApiExtractor.extract(Map.of("src/Firmas.Domain/ResultadoValidacion.cs", DOMAIN));

        assertTrue(api.contains("namespace Firmas.Domain"), api);
        assertTrue(api.contains("public sealed record ResultadoValidacion(bool EsValido, IReadOnlyList<string> Errores)"), api);
        assertTrue(api.contains("public static ResultadoValidacion Exitoso()"), api);
        assertTrue(api.contains("public static ResultadoValidacion ConErrores(IEnumerable<string> errores)"), api);
        assertTrue(api.contains("Task<Plantilla?> ObtenerAsync(Guid id, CancellationToken ct = default);"), api);
        assertTrue(api.contains("public Plantilla(Guid id, string nombre)"), api);
        assertTrue(api.contains("public Guid Id { get; }"), api);
        assertFalse(api.contains("Interno"), api);
        assertFalse(api.contains("return new"), api);
        assertFalse(api.contains("Id = id"), api);
    }

    @Test
    void extensionMethodsShowTheirThisParameter() {
        var api = PublicApiExtractor.extract(Map.of("src/Firmas.Infrastructure/ServiceCollectionExtensions.cs", INFRA));

        assertTrue(api.contains("namespace Firmas.Infrastructure"), api);
        assertTrue(api.contains("public static class InfrastructureServices"), api);
        assertTrue(api.contains("public static IServiceCollection AddInfrastructureServices(this IServiceCollection services)"), api);
    }

    @Test
    void dartPublicClassesAndMembersWithoutPrivateOnes() {
        var api = PublicApiExtractor.extract(Map.of("lib/application/signature_service.dart", DART));

        assertTrue(api.contains("class SignatureService"), api);
        assertTrue(api.contains("SignatureService(this.repository)"), api);
        assertTrue(api.contains("Future<Signature> create(String name, {String? title})"), api);
        assertTrue(api.contains("abstract class SignatureRepository"), api);
        assertTrue(api.contains("Future<void> save(Signature signature);"), api);
        assertFalse(api.contains("_privateHelper"), api);
    }

    // Verificado con el repositorio real: los records con parámetros en varias líneas quedaban cortados en "(".
    @Test
    void multiLineSignaturesAreJoinedUntilTheParenthesisCloses() {
        var api = PublicApiExtractor.extract(Map.of("src/Firmas.Application/DTOs/Respuesta.cs", """
                namespace Firmas.Application.DTOs;

                public record ResultadoValidacionResponse(
                    bool EsValido,
                    IReadOnlyList<string> Errores);

                public class Handler
                {
                    public Task<ResultadoValidacionResponse> HandleAsync(
                        Guid firmaId,
                        CancellationToken ct)
                    {
                        return null!;
                    }
                }
                """));

        assertTrue(api.contains("public record ResultadoValidacionResponse( bool EsValido, IReadOnlyList<string> Errores)"), api);
        assertTrue(api.contains("public Task<ResultadoValidacionResponse> HandleAsync( Guid firmaId, CancellationToken ct)"), api);
        assertFalse(api.contains("return null"), api);
    }

    @Test
    void filesAreListedInOrderWithTheirPath() {
        var contents = new LinkedHashMap<String, String>();
        contents.put("src/A.cs", "namespace A;\npublic class Uno { }");
        contents.put("src/B.cs", "namespace B;\npublic class Dos { }");

        var api = PublicApiExtractor.extract(contents);

        assertTrue(api.indexOf("### src/A.cs") < api.indexOf("### src/B.cs"), api);
    }
}
