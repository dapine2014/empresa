package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.model.StackProfile;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Verificado en vivo (MISSION-B1-INV-1790974335, MISSION-B1-AGENDA-1790996608): Neo eligió solo API para "una app para
 * llevar el inventario" y decidió distinto con el mismo pedido. Lo que el fundador dice explícitamente lo decide Java:
 * "app/aplicación/pantalla/interfaz/móvil/celular" exige la app Flutter; "solo API"/"sin pantallas" la prohíbe. Lo
 * ambiguo queda a criterio de Neo (regla del prompt).
 */
public final class RequestedComponents {

    private static final Pattern WANTS_APP = Pattern.compile(
            "\\b(app|apps|aplicacion|aplicaciones|pantalla|pantallas|interfaz|frontend|movil|celular)\\b");
    private static final Pattern API_ONLY = Pattern.compile(
            "\\b(solo|solamente|unicamente)\\s+(una\\s+|un\\s+)?(api|backend|servicio)\\b|\\bsin\\s+(pantallas|interfaz|frontend)\\b");

    private RequestedComponents() {
    }

    public static List<String> errors(String instruction, TeamPlan plan) {
        if (instruction == null || plan == null) {
            return List.of();
        }
        var text = Normalizer.normalize(instruction, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        var apiOnly = API_ONLY.matcher(text);
        var hasApp = plan.hasComponent(StackProfile.FLUTTER_WEB_APP);
        if (apiOnly.find()) {
            return hasApp ? List.of("El pedido dice \"" + apiOnly.group() + "\": es solo backend; quita FLUTTER_WEB_APP "
                    + "de components.") : List.of();
        }
        var app = WANTS_APP.matcher(text);
        if (app.find() && !hasApp) {
            return List.of("El pedido dice \"" + app.group() + "\": las personas usan el producto con pantallas; incluye "
                    + "FLUTTER_WEB_APP en components (y DOTNET_APP si guarda datos en un servidor).");
        }
        return List.of();
    }
}
