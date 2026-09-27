package com.aicompany.core.model;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.model.StackProfile.Layer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Los .csproj de un plan .NET, generados por Java (verificado en vivo con MISSION-SANDBOX-VERIFY-2 a -4:
 * qwen3:8b no escribía los proyectos, los escribía con texto antes del XML, cortados, o deformaba
 * ".csproj" en ".cs. proj" aun con la ruta exacta en la corrección). Son 100% derivables del plan: capas,
 * reglas DDD de dependencia y los paquetes disponibles sin red en el sandbox (ver executionContract()).
 */
public final class ProjectScaffold {

    private ProjectScaffold() {
    }

    /** Dependencias entre capas del mismo contexto (reglas DDD de StackProfile/DddLayerChecker). */
    private static final Map<Layer, List<Layer>> REFERENCES = Map.of(
            Layer.DOMAIN, List.of(),
            Layer.APPLICATION, List.of(Layer.DOMAIN),
            Layer.INFRASTRUCTURE, List.of(Layer.DOMAIN, Layer.APPLICATION),
            Layer.API, List.of(Layer.DOMAIN, Layer.APPLICATION, Layer.INFRASTRUCTURE),
            Layer.TESTS, List.of(Layer.DOMAIN, Layer.APPLICATION, Layer.INFRASTRUCTURE));

    private static final String TEST_PACKAGES = """
              <ItemGroup>
                <PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.8.0" />
                <PackageReference Include="xunit" Version="2.5.3" />
                <PackageReference Include="xunit.runner.visualstudio" Version="2.5.3" />
              </ItemGroup>
            """;

    /** Paquetes web precargados en la imagen dotnet-app (ver executionContract()). */
    private static final String API_PACKAGES = """
              <ItemGroup>
                <PackageReference Include="Swashbuckle.AspNetCore" Version="6.6.2" />
                <PackageReference Include="Microsoft.AspNetCore.OpenApi" Version="8.0.31" />
              </ItemGroup>
            """;

    public static List<GeneratedFile> generate(StackProfile profile, List<String> contexts) {
        return generate(profile, contexts, Map.of());
    }

    /**
     * Parte 3: packagesByProject (ruta del .csproj → paquetes aprobados pedidos por el dueño de esa capa) se agrega
     * como PackageReference después de los paquetes del perfil.
     */
    public static List<GeneratedFile> generate(StackProfile profile, List<String> contexts,
                                               Map<String, List<DependencyRef>> packagesByProject) {

        if (profile.ecosystem() != StackProfile.Ecosystem.NUGET) {
            return List.of();
        }

        var files = new ArrayList<GeneratedFile>();

        for (var context : contexts) {
            for (var layer : profile.layers()) {
                if (profile.isSharedLayer(layer)) {
                    continue;
                }
                var dir = profile.resolveRoot(context, layer).orElseThrow();
                var references = REFERENCES.getOrDefault(layer, List.of()).stream()
                        .map(dep -> profile.resolveRoot(context, dep))
                        .flatMap(java.util.Optional::stream)
                        .map(depDir -> projectPath(depDir))
                        .toList();
                var sdk = layer == Layer.API ? "Microsoft.NET.Sdk.Web" : "Microsoft.NET.Sdk";
                var extra = layer == Layer.TESTS
                        ? "    <IsPackable>false</IsPackable>\n    <IsTestProject>true</IsTestProject>\n" : "";
                var packages = layer == Layer.TESTS ? TEST_PACKAGES : layer == Layer.API ? API_PACKAGES : "";
                var path = projectPath(dir);
                files.add(project(path, sdk, extra, references,
                        packages + requestedPackages(packagesByProject.getOrDefault(path, List.of()))));
            }
        }

        if (profile == StackProfile.GODOT_DOTNET_GAME) {
            var references = contexts.stream()
                    .flatMap(c -> List.of(Layer.DOMAIN, Layer.APPLICATION).stream()
                            .map(l -> profile.resolveRoot(c, l).orElseThrow()))
                    .map(ProjectScaffold::projectPath)
                    .toList();
            files.add(project("game/Game.csproj", "Godot.NET.Sdk/4.3.0",
                    "    <EnableDynamicLoading>true</EnableDynamicLoading>\n", references,
                    requestedPackages(packagesByProject.getOrDefault("game/Game.csproj", List.of()))));
        }

        files.add(solution(files));
        return files;
    }

    /** Solution.sln con todos los proyectos (GUIDs deterministas por ruta). El sandbox genera la suya igual. */
    private static GeneratedFile solution(List<GeneratedFile> projects) {
        var csharp = "{9A19103F-16F7-4668-BE54-9A1E7A4F7556}";
        var out = new StringBuilder("\nMicrosoft Visual Studio Solution File, Format Version 12.00\n# Visual Studio Version 17\n");
        var guids = new ArrayList<String>();
        for (var project : projects) {
            var name = project.path().substring(project.path().lastIndexOf('/') + 1).replace(".csproj", "");
            var guid = "{" + java.util.UUID.nameUUIDFromBytes(project.path().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .toString().toUpperCase(java.util.Locale.ROOT) + "}";
            guids.add(guid);
            out.append("Project(\"").append(csharp).append("\") = \"").append(name).append("\", \"")
                    .append(project.path().replace('/', '\\')).append("\", \"").append(guid).append("\"\nEndProject\n");
        }
        out.append("Global\n\tGlobalSection(SolutionConfigurationPlatforms) = preSolution\n")
                .append("\t\tDebug|Any CPU = Debug|Any CPU\n\t\tRelease|Any CPU = Release|Any CPU\n\tEndGlobalSection\n")
                .append("\tGlobalSection(ProjectConfigurationPlatforms) = postSolution\n");
        for (var guid : guids) {
            for (var config : List.of("Debug", "Release")) {
                out.append("\t\t").append(guid).append(".").append(config).append("|Any CPU.ActiveCfg = ").append(config).append("|Any CPU\n");
                out.append("\t\t").append(guid).append(".").append(config).append("|Any CPU.Build.0 = ").append(config).append("|Any CPU\n");
            }
        }
        out.append("\tEndGlobalSection\nEndGlobal\n");
        return new GeneratedFile("Solution.sln", out.toString());
    }

    private static String requestedPackages(List<DependencyRef> packages) {
        if (packages.isEmpty()) {
            return "";
        }
        return "  <ItemGroup>\n" + packages.stream()
                .map(p -> "    <PackageReference Include=\"" + p.name() + "\" Version=\"" + p.version() + "\" />")
                .collect(Collectors.joining("\n")) + "\n  </ItemGroup>\n";
    }

    private static String projectPath(String dir) {
        return dir + "/" + dir.substring(dir.lastIndexOf('/') + 1) + ".csproj";
    }

    private static GeneratedFile project(String path, String sdk, String extraProperties, List<String> references,
                                         String packages) {
        var from = Path.of(path).getParent();
        var refs = references.isEmpty() ? "" : "  <ItemGroup>\n" + references.stream()
                .map(r -> "    <ProjectReference Include=\"" + from.relativize(Path.of(r)) + "\" />")
                .collect(Collectors.joining("\n")) + "\n  </ItemGroup>\n";
        var content = "<Project Sdk=\"" + sdk + "\">\n"
                + "  <PropertyGroup>\n"
                + "    <TargetFramework>net8.0</TargetFramework>\n"
                + "    <ImplicitUsings>enable</ImplicitUsings>\n"
                + "    <Nullable>enable</Nullable>\n"
                + extraProperties
                + "  </PropertyGroup>\n"
                + refs
                + packages
                + "</Project>\n";
        return new GeneratedFile(path, content);
    }
}
