package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign.AppDesign;
import com.aicompany.core.agent.model.Mockups;
import com.aicompany.core.agent.model.VisualSystem;
import com.aicompany.core.model.CssTokens;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Spec 2026-11-01 front con diseño visual: rechazo conservador (regex) de maquetas con colores literales, scripts o URLs. */
public final class MockupValidator {

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");
    private static final Pattern FUNC_COLOR = Pattern.compile("(?i)\\b(rgba?|hsla?)\\s*\\(");
    private static final Pattern VAR = Pattern.compile("var\\(\\s*--([\\w-]+)");
    private static final Pattern SCRIPT = Pattern.compile("(?i)<script|javascript:");
    private static final Pattern EXTERNAL = Pattern.compile("(?i)https?:|\\burl\\s*\\(|@import|//\\s*[\\w-]+\\.[a-z]{2,}");
    // Prueba en vivo 6: el xmlns del SVG (www.w3.org) y url(#id) de su propio <defs> no salen a la red.
    private static final Pattern SAFE_REFS = Pattern.compile(
            "(?i)xmlns(?::[\\w-]+)?\\s*=\\s*[\"']https?://www\\.w3\\.org/[^\"']*[\"']|(?:xlink:)?href\\s*=\\s*[\"']#[\\w-]+[\"']|url\\(\\s*[\"']?#[\\w-]+[\"']?\\s*\\)");
    private static final Pattern DATA_ID = Pattern.compile("data-id\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern SVG_NAME = Pattern.compile("[a-z0-9_]+");

    private MockupValidator() {
    }

    public static List<String> validate(Mockups m, AppDesign app, Set<String> screensInIncrement, VisualSystem vs) {
        var errors = new ArrayList<String>();
        var knownVars = CssTokens.variableNames(vs);
        var byScreen = new HashSet<String>();
        for (var mock : m.screens()) {
            var id = mock.screen();
            byScreen.add(id);
            if (app != null && app.screens().stream().noneMatch(s -> s.id().equals(id)))
                errors.add("La maqueta " + id + " no corresponde a ninguna pantalla del diseño de interacción.");
            var html = mock.html() == null ? "" : mock.html();
            checkText("Maqueta " + id, html, knownVars, errors);
            var ids = new HashSet<String>();
            var matcher = DATA_ID.matcher(html);
            var count = 0;
            while (matcher.find()) {
                count++;
                if (!ids.add(matcher.group(1)))
                    errors.add("Maqueta " + id + ": el data-id \"" + matcher.group(1) + "\" está repetido.");
            }
            if (count == 0) errors.add("Maqueta " + id + ": no tiene ningún data-id; cada elemento relevante lleva uno único.");
        }
        for (var required : screensInIncrement)
            if (!byScreen.contains(required)) errors.add("Falta la maqueta de la pantalla " + required + ".");
        for (var svg : m.svgs()) {
            if (svg.name() == null || !SVG_NAME.matcher(svg.name()).matches())
                errors.add("SVG \"" + svg.name() + "\": el nombre debe ser [a-z0-9_]+.");
            checkText("SVG " + svg.name(), svg.svg() == null ? "" : svg.svg(), knownVars, errors);
        }
        return errors;
    }

    private static void checkText(String where, String raw, Set<String> knownVars, List<String> errors) {
        var text = SAFE_REFS.matcher(raw).replaceAll("");
        if (HEX.matcher(text).find() || FUNC_COLOR.matcher(text).find())
            errors.add(where + ": tiene un color literal (#hex, rgb(, hsl(); usa var(--color-<token>) del sistema visual.");
        var vars = VAR.matcher(text);
        while (vars.find())
            if (!knownVars.contains(vars.group(1)))
                errors.add(where + ": la variable --" + vars.group(1) + " no existe en el sistema visual.");
        if (SCRIPT.matcher(text).find()) errors.add(where + ": no puede llevar script ni javascript:.");
        if (EXTERNAL.matcher(text).find())
            errors.add(where + ": no puede referenciar URLs externas, url() ni @import.");
    }
}
