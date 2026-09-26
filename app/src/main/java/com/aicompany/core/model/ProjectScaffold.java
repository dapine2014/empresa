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

    public static List<GeneratedFile> generate(StackProfile profile, List<String> contexts) {

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
                files.add(project(projectPath(dir), sdk, extra, references, layer == Layer.TESTS ? TEST_PACKAGES : ""));
            }
        }

        if (profile == StackProfile.GODOT_DOTNET_GAME) {
            var references = contexts.stream()
                    .flatMap(c -> List.of(Layer.DOMAIN, Layer.APPLICATION).stream()
                            .map(l -> profile.resolveRoot(c, l).orElseThrow()))
                    .map(ProjectScaffold::projectPath)
                    .toList();
            files.add(project("game/Game.csproj", "Godot.NET.Sdk/4.3.0",
                    "    <EnableDynamicLoading>true</EnableDynamicLoading>\n", references, ""));
        }

        return files;
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
