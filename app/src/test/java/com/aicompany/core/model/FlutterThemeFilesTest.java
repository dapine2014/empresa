package com.aicompany.core.model;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.agent.model.Mockups;
import com.aicompany.core.agent.model.VisualDesign;
import com.aicompany.core.agent.model.VisualSystem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlutterThemeFilesTest {

    private static VisualDesign design(String primary) {
        var colors = List.of(new VisualSystem.ColorToken("primary", primary), new VisualSystem.ColorToken("onPrimary", "#FFFFFF"),
                new VisualSystem.ColorToken("surface", "#FAFAFA"), new VisualSystem.ColorToken("onSurface", "#111111"),
                new VisualSystem.ColorToken("error", "#B00020"), new VisualSystem.ColorToken("onError", "#FFFFFF"));
        var vs = new VisualSystem(colors, new VisualSystem.Typography("Roboto",
                Map.of("display", 32.0, "title", 20.0, "body", 14.0, "label", 12.0)),
                Map.of("md", 16.0), Map.of("md", 8.0), List.of());
        return new VisualDesign(vs, new Mockups(List.of(), List.of(new Mockups.SvgAsset("logo", "<svg/>"))));
    }

    @Test
    void themeHasEveryToken() {
        var theme = FlutterThemeFiles.files(design("#2F6FED"), List.of()).get("lib/theme/app_theme.dart");
        assertTrue(theme.contains("static const Color primary = Color(0xFF2F6FED);"));
        assertTrue(theme.contains("static const Color onError = Color(0xFFFFFFFF);"));
        assertTrue(theme.contains("static const double spaceMd = 16.0;"));
        assertTrue(theme.contains("static const double radiusMd = 8.0;"));
        for (var size : List.of("display", "title", "body", "label")) {
            assertTrue(theme.contains("static const TextStyle " + size + " = TextStyle("), size);
        }
        assertTrue(theme.contains("static ThemeData get theme"));
        assertTrue(theme.contains("fontFamily = 'Roboto'"));
    }

    // Revisión final del diseño visual (C2): la captura lee flt-semantics-identifier, que solo emite
    // Semantics(identifier:); Forjai da el envoltorio ForjaiId (Semantics + Key) y la vista previa enciende la semántica.
    @Test
    void theForjaiIdHelperEmitsTheSemanticsIdentifierAndTheKey() {
        var files = FlutterThemeFiles.files(design("#2F6FED"), List.of());
        var helper = files.get(FlutterThemeFiles.FORJAI_ID_PATH);
        assertEquals("lib/forjai/forjai_id.dart", FlutterThemeFiles.FORJAI_ID_PATH);
        assertNotNull(helper, files.keySet().toString());
        assertTrue(helper.contains("class ForjaiId extends StatelessWidget"), helper);
        assertTrue(helper.contains("Semantics(identifier: id, container: true, child: KeyedSubtree(key: Key(id), child: child))"),
                helper);
        assertTrue(files.get(FlutterThemeFiles.PREVIEW_PATH).contains("SemanticsBinding.instance.ensureSemantics();"));
    }

    @Test
    void theForjaiBarIdHelperIsAPreferredSizeWidgetForTheAppBarSlot() {
        var helper = FlutterThemeFiles.files(design("#2F6FED"), List.of()).get(FlutterThemeFiles.FORJAI_ID_PATH);
        assertTrue(helper.contains("class ForjaiBarId extends StatelessWidget implements PreferredSizeWidget"), helper);
        assertTrue(helper.contains("const ForjaiBarId(this.id, {super.key, required this.child});"), helper);
        assertTrue(helper.contains("final PreferredSizeWidget child;"), helper);
        assertTrue(helper.contains("Size get preferredSize => child.preferredSize;"), helper);
        assertTrue(helper.contains("import 'package:flutter/material.dart';"), helper);
    }

    @Test
    void previewRegistryImportsEachScreen() {
        var files = FlutterThemeFiles.files(design("#2F6FED"),
                FlutterThemeFiles.previewPaths(List.of("lib/pedidos/presentation/previews/pant_02_preview.dart",
                        "lib/pedidos/presentation/previews/pant_01_preview.dart", "lib/pedidos/domain/pedido.dart")));
        var previews = files.get("lib/forjai/previews.dart");
        assertTrue(previews.contains("import '../pedidos/presentation/previews/pant_01_preview.dart';"));
        assertTrue(previews.contains("'PANT-02': () => const Pant02Preview(),"));
        assertFalse(previews.contains("pedido.dart"));
        var host = files.get("lib/forjai/preview.dart");
        assertTrue(host.contains("Widget forjaiPreviewOr(Widget app)"));
        assertTrue(host.contains("Uri.base.queryParameters['preview']"));
        assertTrue(host.contains("ensureSemantics()") && host.contains("Semantics(identifier: id"));
        assertEquals("<svg/>", files.get("assets/svg/logo.svg"));
    }

    @Test
    void pubspecDeclaresFontsAndSvgs() {
        var pubspec = "name: pedidos\ndependencies:\n  flutter:\n    sdk: flutter\n  http: 1.2.2\n\nflutter:\n  uses-material-design: true\n";
        var merged = FlutterThemeFiles.mergePubspec(pubspec, design("#2F6FED"));
        assertTrue(merged.contains("  flutter_svg: " + FlutterThemeFiles.FLUTTER_SVG_VERSION));
        assertTrue(merged.contains("        - asset: assets/fonts/Roboto-Regular.ttf"));
        assertTrue(merged.contains("        - asset: assets/fonts/Roboto-Bold.ttf\n          weight: 700"));
        assertTrue(merged.contains("  assets:\n    - assets/svg/"));
        assertTrue(merged.contains("  uses-material-design: true"));
        assertEquals(merged, FlutterThemeFiles.mergePubspec(merged, design("#2F6FED")), "idempotente");
        var bare = FlutterThemeFiles.mergePubspec("name: x\n", design("#2F6FED"));
        assertTrue(bare.contains("\nflutter:\n") && bare.contains("  assets:\n    - assets/svg/") && bare.contains("  fonts:\n"), bare);
    }

    @Test
    void regeneratesThemeWhenVisualVersionChanges() {
        var v1 = FlutterThemeFiles.files(design("#2F6FED"), List.of()).get("lib/theme/app_theme.dart");
        var v2 = FlutterThemeFiles.files(design("#00AA55"), List.of()).get("lib/theme/app_theme.dart");
        assertNotEquals(v1, v2);
        assertTrue(v2.contains("Color(0xFF00AA55)"));
    }

    @Test
    void reservesForjaiPaths() {
        assertEquals(2, FlutterThemeFiles.reservedErrors(List.of(new GeneratedFile("lib/theme/x.dart", "x"),
                new GeneratedFile("app/lib/forjai/preview.dart", "x"), new GeneratedFile("lib/pedidos/a.dart", "x"))).size());
    }
}
