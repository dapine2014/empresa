package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.model.HttpRoute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiContractGateTest {

    private static final List<HttpRoute> ROUTES = List.of(new HttpRoute("GET", "/clientes/{id}"),
            new HttpRoute("POST", "/oportunidades"));

    @Test
    void handWrittenBackendRouteIsRejected() {
        var errors = ApiContractGate.check(List.of(new GeneratedFile("app/lib/clientes/infrastructure/repo.dart",
                "final r = await http.get(Uri.parse('$base/clientes/$id'));\nfinal s = \"/oportunidades\";")), ROUTES);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("app/lib/clientes/infrastructure/repo.dart"), errors.get(0));
        assertTrue(errors.get(0).contains("/oportunidades"), errors.get(0));
        assertTrue(errors.get(0).contains("/clientes/"), errors.get(0));
        assertTrue(errors.get(0).contains("ApiRoutes"), errors.get(0));
    }

    @Test
    void localhostIsRejected() {
        var errors = ApiContractGate.check(List.of(new GeneratedFile("app/lib/main.dart",
                "const base = 'http://localhost:5080';")), ROUTES);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("API_BASE_URL"), errors.get(0));
    }

    @Test
    void usingApiRoutesAndAssetsPasses() {
        assertEquals(List.of(), ApiContractGate.check(List.of(
                new GeneratedFile("app/lib/main.dart", "final r = ApiRoutes.obtenerClientes(id); Image.asset('/img/logo.png');"),
                new GeneratedFile("app/lib/api/api_routes.dart", "ApiRoute('GET', '/clientes/$id')"),
                new GeneratedFile("app/pubspec.yaml", "name: app")), ROUTES));
    }

    // Revisión final I4: rutas de navegación de la app y tests con MockClient no son llamadas a la API.
    @Test
    void navigationRoutesAndTestsAreNotApiCalls() {
        assertEquals(List.of(), ApiContractGate.check(List.of(
                new GeneratedFile("app/lib/main.dart",
                        "MaterialApp(routes: {'/oportunidades': (c) => const Lista()});\n"
                                + "Navigator.pushNamed(context, '/clientes/nuevo');"),
                new GeneratedFile("app/test/clientes/repo_test.dart",
                        "import 'package:http/testing.dart';\nexpect(request.url.path, '/clientes/1');")), ROUTES));
    }
}
