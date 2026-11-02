package com.aicompany.core.service;

import com.aicompany.core.agent.model.Mockups;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.agent.model.VisualDesign;
import com.aicompany.core.model.Box;
import com.aicompany.core.model.Captures;
import com.aicompany.core.model.DesignScreenReview;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.SandboxResult;
import com.aicompany.core.model.StaticCheck;
import com.aicompany.core.model.StaticValidationStatus;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamMissionContext;
import com.aicompany.core.model.TeamSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec 2026-11-01 front con diseño visual §13-14: el ciclo de corrección de diseño (2 rondas y freno al decisor). */
class DesignReviewLoopTest {

    private static final String HEAD = "a".repeat(40);
    private static final String PREVIEW_1 = "lib/pedidos/presentation/previews/pant_01_preview.dart";
    private static final String PAGE_1 = "lib/pedidos/presentation/pages/login_page.dart";
    private static final String PREVIEW_2 = "lib/pedidos/presentation/previews/pant_02_preview.dart";
    private static final String PAGE_2 = "lib/pedidos/presentation/pages/catalogo_page.dart";
    private static final String THEME = "lib/theme/app_theme.dart";

    @TempDir
    Path dir;

    private final DesignCaptureService captures = mock(DesignCaptureService.class);
    private final DesignReviewMemoryService reviews = mock(DesignReviewMemoryService.class);
    private final VisualDesignMemoryService visuals = mock(VisualDesignMemoryService.class);
    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final DevelopmentWorkspaceService workspace = mock(DevelopmentWorkspaceService.class);
    private final JsonMapper json = JsonMapper.builder().build();

    /** El front de prueba: cuenta las llamadas y guarda cada prompt y sus rutas requeridas. */
    private class FakeFront implements DesignReviewLoop.Front {
        Set<String> screens = Set.of("PANT-01");
        final List<String> prompts = new ArrayList<>();
        final List<List<String>> required = new ArrayList<>();
        final List<String> commitIds = new ArrayList<>();
        int rebuilds;

        @Override
        public Set<String> screens(TeamMissionContext ctx) {
            return screens;
        }

        @Override
        public String correct(TeamMissionContext ctx, PlannedTask owner, String prompt, List<String> requiredPaths,
                              String headSha, String commitId) {
            prompts.add(prompt);
            required.add(requiredPaths);
            commitIds.add(commitId);
            return String.valueOf(prompts.size()).repeat(40);
        }

        @Override
        public boolean rebuild(TeamMissionContext ctx, String headSha) {
            rebuilds++;
            return true;
        }
    }

    private final FakeFront front = new FakeFront();
    private Path mockupPng;
    private Path goodPng;
    private Path badPng;
    private final List<Box> boxes = List.of(new Box("btn-ingresar", 16, 520, 358, 48, "#1F4E79"));

    private DesignReviewLoop loop() {
        return new DesignReviewLoop(captures, reviews, visuals, policies, workspace, json, front);
    }

    private static TeamMissionContext context() {
        var team = new TeamSnapshot("TEAM-DEVELOPMENT", "Engineering Team", "ACTIVE", "engineering", List.of(
                new TeamMemberInfo("frontend-ui", "Mila", "UI", "R", List.of("UI"), "m"),
                new TeamMemberInfo("qa", "Vera", "QA", "R", List.of("QA"), "m")));
        var plan = new TeamPlan("App", null, null, List.of(
                new PlannedTask("frontend-ui", "WORK", "UI", "Pantallas", List.of("UI"),
                        List.of("lib/pedidos/presentation", "lib/main.dart")),
                new PlannedTask("qa", "VALIDATION", "STATIC_REVIEW", "Revisar", List.of("QA"), List.of())),
                List.of(), "FLUTTER_WEB_APP", List.of(new TeamPlan.BoundedContext("Pedidos", "Pedidos")), List.of());
        return new TeamMissionContext("M-1", "app", team, plan);
    }

    private static BufferedImage pattern(boolean inverted) {
        var img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                var on = ((x / 8) + (y / 8)) % 2 == 0;
                img.setRGB(x, y, on != inverted ? 0xFFFFFF : 0x1F4E79);
            }
        }
        return img;
    }

    private Path png(String name, BufferedImage img) throws Exception {
        var path = dir.resolve(name);
        ImageIO.write(img, "png", path.toFile());
        return path;
    }

    private Captures capture(Set<String> screens, Path png) {
        var pngs = new LinkedHashMap<String, Path>();
        var bx = new LinkedHashMap<String, List<Box>>();
        for (var s : screens) {
            for (var size : Captures.SIZES) {
                pngs.put(s + "-" + size, png);
                bx.put(s + "-" + size, boxes);
            }
        }
        return new Captures(pngs, bx);
    }

    @BeforeEach
    void setUp() throws Exception {
        mockupPng = png("mockup.png", pattern(false));
        goodPng = png("good.png", pattern(false));
        badPng = png("bad.png", pattern(true));
        var design = new VisualDesign(VisualDesignWriterTest.system("#FFFFFF"), new Mockups(List.of(
                new Mockups.Mockup("PANT-01", "<div data-id=\"btn-ingresar\">x</div>"),
                new Mockups.Mockup("PANT-02", "<div data-id=\"btn-ingresar\">x</div>")), List.of()));
        when(visuals.approved("M-1")).thenReturn(Optional.of(new VisualDesignMemoryService.Version(1,
                json.writeValueAsString(design), "APPROVED", 1, 1, "")));
        when(captures.mockupCaptures("M-1", 1)).thenReturn(Optional.of(capture(Set.of("PANT-01", "PANT-02"), mockupPng)));
        when(policies.activeValue(PolicyKey.DESIGN_SIMILARITY_THRESHOLD)).thenReturn(0.90);
        when(reviews.all("M-1")).thenReturn(List.of());
        when(workspace.filesAtCommit(eq("M-1"), anyString())).thenReturn(List.of(PREVIEW_1, PAGE_1, PREVIEW_2, PAGE_2,
                THEME, "lib/pedidos/domain/pedido.dart", "lib/main.dart"));
        var contents = new HashMap<String, String>(Map.of(
                PREVIEW_1, "import 'package:flutter/material.dart';\nimport '../pages/login_page.dart';\n"
                        + "class Pant01Preview extends StatelessWidget { /* PREVIEW-UNO */ }\n",
                PAGE_1, "class LoginPage { /* PAGINA-LOGIN */ }\n",
                PREVIEW_2, "import '../pages/catalogo_page.dart';\nclass Pant02Preview { /* PREVIEW-DOS */ }\n",
                PAGE_2, "class CatalogoPage { /* PAGINA-CATALOGO */ }\n",
                THEME, "class AppTheme { static const Color primary = Color(0xFF1F4E79); /* TEMA */ }\n",
                "lib/pedidos/domain/pedido.dart", "class Pedido { /* DOMINIO */ }\n",
                "lib/main.dart", "void main() { /* MAIN */ }\n"));
        when(workspace.readFileAtCommit(eq("M-1"), anyString(), anyString()))
                .thenAnswer(inv -> contents.get(inv.getArgument(2, String.class)));
    }

    @Test
    void passingScreensVerifyWithoutModelCall() {
        when(captures.captureApp(eq("M-1"), eq(HEAD), eq(1), any())).thenReturn(capture(Set.of("PANT-01"), goodPng));

        var outcome = loop().run(context(), HEAD, 1);

        assertTrue(outcome.accepted());
        assertEquals(List.of(), outcome.awaiting());
        assertEquals(HEAD, outcome.headSha());
        assertEquals(0, front.prompts.size());
        verify(reviews).save(eq("M-1"), argThat(r -> r.screen().equals("PANT-01") && r.passed()), eq(1), eq(0),
                eq(DesignScreenReview.PASSED));
    }

    @Test
    void failingScreenGetsTwoRoundsThenAwaitsDecision() {
        when(captures.captureApp(eq("M-1"), anyString(), eq(1), any())).thenReturn(capture(Set.of("PANT-01"), badPng));

        var outcome = loop().run(context(), HEAD, 1);

        assertEquals(2, front.prompts.size());
        for (var prompt : front.prompts) {
            assertTrue(prompt.length() < 8000, "prompt de " + prompt.length() + " caracteres");
            assertTrue(prompt.contains("CORRECCIÓN DE DISEÑO"), prompt);
        }
        assertEquals(List.of("M-1-DESIGN-R1", "M-1-DESIGN-R2"), front.commitIds);
        assertEquals(2, front.rebuilds);
        assertFalse(outcome.accepted());
        assertEquals(List.of("PANT-01"), outcome.awaiting());
        assertEquals("2".repeat(40), outcome.headSha());
        verify(reviews).save(eq("M-1"), argThat(r -> r.screen().equals("PANT-01")), eq(1), eq(2),
                eq(DesignScreenReview.AWAITING_DECISION));
        verify(captures, times(3)).captureApp(eq("M-1"), anyString(), eq(1), eq(Set.of("PANT-01")));
    }

    @Test
    void correctionPromptContainsOnlyThatScreenFile() {
        front.screens = new java.util.LinkedHashSet<>(List.of("PANT-01", "PANT-02"));
        // PANT-01 falla y PANT-02 coincide: la corrección lleva solo los archivos de PANT-01 y el tema.
        var pngs = new LinkedHashMap<String, Path>();
        var bx = new LinkedHashMap<String, List<Box>>();
        for (var size : Captures.SIZES) {
            pngs.put("PANT-01-" + size, badPng);
            pngs.put("PANT-02-" + size, goodPng);
            bx.put("PANT-01-" + size, boxes);
            bx.put("PANT-02-" + size, boxes);
        }
        when(captures.captureApp(eq("M-1"), anyString(), eq(1), any())).thenReturn(new Captures(pngs, bx));

        loop().run(context(), HEAD, 1);

        var prompt = front.prompts.get(0);
        assertTrue(prompt.contains("PREVIEW-UNO"), prompt);
        assertTrue(prompt.contains("PAGINA-LOGIN"), prompt);
        assertTrue(prompt.contains("TEMA"), prompt);
        assertFalse(prompt.contains("PREVIEW-DOS"), prompt);
        assertFalse(prompt.contains("PAGINA-CATALOGO"), prompt);
        assertFalse(prompt.contains("DOMINIO"), prompt);
        assertFalse(prompt.contains("MAIN"), prompt);
        assertFalse(prompt.contains("Pantalla PANT-02"), prompt);
        assertEquals(List.of(PREVIEW_1, PAGE_1), front.required.get(0));
    }

    @Test
    void designMismatchBlocksNotFails() {
        when(captures.captureApp(eq("M-1"), anyString(), eq(1), any())).thenReturn(capture(Set.of("PANT-01"), badPng));

        var outcome = loop().run(context(), HEAD, 1);

        assertFalse(outcome.accepted());
        assertTrue(DesignReviewLoop.blockReason(outcome).startsWith("DESIGN_MISMATCH: PANT-01"),
                DesignReviewLoop.blockReason(outcome));
        var checks = List.of(StaticCheck.pass("COMMIT_EXISTS", "ok", HEAD, List.of()));
        var sandbox = new SandboxResult("PASS", List.of(new SandboxResult.StepResult("test", "PASS", 0, 1, "", 3, 0)));
        // El código y los tests están en verde: no es FAILED (no entra a la corrección automática), pero tampoco VERIFIED.
        assertEquals(StaticValidationStatus.UNVALIDATED,
                StaticValidationStatus.compute(checks, null, sandbox, true, outcome.accepted()));
        assertNull(DesignReviewLoop.blockReason(new DesignReviewLoop.Outcome(true, List.of(), HEAD)));
    }

    @Test
    void noVisualDesignSkipsLoop() {
        when(visuals.approved("M-1")).thenReturn(Optional.empty());

        var outcome = loop().run(context(), HEAD, 1);

        assertTrue(outcome.accepted());
        assertEquals(HEAD, outcome.headSha());
        assertEquals(0, front.prompts.size());
        verifyNoInteractions(captures, reviews);
    }

    // Revisión R-reinicios: lo decidido y las rondas ya gastadas sobreviven (quedan en Neo4j).
    @Test
    void persistedRoundsAndAcceptedScreensAreRespected() {
        front.screens = new java.util.LinkedHashSet<>(List.of("PANT-01", "PANT-02"));
        when(reviews.all("M-1")).thenReturn(List.of(
                new DesignScreenReview(1, "PANT-01", "phone", 0.5, DesignScreenReview.PENDING, 2, 1, "founder",
                        "el botón más abajo", null),
                new DesignScreenReview(1, "PANT-02", "phone", 0.5, DesignScreenReview.ACCEPTED, 2, 0, "founder",
                        "así está bien", null)));
        when(captures.captureApp(eq("M-1"), anyString(), eq(1), any())).thenReturn(capture(Set.of("PANT-01", "PANT-02"), badPng));

        var outcome = loop().run(context(), HEAD, 1);

        // 2 rondas ya gastadas + 1 extra del decisor = una sola llamada más, con su comentario.
        assertEquals(1, front.prompts.size());
        assertTrue(front.prompts.get(0).contains("el botón más abajo"), front.prompts.get(0));
        assertEquals(List.of("M-1-DESIGN-R5"), front.commitIds);
        assertEquals(List.of("PANT-01"), outcome.awaiting());
        verify(reviews, never()).save(eq("M-1"), argThat(r -> r.screen().equals("PANT-02")), anyInt(), anyInt(), anyString());
    }

    @Test
    void aCorrectionThatBreaksTheBuildIsDropped() throws Exception {
        when(captures.captureApp(eq("M-1"), anyString(), eq(1), any())).thenReturn(capture(Set.of("PANT-01"), badPng));
        var breaking = new FakeFront() {
            @Override
            public boolean rebuild(TeamMissionContext ctx, String headSha) {
                rebuilds++;
                return false;
            }
        };

        var outcome = new DesignReviewLoop(captures, reviews, visuals, policies, workspace, json, breaking)
                .run(context(), HEAD, 1);

        assertEquals(2, breaking.prompts.size());
        verify(workspace, times(2)).dropHeadCommit("M-1");
        assertEquals(HEAD, outcome.headSha());
        assertFalse(outcome.accepted());
    }
}
