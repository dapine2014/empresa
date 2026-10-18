package com.aicompany.core.agent.validation;

import com.aicompany.core.model.HttpRoute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiRoutesGeneratorTest {

    @Test
    void oneMemberPerRouteWithStableNamesAndParameters() {
        var dart = ApiRoutesGenerator.generate(List.of(
                new HttpRoute("POST", "/clientes"), new HttpRoute("GET", "/clientes"),
                new HttpRoute("GET", "/clientes/{id}"), new HttpRoute("DELETE", "/clientes/{id}"),
                new HttpRoute("PUT", "/clientes/{clienteId}/oportunidades/{id}"),
                new HttpRoute("GET", "/health")));
        assertTrue(dart.contains("static const ApiRoute crearClientes = ApiRoute('POST', '/clientes');"), dart);
        assertTrue(dart.contains("static const ApiRoute listarClientes = ApiRoute('GET', '/clientes');"), dart);
        assertTrue(dart.contains("static ApiRoute obtenerClientes(String id) => "
                + "ApiRoute('GET', '/clientes/${Uri.encodeComponent(id)}');"), dart);
        assertTrue(dart.contains("static ApiRoute eliminarClientes(String id) =>"), dart);
        assertTrue(dart.contains("static ApiRoute actualizarClientesOportunidades(String clienteId, String id) =>"), dart);
        assertTrue(dart.contains("static const ApiRoute listarHealth = ApiRoute('GET', '/health');"), dart);
        assertTrue(dart.startsWith("// Generado por Forjai"), dart);
    }

    @Test
    void collidingNamesGetANumericSuffixInRouteOrder() {
        var dart = ApiRoutesGenerator.generate(List.of(
                new HttpRoute("GET", "/clientes-vip"), new HttpRoute("GET", "/clientes_vip")));
        assertTrue(dart.contains("listarClientesVip ="), dart);
        assertTrue(dart.contains("listarClientesVip2 ="), dart);
    }

    @Test
    void sameRoutesAlwaysGiveTheSameFile() {
        var routes = List.of(new HttpRoute("GET", "/a"), new HttpRoute("POST", "/a"));
        assertEquals(ApiRoutesGenerator.generate(routes), ApiRoutesGenerator.generate(List.copyOf(routes)));
    }
}
