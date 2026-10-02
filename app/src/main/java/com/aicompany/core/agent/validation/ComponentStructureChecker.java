package com.aicompany.core.agent.validation;

import com.aicompany.core.model.AppComponent;
import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.StaticCheck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spec 2026-10-02 B1 §3: los chequeos de estructura y capas de cada componente, con las piezas de siempre sobre rutas
 * relativas al componente y los resultados con la ruta real. {@code forjaiFiles}: archivos que escribe Java (p. ej.
 * api_routes.dart), fuera de la estructura de los agentes pero permitidos.
 */
public final class ComponentStructureChecker {

    private ComponentStructureChecker() {
    }

    public static List<StaticCheck> check(List<AppComponent> components, List<String> contexts, List<String> files,
                                          Map<String, String> contents, List<String> forjaiFiles) {

        var multi = components.size() > 1;
        var entry = new ArrayList<StaticCheck>();
        var structure = new ArrayList<StaticCheck>();
        var ddd = new ArrayList<StaticCheck>();
        var portability = new ArrayList<StaticCheck>();
        var outsideAll = new ArrayList<String>();

        if (multi) {
            for (var file : files) {
                var common = AppComponent.COMMON_ROOT_PATHS.stream()
                        .anyMatch(root -> OwnedPaths.covers(root, OwnedPaths.normalize(file)));
                if (!common && !forjaiFiles.contains(file) && components.stream().noneMatch(c -> c.owns(file))) {
                    outsideAll.add(file);
                }
            }
        }

        for (var component : components) {
            var ctx = component.contexts(contexts);
            // Con un componente (raíz) quedan exactamente los mismos archivos que antes de B1.
            var own = files.stream().filter(component::owns).filter(f -> !forjaiFiles.contains(f))
                    .map(f -> component.strip(f).orElseThrow()).toList();
            var checks = ProfileStructureChecker.check(component.profile(), ctx, own);
            entry.add(prefixed(checks.get(0), component));
            structure.add(prefixed(checks.get(1), component));

            var relativeContents = new LinkedHashMap<String, String>();
            contents.forEach((path, content) -> {
                if (component.owns(path)) {
                    component.strip(path).ifPresent(p -> relativeContents.put(p, content));
                }
            });
            var analyzed = (int) relativeContents.keySet().stream()
                    .filter(p -> p.endsWith(component.profile().sourceExtension())).count();
            ddd.add(prefixed(DddLayerChecker.toCheck(
                    DddLayerChecker.check(component.profile(), ctx, relativeContents), analyzed), component));

            if (component.profile() == StackProfile.FLUTTER_WEB_APP) {
                var dart = new LinkedHashMap<String, String>();
                relativeContents.forEach((p, c) -> {
                    if (p.endsWith(".dart") && !forjaiFiles.contains(component.prefix(p))) {
                        dart.put(p, c);
                    }
                });
                portability.add(prefixed(PortabilityChecker.check(dart), component));
            }
        }

        if (!outsideAll.isEmpty()) {
            structure.add(StaticCheck.fail("PROFILE_STRUCTURE", "Archivos fuera de " + components.stream()
                    .map(c -> c.dir() + "/").toList() + " y de la raíz común " + AppComponent.COMMON_ROOT_PATHS + ": "
                    + outsideAll, null, outsideAll));
        }

        var result = new ArrayList<StaticCheck>();
        result.add(merge("ENTRY_FILES", entry));
        result.add(merge("PROFILE_STRUCTURE", structure));
        result.add(merge("DDD_LAYERS", ddd));
        if (!portability.isEmpty()) {
            result.add(merge("PORTABILITY", portability));
        }
        return result;
    }

    /** Las rutas del resultado, con el prefijo del componente (sin cambios en la raíz). */
    private static StaticCheck prefixed(StaticCheck check, AppComponent component) {
        if (component.isRoot()) {
            return check;
        }
        var paths = check.paths() == null ? List.<String>of() : check.paths().stream().map(component::prefix).toList();
        var detail = component.dir() + "/: " + check.detail();
        return check.passed()
                ? StaticCheck.pass(check.check(), detail, check.sha(), paths)
                : StaticCheck.fail(check.check(), detail, check.sha(), paths);
    }

    /** Un solo chequeo por nombre: pasa si pasan todos; si no, junta los detalles y rutas de los que fallaron. */
    private static StaticCheck merge(String name, List<StaticCheck> checks) {
        if (checks.size() == 1) {
            return checks.get(0);
        }
        var failed = checks.stream().filter(c -> !c.passed()).toList();
        if (failed.isEmpty()) {
            return StaticCheck.pass(name, String.join(" | ", checks.stream().map(StaticCheck::detail).toList()),
                    null, List.of());
        }
        var paths = failed.stream().flatMap(c -> c.paths() == null ? java.util.stream.Stream.<String>empty()
                : c.paths().stream()).distinct().toList();
        return StaticCheck.fail(name, String.join(" | ", failed.stream().map(StaticCheck::detail).toList()), null, paths);
    }
}
