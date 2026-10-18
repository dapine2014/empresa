package com.aicompany.core.agent.validation;

import com.aicompany.core.model.HttpRoute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpRouteExtractorTest {

    @Test
    // /health es del sandbox, no del contrato (revisión final I3).
    void readsTheFiveMinimalApiVerbs() {
        var routes = HttpRouteExtractor.extract(Map.of("src/X.Api/Program.cs", """
                var app = builder.Build();
                app.MapGet("/health", () => Results.Ok());
                app.MapPost("/clientes", (Crear c) => Results.Created());
                app.MapPut("/clientes/{id}", (Guid id) => Results.Ok());
                app.MapPatch("clientes/{id}/estado", (Guid id) => Results.Ok());
                app.MapDelete("/clientes/{id:guid}", (Guid id) => Results.NoContent());
                """));
        assertEquals(List.of(new HttpRoute("POST", "/clientes"), new HttpRoute("DELETE", "/clientes/{id}"),
                new HttpRoute("PUT", "/clientes/{id}"), new HttpRoute("PATCH", "/clientes/{id}/estado")), routes);
    }

    // Review Focus 3: grupos en variables, encadenados y definidos en otro archivo.
    @Test
    void resolvesGroupsInVariablesChainsAndOtherFiles() {
        var routes = HttpRouteExtractor.extract(Map.of(
                "src/X.Api/Program.cs", """
                        var api = app.MapGroup("/api");
                        RouteGroupBuilder v1 = api.MapGroup("v1/");
                        v1.MapGet("/clientes/{id:guid}", (Guid id) => Results.Ok());
                        app.MapGroup("/admin").MapGroup("/reportes").MapGet("", () => Results.Ok());
                        """,
                "src/X.Api/Endpoints.cs", """
                        public static class Endpoints {
                            public static void Map(IEndpointRouteBuilder routes) {
                                routes.MapPost("/oportunidades", () => Results.Ok());
                                v1.MapDelete("/clientes/{id?}", () => Results.Ok());
                            }
                        }
                        """));
        assertTrue(routes.contains(new HttpRoute("GET", "/api/v1/clientes/{id}")), routes.toString());
        assertTrue(routes.contains(new HttpRoute("GET", "/admin/reportes")), routes.toString());
        assertTrue(routes.contains(new HttpRoute("POST", "/oportunidades")), routes.toString());
        assertTrue(routes.contains(new HttpRoute("DELETE", "/api/v1/clientes/{id}")), routes.toString());
    }

    @Test
    void noLiteralRoutesMeansAnEmptyList() {
        assertEquals(List.of(), HttpRouteExtractor.extract(Map.of("src/X.Api/Program.cs",
                "app.MapControllers(); var ruta = \"/x\"; app.MapGet(ruta, () => 1);")));
    }

    // Revisión final C1: un archivo por recurso, cada uno con su variable "group" (el patrón más común).
    @Test
    void sameVariableNameInSeveralFilesResolvesPerFile() {
        var routes = HttpRouteExtractor.extract(Map.of(
                "src/X.Api/ClientesEndpoints.cs", """
                        var group = app.MapGroup("/clientes");
                        group.MapGet("/", () => Results.Ok());
                        group.MapGet("/{id}", (Guid id) => Results.Ok());
                        """,
                "src/X.Api/PedidosEndpoints.cs", """
                        var group = app.MapGroup("/pedidos");
                        group.MapPost("/", () => Results.Ok());
                        """));
        assertEquals(List.of(new HttpRoute("GET", "/clientes"), new HttpRoute("GET", "/clientes/{id}"),
                new HttpRoute("POST", "/pedidos")), routes);
    }

    // Revisión final C1: llamadas fluidas después de MapGroup (WithTags, RequireCors, WithOpenApi…).
    @Test
    void fluentCallsAfterMapGroupKeepThePrefix() {
        var routes = HttpRouteExtractor.extract(Map.of("src/X.Api/Program.cs", """
                var clientes = app.MapGroup("/clientes").WithTags("Clientes").RequireCors("app");
                clientes.MapGet("/{id}", (Guid id) => Results.Ok());
                app.MapGroup("/pedidos").WithOpenApi().MapPost("", () => Results.Ok());
                """));
        assertEquals(List.of(new HttpRoute("GET", "/clientes/{id}"), new HttpRoute("POST", "/pedidos")), routes);
    }

    // Revisión final C1: una variable cuyo prefijo no se puede saber no se toma como raíz: se informa.
    @Test
    void unresolvableReceiversAreReported() {
        var extraction = HttpRouteExtractor.extractDetailed(Map.of("src/X.Api/Endpoints.cs", """
                public static void MapClientes(this RouteGroupBuilder grupo) {
                    grupo.MapGet("/{id}", (Guid id) => Results.Ok());
                }
                public static void MapRaiz(this IEndpointRouteBuilder routes) {
                    routes.MapGet("/ping", () => Results.Ok());
                }
                """));
        assertEquals(List.of("grupo"), extraction.unresolved());
        assertEquals(List.of(new HttpRoute("GET", "/ping")), extraction.routes());
    }

    // Revisión final M1 + I3: rutas comentadas no cuentan y /health no es parte del contrato de la app.
    @Test
    void commentsAndHealthAreNotPartOfTheContract() {
        var routes = HttpRouteExtractor.extract(Map.of("src/X.Api/Program.cs", """
                var app = builder.Build();
                // app.MapGet("/viejo", () => 1);
                /* app.MapPost("/borrado", () => 1); */
                app.MapGet("/health", () => Results.Ok());
                app.MapGet("/clientes", () => Results.Ok());
                """));
        assertEquals(List.of(new HttpRoute("GET", "/clientes")), routes);
    }
}
