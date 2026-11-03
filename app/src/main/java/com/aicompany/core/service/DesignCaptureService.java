package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.VisualDesign;
import com.aicompany.core.model.Box;
import com.aicompany.core.model.Captures;
import com.aicompany.core.model.CssTokens;
import com.aicompany.core.model.SandboxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Spec 2026-11-01 front con diseño visual: Forjai commitea las maquetas ({@code .forjai/mockups/<PANT-NN>.html}, con el
 * CSS de variables que genera Java) en el repositorio de la misión y el sandbox las renderiza a PNG (job DESIGN_CAPTURE).
 * Las capturas quedan en el staging que el runner comparte con las entregas ({@code <deliveries-root>/.staging}).
 */
@Service
public class DesignCaptureService {

    private static final Logger log = LoggerFactory.getLogger(DesignCaptureService.class);

    static final String MOCKUPS_DIR = ".forjai/mockups/";
    private static final Pattern MISSION_ID = Pattern.compile("^[A-Z0-9-]+$");
    private static final Pattern SCREEN = Pattern.compile("PANT-\\d{2}");
    /** Lo único que se lee del staging y lo único que se sirve: {@code PANT-NN-(phone|desktop).(png|json)}. */
    private static final Pattern CAPTURE_FILE = Pattern.compile("(PANT-\\d{2}-(?:phone|desktop))\\.(png|json)");
    private static final Pattern IMAGE_NAME = Pattern.compile("PANT-\\d{2}-(phone|desktop)\\.png");

    private final SandboxRunnerClient runner;
    private final DevelopmentWorkspaceService workspace;
    private final VisualDesignMemoryService visuals;
    private final JsonMapper json;
    private final Path stagingRoot;

    @Autowired
    public DesignCaptureService(SandboxRunnerClient runner, DevelopmentWorkspaceService workspace,
                                VisualDesignMemoryService visuals, JsonMapper json,
                                @Value("${products.deliveries-root}") String deliveriesRoot) {
        this(runner, workspace, visuals, json, Path.of(deliveriesRoot));
    }

    public DesignCaptureService(SandboxRunnerClient runner, DevelopmentWorkspaceService workspace,
                                VisualDesignMemoryService visuals, JsonMapper json, Path deliveriesRoot) {
        this.runner = runner;
        this.workspace = workspace;
        this.visuals = visuals;
        this.json = json;
        this.stagingRoot = deliveriesRoot.toAbsolutePath().normalize().resolve(".staging");
    }

    /**
     * Commitea las maquetas de esa versión del diseño visual (tarea {@code <misión>-MOCKUPS}, {@code -MOCKUPS-V<n>} en
     * los ajustes) y las captura. Lanza IllegalStateException si no hay captura (el sandbox no responde o falló el paso);
     * con una captura parcial devuelve lo que llegó ({@link Captures#missing}).
     */
    public Captures captureMockups(String missionId, int increment, int version, VisualDesign d) {
        var files = new ArrayList<DevelopmentResult.GeneratedFile>();
        for (var mockup : d.mockups().screens()) {
            if (mockup.screen() == null || !SCREEN.matcher(mockup.screen()).matches()) {
                throw new IllegalStateException("Pantalla de maqueta inválida: " + mockup.screen());
            }
            files.add(new DevelopmentResult.GeneratedFile(MOCKUPS_DIR + mockup.screen() + ".html",
                    CssTokens.page(mockup, d.system())));
        }
        for (var svg : d.mockups().svgs()) {
            files.add(new DevelopmentResult.GeneratedFile(MOCKUPS_DIR + "svg/" + svg.name() + ".svg", svg.svg()));
        }
        String sha;
        try {
            var taskId = missionId + "-MOCKUPS" + (version > 1 ? "-V" + version : "");
            var result = new DevelopmentResult("Maquetas del diseño visual (versión " + version + "), generadas por Forjai",
                    files);
            // Revisión final (I2): lo que un ajuste quitó de las maquetas sale del repositorio en el mismo commit.
            var stale = staleMockups(missionId, files);
            sha = (stale.isEmpty() ? workspace.commitAgentWork(missionId, taskId, "forjai", "Forjai", result)
                    : workspace.commitAgentWork(missionId, taskId, "forjai", "Forjai", result, stale)).sha();
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudieron commitear las maquetas: " + ex.getMessage(), ex);
        }
        var dir = capture(missionId, sha, increment, "MOCKUPS");
        var captures = read(missionId, dir, null);
        visuals.setCaptureDir(missionId, version, dir);
        log.info("MISSION {} - mockups v{} captured: {} image(s) in {}", missionId, version,
                captures.pngByScreenSize().size(), dir);
        return captures;
    }

    /** Archivos de {@code .forjai/mockups/} en HEAD que la versión nueva ya no trae. */
    private List<String> staleMockups(String missionId, List<DevelopmentResult.GeneratedFile> files) throws IOException {
        var head = workspace.headIfAny(missionId);
        if (head == null || head.isEmpty()) {
            return List.of();
        }
        var kept = files.stream().map(DevelopmentResult.GeneratedFile::path).collect(java.util.stream.Collectors.toSet());
        return workspace.filesAtCommit(missionId, head.get()).stream()
                .filter(p -> p.startsWith(MOCKUPS_DIR) && !kept.contains(p)).toList();
    }

    /**
     * Vuelve a capturar las maquetas de esa versión desde un commit que ya las tiene ({@code .forjai/mockups/}), sin
     * commitear nada (el ciclo de diseño no mueve HEAD para esto), y registra la carpeta en la versión.
     */
    public Captures recaptureMockups(String missionId, String sha, int increment, int version) {
        var dir = capture(missionId, sha, increment, "MOCKUPS");
        visuals.setCaptureDir(missionId, version, dir);
        return read(missionId, dir, null);
    }

    /** Captura la app construida ({@code ?preview=<PANT-NN>}) en ese commit; solo se devuelven las pantallas pedidas. */
    public Captures captureApp(String missionId, String headSha, int increment, Set<String> screens) {
        return captureApp(missionId, headSha, increment, screens, "");
    }

    /** Revisión Task 11 (3): la app Flutter de una misión con varios componentes vive en su carpeta ({@code app}). */
    public Captures captureApp(String missionId, String headSha, int increment, Set<String> screens, String componentDir) {
        var dir = capture(missionId, headSha, increment, "APP", componentDir);
        // Revisión R15: la última captura de la app queda registrada para verla al lado de la maqueta.
        visuals.setAppCaptureDir(missionId, dir);
        return read(missionId, dir, screens);
    }

    /** La captura ya registrada de esa versión de las maquetas, si existe. */
    public Optional<Captures> mockupCaptures(String missionId, int version) {
        return mockupDir(missionId, version).map(dir -> read(missionId, dir, null));
    }

    /** PNG de las maquetas de la última versión del diseño visual, por {@code PANT-NN-<tamaño>}. */
    public Map<String, Path> mockupImages(String missionId) {
        return latestMockupDir(missionId).map(dir -> read(missionId, dir, null).pngByScreenSize())
                .<Map<String, Path>>map(TreeMap::new).orElse(Map.of());
    }

    /** Un PNG de las maquetas de la última versión; vacío si el nombre no es {@code PANT-NN-(phone|desktop).png}. */
    public Optional<Path> mockupImage(String missionId, String name) {
        if (name == null || !IMAGE_NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        return latestMockupDir(missionId).map(dir -> stagingRoot.resolve(missionId).resolve(dir).resolve(name))
                .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS));
    }

    /** Revisión R15: los PNG de la última captura de la app, por {@code PANT-NN-<tamaño>}. */
    public Map<String, Path> appImages(String missionId) {
        return appDir(missionId).map(dir -> read(missionId, dir, null).pngByScreenSize())
                .<Map<String, Path>>map(TreeMap::new).orElse(Map.of());
    }

    /** Revisión R15: un PNG de la última captura de la app, con la misma lista blanca y NOFOLLOW que las maquetas. */
    public Optional<Path> appImage(String missionId, String name) {
        if (name == null || !IMAGE_NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        return appDir(missionId).map(dir -> stagingRoot.resolve(missionId).resolve(dir).resolve(name))
                .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS));
    }

    private Optional<String> appDir(String missionId) {
        if (missionId == null || !MISSION_ID.matcher(missionId).matches()) {
            return Optional.empty();
        }
        return visuals.appCaptureDir(missionId).filter(dir -> isCaptureDir(dir, "app"));
    }

    private Optional<String> latestMockupDir(String missionId) {
        if (missionId == null || !MISSION_ID.matcher(missionId).matches()) {
            return Optional.empty();
        }
        return visuals.latest(missionId).flatMap(v -> mockupDir(missionId, v.version()));
    }

    private Optional<String> mockupDir(String missionId, int version) {
        return visuals.captureDir(missionId, version).filter(dir -> isCaptureDir(dir, "mockups"));
    }

    private static boolean isCaptureDir(String dir, String mode) {
        return dir != null && dir.matches("diseno-[0-9]+-" + mode + "-[0-9a-f-]{36}");
    }

    private String capture(String missionId, String sha, int increment, String mode) {
        return capture(missionId, sha, increment, mode, "");
    }

    private String capture(String missionId, String sha, int increment, String mode, String componentDir) {
        if (missionId == null || !MISSION_ID.matcher(missionId).matches()) {
            throw new IllegalStateException("missionId inválido: " + missionId);
        }
        var job = componentDir == null || componentDir.isEmpty() ? runner.designCapture(missionId, sha, increment, mode)
                : runner.designCapture(missionId, sha, increment, mode, componentDir);
        SandboxResult result = job.orElseThrow(() ->
                new IllegalStateException(runner.lastError() == null ? "El sandbox-runner no devolvió resultado."
                        : runner.lastError()));
        if (!"PASS".equals(result.overall())) {
            var failed = result.steps() == null ? null : result.steps().stream()
                    .filter(s -> !"PASS".equals(s.status())).findFirst().orElse(null);
            throw new IllegalStateException("La captura falló" + (failed == null ? "."
                    : " en el paso " + failed.name() + ": " + failed.outputTail()));
        }
        var dir = result.stagingDir();
        if (!isCaptureDir(dir, mode.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalStateException("Carpeta de captura inválida: " + dir);
        }
        return dir;
    }

    /** Del staging solo entran los archivos regulares {@code PANT-NN-<tamaño>.(png|json)}. */
    private Captures read(String missionId, String dir, Set<String> screens) {
        var folder = stagingRoot.resolve(missionId).resolve(dir);
        var pngs = new LinkedHashMap<String, Path>();
        var boxes = new LinkedHashMap<String, List<Box>>();
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return new Captures(pngs, boxes);
        }
        try (var list = Files.list(folder)) {
            for (var file : list.sorted().toList()) {
                var m = CAPTURE_FILE.matcher(file.getFileName().toString());
                if (!m.matches() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                var key = m.group(1);
                if (screens != null && !screens.contains(key.substring(0, key.lastIndexOf('-')))) {
                    continue;
                }
                if ("png".equals(m.group(2))) {
                    pngs.put(key, file);
                } else {
                    boxes.put(key, List.of(json.readValue(Files.readString(file), Box[].class)));
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer la captura " + dir + ": " + ex.getMessage(), ex);
        }
        return new Captures(pngs, boxes);
    }
}
