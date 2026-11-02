package com.aicompany.core.service;

import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.agent.model.VisualDesign;
import com.aicompany.core.agent.validation.DesignComparison;
import com.aicompany.core.agent.validation.DesignComparison.ScreenResult;
import com.aicompany.core.agent.validation.OwnedPaths;
import com.aicompany.core.model.Box;
import com.aicompany.core.model.Captures;
import com.aicompany.core.model.DesignScreenReview;
import com.aicompany.core.model.FlutterThemeFiles;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.TeamMissionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Spec 2026-11-01 front con diseño visual §11-14: después de que el sandbox dio verde, Java captura las vistas previas de
 * la app, compara cada pantalla con su maqueta aprobada y le da al front dueño hasta {@code 2 + extraRounds} rondas
 * baratas por pantalla (solo el archivo de esa pantalla, {@code app_theme.dart} y la corrección de Java). Si sigue sin
 * alcanzar el umbral, la pantalla queda {@code AWAITING_DECISION} y el incremento no puede quedar VERIFIED.
 *
 * <p>«El archivo de la pantalla» (regla determinista): la vista previa {@code lib/<ctx>/presentation/previews/pant_NN_preview.dart}
 * más los {@code .dart} de {@code presentation/} que ella importa directamente (relativos o {@code package:}) y que son
 * del mismo dueño. Se incluyen en ese orden mientras el prompt no pase de {@link #PROMPT_LIMIT} caracteres; la vista
 * previa va siempre. Solo esos archivos se le exigen de vuelta.
 *
 * <p>Una corrección que rompe el build o los tests (la pasada VERIFY del sandbox) se descarta: sus commits salen de HEAD
 * y la ronda queda gastada. No es la corrección automática: no hay FAILED ni CorrectionRound.
 */
public class DesignReviewLoop {

    private static final Logger log = LoggerFactory.getLogger(DesignReviewLoop.class);

    /** Tope del prompt de una corrección (el brief exige menos de 8.000 caracteres). */
    static final int PROMPT_LIMIT = 7_500;
    static final int THEME_BUDGET = 2_000;
    static final int CORRECTION_BUDGET = 2_500;
    static final String BLOCK_PREFIX = "DESIGN_MISMATCH: ";

    private static final Pattern PREVIEW = Pattern.compile("(?:(.*)/)?lib/[^/].*/presentation/previews/pant_(\\d+)_preview\\.dart");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+['\"]([^'\"]+)['\"]");

    /** {@code note}: texto corto para el estado verificable (vacío si no hay nada que decir). */
    public record Outcome(boolean accepted, List<String> awaiting, String headSha, String note) {
        public Outcome {
            awaiting = awaiting == null ? List.of() : List.copyOf(awaiting);
            note = note == null ? "" : note;
        }

        public Outcome(boolean accepted, List<String> awaiting, String headSha) {
            this(accepted, awaiting, headSha, "");
        }
    }

    /** Lo que pone la estrategia de desarrollo: pantallas del incremento, la llamada al front con sus gates y el sandbox. */
    public interface Front {
        /** Pantallas (PANT-NN) del incremento en construcción. */
        Set<String> screens(TeamMissionContext ctx);

        /**
         * Llama al dueño con el mismo contrato DevelopmentResult y los mismos gates de siempre, commitea con
         * {@code commitId} como tarea y devuelve el SHA nuevo (null si no hubo commit).
         */
        String correct(TeamMissionContext ctx, PlannedTask owner, String prompt, List<String> requiredPaths,
                       String headSha, String commitId) throws Exception;

        /** Build y tests del sandbox (VERIFY) sobre ese commit; true si pasó. */
        boolean rebuild(TeamMissionContext ctx, String headSha);
    }

    private final DesignCaptureService captures;
    private final DesignReviewMemoryService reviews;
    private final VisualDesignMemoryService visuals;
    private final CompanyPolicyService policies;
    private final DevelopmentWorkspaceService workspace;
    private final JsonMapper json;
    private final Front front;

    public DesignReviewLoop(DesignCaptureService captures, DesignReviewMemoryService reviews,
                            VisualDesignMemoryService visuals, CompanyPolicyService policies,
                            DevelopmentWorkspaceService workspace, JsonMapper json, Front front) {
        this.captures = captures;
        this.reviews = reviews;
        this.visuals = visuals;
        this.policies = policies;
        this.workspace = workspace;
        this.json = json;
        this.front = front;
    }

    /** El motivo del freno ({@code DESIGN_MISMATCH: PANT-01, PANT-03}); null si el diseño quedó aceptado. */
    public static String blockReason(Outcome outcome) {
        return outcome == null || outcome.accepted() ? null : BLOCK_PREFIX + String.join(", ", outcome.awaiting());
    }

    public Outcome run(TeamMissionContext ctx, String headSha, int increment) {
        var missionId = ctx.missionId();
        if (ctx.plan().resolvedComponents().stream().noneMatch(c -> c.profile() == StackProfile.FLUTTER_WEB_APP)) {
            return new Outcome(true, List.of(), headSha);
        }
        var approved = visuals.approved(missionId);
        if (approved.isEmpty()) {
            return new Outcome(true, List.of(), headSha);
        }
        VisualDesign design;
        try {
            design = json.readValue(approved.get().designJson(), VisualDesign.class);
        } catch (RuntimeException ex) {
            // Igual que los gates del front: un diseño ilegible cuenta como sin diseño.
            log.warn("MISSION {} - could not read the approved visual design: {}", missionId, ex.getMessage());
            return new Outcome(true, List.of(), headSha);
        }
        var screens = new LinkedHashSet<>(front.screens(ctx));
        if (screens.isEmpty()) {
            return new Outcome(true, List.of(), headSha, "sin pantallas en el incremento.");
        }
        var threshold = policies.activeValue(PolicyKey.DESIGN_SIMILARITY_THRESHOLD);
        var all = reviews.all(missionId);
        var state = new LinkedHashMap<String, DesignScreenReview>();
        all.stream().filter(r -> r.increment() == increment).forEach(r -> state.put(r.screen(), r));
        var corrections = all.stream().mapToInt(DesignScreenReview::rounds).sum();

        var head = headSha;
        var error = "";
        try {
            var mockups = mockups(missionId, headSha, increment, approved.get().version());
            while (true) {
                var app = captures.captureApp(missionId, head, increment, screens);
                var files = workspace.filesAtCommit(missionId, head);
                var results = new LinkedHashMap<String, List<ScreenResult>>();
                var toCorrect = new ArrayList<String>();
                for (var screen : screens) {
                    var prev = state.get(screen);
                    if (prev != null && DesignScreenReview.ACCEPTED.equals(prev.status())) {
                        continue;
                    }
                    var compared = compare(screen, mockups, app, design, threshold);
                    results.put(screen, compared);
                    var rounds = prev == null ? 0 : prev.rounds();
                    var limit = prev == null ? DesignScreenReview.MAX_ROUNDS : prev.roundLimit();
                    String status;
                    if (!compared.isEmpty() && compared.stream().allMatch(ScreenResult::passed)) {
                        status = DesignScreenReview.PASSED;
                    } else if (!compared.isEmpty() && rounds < limit && owner(ctx, preview(files, screen)) != null) {
                        status = DesignScreenReview.PENDING;
                        toCorrect.add(screen);
                    } else {
                        status = DesignScreenReview.AWAITING_DECISION;
                    }
                    var worst = worst(screen, compared);
                    reviews.save(missionId, worst, increment, rounds, status);
                    state.put(screen, with(prev, increment, worst, status, rounds));
                }
                if (toCorrect.isEmpty()) {
                    break;
                }
                var before = head;
                var commits = 0;
                for (var screen : toCorrect) {
                    var prev = state.get(screen);
                    var preview = preview(files, screen);
                    var owner = owner(ctx, preview);
                    var rounds = prev.rounds() + 1;
                    corrections++;
                    // La ronda se gasta antes de la llamada: un reinicio a mitad no regala rondas.
                    var worst = worst(screen, results.get(screen));
                    reviews.save(missionId, worst, increment, rounds, DesignScreenReview.PENDING);
                    state.put(screen, with(prev, increment, worst, DesignScreenReview.PENDING, rounds));
                    var failed = results.get(screen).stream().filter(r -> !r.passed()).toList();
                    var screenFiles = screenFiles(missionId, head, files, preview, owner);
                    var prompt = prompt(screen, failed, prev, rounds, screenFiles,
                            themeOf(missionId, head, files, preview));
                    try {
                        var sha = front.correct(ctx, owner, prompt.text(), prompt.required(), head,
                                missionId + "-DESIGN-R" + corrections);
                        if (sha != null) {
                            head = sha;
                            commits++;
                        }
                    } catch (Exception ex) {
                        rethrowCancellation(ex);
                        log.warn("MISSION {} - design correction of {} failed: {}", missionId, screen, ex.getMessage());
                    }
                }
                if (commits > 0 && !front.rebuild(ctx, head)) {
                    log.warn("MISSION {} - design correction broke the build/tests; dropping {} commit(s)", missionId, commits);
                    for (int i = 0; i < commits; i++) {
                        workspace.dropHeadCommit(missionId);
                    }
                    head = before;
                }
            }
        } catch (Exception ex) {
            rethrowCancellation(ex);
            // Sin captura no hay comparación: lo no resuelto espera al decisor (pedir otra ronda vuelve a capturar).
            error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            log.warn("MISSION {} - design review could not complete: {}", missionId, error);
            for (var screen : screens) {
                var prev = state.get(screen);
                if (prev != null && (DesignScreenReview.ACCEPTED.equals(prev.status())
                        || DesignScreenReview.AWAITING_DECISION.equals(prev.status()))) {
                    continue;
                }
                var none = new ScreenResult(screen, prev == null ? "" : prev.size(), prev == null ? 0.0 : prev.ssim(),
                        List.of(), List.of(), false);
                var rounds = prev == null ? 0 : prev.rounds();
                try {
                    reviews.save(missionId, none, increment, rounds, DesignScreenReview.AWAITING_DECISION);
                } catch (RuntimeException saveError) {
                    log.warn("MISSION {} - could not save the design review of {}: {}", missionId, screen,
                            saveError.getMessage());
                }
                state.put(screen, with(prev, increment, none, DesignScreenReview.AWAITING_DECISION, rounds));
            }
        }

        var awaiting = screens.stream().filter(s -> state.get(s) != null
                && DesignScreenReview.AWAITING_DECISION.equals(state.get(s).status())).toList();
        var accepted = screens.stream().allMatch(s -> state.get(s) != null
                && (DesignScreenReview.PASSED.equals(state.get(s).status())
                || DesignScreenReview.ACCEPTED.equals(state.get(s).status())));
        return new Outcome(accepted, awaiting, head, note(screens, state, error));
    }

    private Captures mockups(String missionId, String headSha, int increment, int version) {
        var stored = captures.mockupCaptures(missionId, version);
        if (stored.isPresent() && !stored.get().pngByScreenSize().isEmpty()) {
            return stored.get();
        }
        // La versión aprobada no tiene captura (o su carpeta ya no está): las maquetas ya están commiteadas en HEAD.
        return captures.recaptureMockups(missionId, headSha, increment, version);
    }

    /** Un resultado por tamaño con maqueta capturada; vacío si la pantalla no tiene ninguna maqueta capturada. */
    private static List<ScreenResult> compare(String screen, Captures mockups, Captures app, VisualDesign design,
                                              double threshold) {
        var out = new ArrayList<ScreenResult>();
        for (var size : Captures.SIZES) {
            var key = screen + "-" + size;
            var mockupPng = mockups.pngByScreenSize().get(key);
            if (mockupPng == null) {
                continue;
            }
            var mockupBoxes = mockups.boxesByScreenSize().getOrDefault(key, List.of());
            var mockupImg = image(mockupPng);
            if (mockupImg == null) {
                continue;
            }
            var appPng = app.pngByScreenSize().get(key);
            var appImg = appPng == null ? null : image(appPng);
            if (appImg == null) {
                // La vista previa no se pudo abrir en ese tamaño: faltan todos sus elementos.
                var missing = mockupBoxes.stream().map(Box::id).toList();
                out.add(new ScreenResult(screen, size, 0.0, List.of(), missing.isEmpty() ? List.of(screen) : missing,
                        false));
                continue;
            }
            out.add(DesignComparison.compare(screen, size, mockupImg, mockupBoxes, appImg,
                    app.boxesByScreenSize().getOrDefault(key, List.of()), design.system(), threshold));
        }
        return out;
    }

    private static BufferedImage image(Path png) {
        try {
            return ImageIO.read(png.toFile());
        } catch (Exception ex) {
            return null;
        }
    }

    /** El peor tamaño: el de menor similitud entre los que fallan (o entre todos si pasan). */
    private static ScreenResult worst(String screen, List<ScreenResult> results) {
        if (results == null || results.isEmpty()) {
            return new ScreenResult(screen, "", 0.0, List.of(), List.of(), false);
        }
        var failing = results.stream().filter(r -> !r.passed()).toList();
        return (failing.isEmpty() ? results : failing).stream().min(Comparator.comparingDouble(ScreenResult::ssim))
                .orElseThrow();
    }

    private static DesignScreenReview with(DesignScreenReview prev, int increment, ScreenResult r, String status,
                                           int rounds) {
        return new DesignScreenReview(increment, r.screen(), r.size(), r.ssim(), status, rounds,
                prev == null ? 0 : prev.extraRounds(), prev == null ? null : prev.decidedBy(),
                prev == null ? null : prev.decisionNote(), null);
    }

    /** La vista previa de esa pantalla en el commit, o null si no existe. */
    static String preview(List<String> files, String screen) {
        var nn = screen.replaceAll("\\D", "");
        for (var path : files) {
            var m = PREVIEW.matcher(path);
            if (m.matches() && Integer.parseInt(m.group(2)) == Integer.parseInt(nn.isEmpty() ? "-1" : nn)) {
                return path;
            }
        }
        return null;
    }

    /** La tarea WORK dueña de la vista previa (sus ownedPaths la cubren), o null. */
    private static PlannedTask owner(TeamMissionContext ctx, String preview) {
        if (preview == null) {
            return null;
        }
        return ctx.plan().workTasks().stream().filter(t -> OwnedPaths.coveredByAny(t.ownedPathsOrEmpty(), preview))
                .findFirst().orElse(null);
    }

    /** Raíz del proyecto Flutter de esa vista previa ("" o "app/"). */
    private static String root(String preview) {
        var m = PREVIEW.matcher(preview);
        return m.matches() && m.group(1) != null ? m.group(1) + "/" : "";
    }

    /** La vista previa y los .dart de presentation/ que importa, del mismo dueño; ruta → contenido, en orden. */
    private Map<String, String> screenFiles(String missionId, String sha, List<String> files, String preview,
                                            PlannedTask owner) {
        var out = new LinkedHashMap<String, String>();
        var content = read(missionId, sha, preview);
        out.put(preview, content == null ? "" : content);
        if (content == null) {
            return out;
        }
        var root = root(preview);
        var dir = preview.substring(0, preview.lastIndexOf('/'));
        var matcher = IMPORT.matcher(content);
        while (matcher.find()) {
            var target = resolve(matcher.group(1), dir, root);
            if (target == null || out.containsKey(target) || !target.endsWith(".dart")
                    || !target.contains("/presentation/") || !files.contains(target)
                    || !OwnedPaths.coveredByAny(owner.ownedPathsOrEmpty(), target)
                    || FlutterThemeFiles.RESERVED_ROOTS.stream().anyMatch(r -> target.startsWith(root + r + "/"))) {
                continue;
            }
            var imported = read(missionId, sha, target);
            if (imported != null) {
                out.put(target, imported);
            }
        }
        return out;
    }

    /** Ruta del repositorio de un import de Dart (relativo o package:&lt;paquete&gt;/…); null si es de otro paquete o SDK. */
    static String resolve(String uri, String dir, String root) {
        if (uri.startsWith("dart:")) {
            return null;
        }
        String joined;
        if (uri.startsWith("package:")) {
            var rest = uri.substring("package:".length());
            var slash = rest.indexOf('/');
            if (slash < 0) {
                return null;
            }
            joined = root + "lib/" + rest.substring(slash + 1);
        } else {
            joined = dir + "/" + uri;
        }
        var parts = new ArrayList<String>();
        for (var part : joined.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }

    private String themeOf(String missionId, String sha, List<String> files, String preview) {
        var path = root(preview) + FlutterThemeFiles.THEME_PATH;
        return files.contains(path) ? read(missionId, sha, path) : null;
    }

    private String read(String missionId, String sha, String path) {
        try {
            return workspace.readFileAtCommit(missionId, sha, path);
        } catch (Exception ex) {
            log.warn("MISSION {} - could not read {} for the design correction: {}", missionId, path, ex.getMessage());
            return null;
        }
    }

    /** El prompt de una corrección y los archivos que entraron en él (los únicos que se exigen de vuelta). */
    record Prompt(String text, List<String> required) {
    }

    /**
     * Prompt corto: corrección de Java, comentario del decisor, tema (solo lectura) y los archivos de la pantalla. La
     * vista previa va siempre; los importados, en orden, mientras el total no pase de {@link #PROMPT_LIMIT}.
     */
    static Prompt prompt(String screen, List<ScreenResult> failed, DesignScreenReview prev, int round,
                         Map<String, String> screenFiles, String theme) {
        var limit = prev == null ? DesignScreenReview.MAX_ROUNDS : prev.roundLimit();
        var top = new StringBuilder(cut(DesignComparison.correction(failed), CORRECTION_BUDGET));
        if (prev != null && prev.extraRounds() > 0 && prev.decisionNote() != null && !prev.decisionNote().isBlank()) {
            top.append("\nComentario de quien pidió esta ronda: ").append(cut(prev.decisionNote().strip(), 500));
        }
        var rules = "\nNo cambies otros archivos; lib/theme y lib/forjai son de Forjai. Colores, fuentes y tamaños solo"
                + " de AppTheme o Theme.of(context). Conserva Key('" + screen
                + "') y la Key de cada elemento (igual a su data-id).";
        var themeBlock = theme == null ? ""
                : "\n\nTEMA (lib/theme/app_theme.dart, solo lectura):\n```dart\n" + cut(theme, THEME_BUDGET) + "\n```";
        var blocks = new LinkedHashMap<String, String>();
        // Lo fijo más la lista de rutas (a lo sumo todas): el cálculo del tope es conservador.
        var used = top.length() + rules.length() + themeBlock.length() + 200
                + String.join(", ", screenFiles.keySet()).length();
        var first = true;
        for (var entry : screenFiles.entrySet()) {
            var block = "\n\nARCHIVO " + entry.getKey() + ":\n```dart\n" + entry.getValue() + "\n```";
            if (first || used + block.length() <= PROMPT_LIMIT) {
                blocks.put(entry.getKey(), block);
                used += block.length();
            }
            first = false;
        }
        var required = List.copyOf(blocks.keySet());
        var text = top + "\n\nRonda " + round + " de " + limit + " de la pantalla " + screen
                + ". Corrige SOLO esta pantalla y devuelve completos y corregidos estos archivos: "
                + String.join(", ", required) + "." + rules + themeBlock + String.join("", blocks.values());
        return new Prompt(text, required);
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String note(Set<String> screens, Map<String, DesignScreenReview> state, String error) {
        var parts = new ArrayList<String>();
        for (var screen : screens) {
            var r = state.get(screen);
            if (r != null) {
                parts.add(screen + " " + r.status() + String.format(java.util.Locale.of("es"), " (similitud %.2f, %d ronda(s))",
                        r.ssim(), r.rounds()));
            }
        }
        return String.join("; ", parts) + (error.isEmpty() ? "" : ". No se pudo completar la captura: " + error);
    }

    private static void rethrowCancellation(Exception ex) {
        MissionCancellations.cancellationIn(ex).ifPresent(c -> {
            throw c;
        });
    }
}
