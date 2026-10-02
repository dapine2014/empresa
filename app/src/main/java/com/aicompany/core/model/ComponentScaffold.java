package com.aicompany.core.model;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Spec 2026-10-02 B1 §3: ProjectScaffold por componente, con las rutas bajo la carpeta de cada uno. */
public final class ComponentScaffold {

    private ComponentScaffold() {
    }

    public static List<GeneratedFile> generate(List<AppComponent> components, List<String> contexts,
                                               Map<String, List<DependencyRef>> packagesByPrefixedProject) {
        var files = new ArrayList<GeneratedFile>();
        for (var component : components) {
            var packages = new LinkedHashMap<String, List<DependencyRef>>();
            packagesByPrefixedProject.forEach((project, refs) -> {
                if (component.owns(project)) {
                    component.strip(project).ifPresent(p -> packages.put(p, refs));
                }
            });
            for (var file : ProjectScaffold.generate(component.profile(), component.contexts(contexts), packages)) {
                files.add(new GeneratedFile(component.prefix(file.path()), file.content()));
            }
        }
        return files;
    }

    public static List<String> projectFiles(List<AppComponent> components, List<String> contexts) {
        return components.stream()
                .flatMap(c -> c.profile().projectFiles(c.contexts(contexts)).stream().map(c::prefix))
                .toList();
    }
}
