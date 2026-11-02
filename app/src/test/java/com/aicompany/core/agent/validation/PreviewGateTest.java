package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewGateTest {

    private static final String MAIN = "void main() { runApp(forjaiPreviewOr(const MyApp())); }";
    private static final String PREVIEW = "class Pant01Preview extends StatelessWidget {\n  const Pant01Preview({super.key});\n"
            + "  Widget build(c) => Scaffold(key: const Key('PANT-01'));\n}";

    @Test
    void requiresPreviewForIncrementScreen() {
        var errors = PreviewGate.check(List.of(new GeneratedFile("lib/main.dart", MAIN)), Set.of("PANT-01"), Map.of());
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("pant_01_preview.dart") && errors.get(0).contains("Pant01Preview"), errors.toString());

        assertEquals(List.of(), PreviewGate.check(List.of(new GeneratedFile("lib/main.dart", MAIN),
                new GeneratedFile("lib/pedidos/presentation/previews/pant_01_preview.dart", PREVIEW)), Set.of("PANT-01"), Map.of()));
        // Una vista previa que ya está en HEAD también cuenta.
        assertEquals(List.of(), PreviewGate.check(List.of(), Set.of("PANT-01"), Map.of(
                "lib/main.dart", MAIN, "lib/pedidos/presentation/previews/pant_01_preview.dart", PREVIEW)));
    }

    @Test
    void rejectsPreviewWithoutClassOrRootKey() {
        var errors = PreviewGate.check(List.of(new GeneratedFile("lib/pedidos/presentation/previews/pant_01_preview.dart",
                "class Otra {}")), Set.of("PANT-01"), Map.of("lib/main.dart", MAIN));
        assertTrue(errors.stream().anyMatch(e -> e.contains("class Pant01Preview")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Key('PANT-01')")), errors.toString());
    }

    @Test
    void doesNotRequirePreviewForScreenOutsideIncrement() {
        assertEquals(List.of(), PreviewGate.check(List.of(new GeneratedFile("lib/main.dart", MAIN),
                new GeneratedFile("lib/pedidos/presentation/previews/pant_01_preview.dart", PREVIEW)),
                Set.of("PANT-01"), Map.of()));
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
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Pant02Preview")) && errors.stream().anyMatch(e -> e.contains("Key('PANT-02')")));
        assertEquals(List.of(), PreviewGate.check(List.of(new GeneratedFile(
                "lib/pedidos/presentation/previews/pant_01_preview.dart", PREVIEW)), Set.of("PANT-01", "PANT-02"), Map.of(), false, false));
    }

    @Test
    void lastWriterMustCoverEveryScreen() {
        var errors = PreviewGate.check(List.of(new GeneratedFile("lib/clientes/presentation/previews/pant_01_preview.dart", PREVIEW)),
                Set.of("PANT-01", "PANT-02"), Map.of(), false, true);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("PANT-02") && errors.get(0).contains("en su vista previa"), errors.toString());
    }
}
