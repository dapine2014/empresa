package com.aicompany.core.agent.validation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Errores reales del compilador en la salida del sandbox (MSBuild/C# y Dart), con rutas relativas al
 * repositorio. Base del ciclo de corrección mínimo: cada error va al dueño del archivo. Sin duplicados
 * (MSBuild repite los errores en el resumen) y sin warnings.
 */
public final class CompilerErrorParser {

    public record CompilerError(String path, int line, int column, String code, String message) {
        public String display() {
            return path + "(" + line + "," + column + "): " + code + ": " + message;
        }
    }

    private static final Pattern DOTNET = Pattern.compile(
            "^\\s*(?:/work/)?(\\S+?\\.cs)\\((\\d+),(\\d+)\\): error (\\w+): (.*?)(?:\\s+\\[[^\\]]*\\])?\\s*$");
    private static final Pattern DART = Pattern.compile(
            "^\\s*(?:/work/)?(\\S+?\\.dart):(\\d+):(\\d+): Error: (.*?)\\s*$");

    private CompilerErrorParser() {
    }

    public static List<CompilerError> parse(String output) {
        if (output == null) {
            return List.of();
        }
        var errors = new LinkedHashSet<CompilerError>();
        for (var line : output.split("\\R")) {
            var dotnet = DOTNET.matcher(line);
            if (dotnet.matches()) {
                errors.add(new CompilerError(dotnet.group(1), Integer.parseInt(dotnet.group(2)),
                        Integer.parseInt(dotnet.group(3)), dotnet.group(4), dotnet.group(5)));
                continue;
            }
            var dart = DART.matcher(line);
            if (dart.matches()) {
                errors.add(new CompilerError(dart.group(1), Integer.parseInt(dart.group(2)),
                        Integer.parseInt(dart.group(3)), "Error", dart.group(4)));
            }
        }
        return new ArrayList<>(errors);
    }
}
