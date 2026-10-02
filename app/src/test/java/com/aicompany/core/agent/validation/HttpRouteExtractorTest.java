package com.aicompany.core.agent.validation;

import com.aicompany.core.model.HttpRoute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpRouteExtractorTest {

    @Test
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
                new HttpRoute("PUT", "/clientes/{id}"), new HttpRoute("PATCH", "/clientes/{id}/estado"),
                new HttpRoute("GET", "/health")), routes);
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
}
