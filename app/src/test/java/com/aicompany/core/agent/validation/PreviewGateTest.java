package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PreviewGateTest {

    private static final String MAIN = "void main() { runApp(forjaiPreviewOr(const MyApp())); }";
    private static final String PREVIEW = "class Pant01Preview extends StatelessWidget {\n  const Pant01Preview({super.key});\n"
            + "  Widget build(c) => const PedidosPage();\n}";
    // Prueba en vivo 3: la pantalla real lleva Key('PANT-01'); la vista previa solo la instancia.
    private static final String SCREEN_PATH = "lib/pedidos/presentation/pedidos_page.dart";
    private static final String SCREEN = "class PedidosPage extends StatelessWidget {\n  const PedidosPage({super.key});\n"
            + "  Widget build(c) => Scaffold(key: const Key('PANT-01'));\n}";

    @Test
    void requiresPreviewForIncrementScreen() {
        var errors = PreviewGate.check(List.of(new GeneratedFile("lib/main.dart", MAIN)), Set.of("PANT-01"), Map.of());
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("pant_01_preview.dart") && errors.get(0).contains("Pant01Preview"), errors.toString());

        assertEquals(List.of(), PreviewGate.check(List.of(new GeneratedFile("lib/main.dart", MAIN),
                new GeneratedFile("lib/pedidos/presentation/previews/pant_01_preview.dart", PREVIEW),
                new GeneratedFile(SCREEN_PATH, SCREEN)), Set.of("PANT-01"), Map.of()));
        // Una vista previa que ya está en HEAD también cuenta.
        assertEquals(List.of(), PreviewGate.check(List.of(), Set.of("PANT-01"), Map.of(
                "lib/main.dart", MAIN, "lib/pedidos/presentation/previews/pant_01_preview.dart", PREVIEW, SCREEN_PATH, SCREEN)));
    }

    // Revisión final del diseño visual (C2): cada data-id de la maqueta (salvo el de la pantalla, que pone la vista
    // previa) tiene que aparecer en la app como ForjaiId('<data-id>': una Key sola no llega a la captura.
    @Test
    void theLastWriterMustWrapEveryMockupDataIdWithForjaiId() {
        var ids = Map.of("PANT-01", Set.of("PANT-01", "titulo", "btn-guardar"));
        var page = "import '../../forjai/forjai_id.dart';\nclass PedidosPage extends StatelessWidget { final k = Key('PANT-01'); build(c) => Column(children: ["
                + "ForjaiId('titulo', child: Text('x')), Text('y', key: Key('btn-guardar'))]); }";
        var errors = PreviewGate.check(List.of(new GeneratedFile("lib/main.dart", MAIN),
                new GeneratedFile("lib/pedidos/presentation/previews/pant_01_preview.dart", PREVIEW),
                new GeneratedFile("lib/pedidos/presentation/pedidos_page.dart", page)), Set.of("PANT-01"), Map.of(),
                true, true, ids);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("ForjaiId('btn-guardar'") && errors.get(0).contains("PANT-01")
                && errors.get(0).contains("lib/forjai/forjai_id.dart"), errors.toString());

        // Lo que ya está en HEAD también cuenta; y con todo envuelto no hay errores.
        assertEquals(List.of(), PreviewGate.check(List.of(new GeneratedFile("lib/pedidos/presentation/pedidos_page.dart",
                page.replace("Text('y', key: Key('btn-guardar'))", "ForjaiId(\"btn-guardar\", child: Text('y'))"))),
                Set.of("PANT-01"), Map.of("lib/main.dart", MAIN, "lib/pedidos/presentation/previews/pant_01_preview.dart", PREVIEW),
                true, true, ids));
        // Las escritoras anteriores (R10) no responden por los data-id de toda la pantalla.
        assertEquals(List.of(), PreviewGate.check(List.of(new GeneratedFile("lib/pedidos/presentation/pedidos_page.dart",
                "class PedidosPage {}")), Set.of("PANT-01"), Map.of(), false, false, ids));
    }

    @Test
    void forjaiBarIdSatisfiesAnIdLikeForjaiId() {
        var ids = Map.of("PANT-01", Set.of("PANT-01", "barra", "item-1"));
        var page = "class PedidosPage extends StatelessWidget { final k = Key('PANT-01'); build(c) => Scaffold(appBar: ForjaiBarId('barra', child: AppBar()), body: "
                + "Column(children: [for (var i = 1; i < 3; i++) ForjaiBarId('item-$i', child: x)])); }";
        assertEquals(List.of(), PreviewGate.check(List.of(new GeneratedFile("lib/main.dart", MAIN),
                new GeneratedFile("lib/pedidos/presentation/previews/pant_01_preview.dart", PREVIEW),
                new GeneratedFile("lib/pedidos/presentation/pedidos_page.dart", page)), Set.of("PANT-01"), Map.of(),
                true, true, ids));
    }

    private static List<String> repeated(String body, Set<String> ids) {
        var page = "import '../../forjai/forjai_id.dart';\nclass PedidosPage extends StatelessWidget { final k = Key('PANT-01'); build(c) => " + body + "; }";
        return PreviewGate.check(List.of(new GeneratedFile("lib/main.dart", MAIN),
                new GeneratedFile("lib/pedidos/presentation/previews/pant_01_preview.dart", PREVIEW),
                new GeneratedFile("lib/pedidos/presentation/pedidos_page.dart", page)), Set.of("PANT-01"), Map.of(),
                true, true, Map.of("PANT-01", ids));
    }

    @Test
    void repeatedListIdsAcceptInterpolatedIdsWithTheSameBase() {
        var ids = Set.of("task-item-1", "task-item-2", "task-item-3");
        for (var body : List.of("ForjaiId('task-item-$i', child: x)", "ForjaiId('task-item-${i}', child: x)",
                "ForjaiId(\"task-item-$i\", child: x)", "ForjaiId('task-item-' + i.toString(), child: x)")) {
            assertEquals(List.of(), repeated(body, ids), body);
        }
        assertEquals(List.of(), repeated("ForjaiId('task-item-1', child: x)", Set.of("task-item-1")));
    }

    @Test
    void repeatedListIdsStillFailWithoutTheirBaseAndAreGroupedInTheMessage() {
        var ids = new java.util.LinkedHashSet<>(List.of("task-item-1", "task-item-2", "task-item-3", "task-check-1", "titulo"));
        var errors = repeated("ForjaiId('otro-$i', child: x)", ids);
        assertEquals(1, errors.size(), errors.toString());
        var e = errors.get(0);
        assertTrue(e.contains("ForjaiId('task-item-$i', child: …) dentro del bucle de la lista (cubre task-item-1, task-item-2, task-item-3)"), e);
        assertTrue(e.contains("ForjaiId('task-check-$i', child: …) dentro del bucle de la lista (cubre task-check-1)"), e);
        assertTrue(e.contains("ForjaiId('titulo', child: …)"), e);
        assertFalse(e.contains("ForjaiId('task-item-1'"), e);
    }

    @Test
    void idsWithoutNumericSuffixStillNeedTheLiteral() {
        assertEquals(1, repeated("ForjaiId('titulo-$i', child: x)", Set.of("titulo")).size());
        assertEquals(1, repeated("ForjaiId('btn-$i', child: x)", Set.of("btn-guardar")).size());
    }

    @Test
    void theRootKeyCanAlsoBeAForjaiId() {
        var screen = SCREEN.replace("Scaffold(key: const Key('PANT-01'))", "ForjaiId('PANT-01', child: Scaffold())");
        assertEquals(List.of(), PreviewGate.check(List.of(new GeneratedFile("lib/main.dart", MAIN),
                new GeneratedFile("lib/pedidos/presentation/previews/pant_01_preview.dart", PREVIEW),
                new GeneratedFile(SCREEN_PATH, screen)), Set.of("PANT-01"), Map.of()));
    }

    @Test
    void rejectsPreviewWithoutClassOrRealScreen() {
        var errors = PreviewGate.check(List.of(new GeneratedFile("lib/pedidos/presentation/previews/pant_01_preview.dart",
                "class Otra {}")), Set.of("PANT-01"), Map.of("lib/main.dart", MAIN));
        assertTrue(errors.stream().anyMatch(e -> e.contains("class Pant01Preview")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("pantalla real de PANT-01") && e.contains("Key('PANT-01')")),
                errors.toString());
    }

    // Prueba en vivo 3: Mila escribió la vista previa como copia de la pantalla (Scaffold propio, Key y ForjaiId) y
    // corregía la copia; la pantalla real que usa el cliente seguía distinta a la maqueta.
    @Test
    void previewThatCopiesTheScreenIsRejected() {
        var copy = "class Pant01Preview extends StatelessWidget {\n  const Pant01Preview({super.key});\n"
                + "  Widget build(c) => Scaffold(key: const Key('PANT-01'), body: ForjaiId('titulo', child: Text('x')));\n}";
        var errors = PreviewGate.check(List.of(new GeneratedFile("lib/main.dart", MAIN),
                new GeneratedFile("lib/pedidos/presentation/previews/pant_01_preview.dart", copy),
                new GeneratedFile(SCREEN_PATH, SCREEN)), Set.of("PANT-01"), Map.of(), true, true,
                Map.of("PANT-01", Set.of("PANT-01", "titulo")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("no puede armar su propio Scaffold")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("instancia PedidosPage(…)")), errors.toString());
        // El ForjaiId que solo está en la vista previa no cuenta: la captura debe ver la pantalla real.
        assertTrue(errors.stream().anyMatch(e -> e.contains("ForjaiId('titulo', child: …)")), errors.toString());
    }

    @Test
    void aStatefulScreenWithTheKeyInItsStateIsTheRealScreen() {
        var screen = "class Pant02Tareas extends StatefulWidget { State createState() => _S(); }\n"
                + "class _S extends State<Pant02Tareas> { build(c) => Scaffold(key: const Key('PANT-01')); }";
        var preview = PREVIEW.replace("const PedidosPage()", "Pant02Tareas(listar: _Ejemplo())");
        assertEquals(List.of(), PreviewGate.check(List.of(new GeneratedFile("lib/main.dart", MAIN),
                new GeneratedFile("lib/pedidos/presentation/previews/pant_01_preview.dart", preview),
                new GeneratedFile("lib/pedidos/presentation/pant_02_tareas.dart", screen)), Set.of("PANT-01"), Map.of()));
    }

    @Test
    void doesNotRequirePreviewForScreenOutsideIncrement() {
        assertEquals(List.of(), PreviewGate.check(List.of(new GeneratedFile("lib/main.dart", MAIN),
                new GeneratedFile("lib/pedidos/presentation/previews/pant_01_preview.dart", PREVIEW),
                new GeneratedFile(SCREEN_PATH, SCREEN)), Set.of("PANT-01"), Map.of()));
    }

    @Test
    void requiresMainToCallForjaiPreviewOr() {
        var errors = PreviewGate.check(List.of(new GeneratedFile("lib/main.dart", "void main() { runApp(const MyApp()); }")),
                Set.of(), Map.of());
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("forjaiPreviewOr("), errors.toString());
    }

    @Test
    void mainCheckIsSkippedForATaskThatDoesNotOwnMain() {
        var old = Map.of("lib/main.dart", "void main() { runApp(const MyApp()); }");
        assertEquals(List.of(), PreviewGate.check(List.of(), Set.of(), old, false));
        assertEquals(1, PreviewGate.check(List.of(), Set.of(), old, true).size());
    }

    @Test
    void earlierWriterIsOnlyCheckedOnThePreviewsItReturns() {
        // No devuelve vistas previas: sin error de completitud aunque PANT-01 y PANT-02 falten.
        assertEquals(List.of(), PreviewGate.check(List.of(), Set.of("PANT-01", "PANT-02"), Map.of(), false, false));
        // Las que devuelve sí se validan.
        var errors = PreviewGate.check(List.of(new GeneratedFile("lib/pedidos/presentation/previews/pant_02_preview.dart", "class X {}")),
                Set.of("PANT-01", "PANT-02"), Map.of(), false, false);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("Pant02Preview"), errors.toString());
        assertEquals(List.of(), PreviewGate.check(List.of(new GeneratedFile(
                "lib/pedidos/presentation/previews/pant_01_preview.dart", PREVIEW)), Set.of("PANT-01", "PANT-02"), Map.of(), false, false));
    }

    @Test
    void lastWriterMustCoverEveryScreen() {
        var errors = PreviewGate.check(List.of(new GeneratedFile("lib/clientes/presentation/previews/pant_01_preview.dart", PREVIEW),
                new GeneratedFile(SCREEN_PATH, SCREEN)), Set.of("PANT-01", "PANT-02"), Map.of(), false, true);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("PANT-02") && errors.get(0).contains("en su vista previa"), errors.toString());
    }
}
