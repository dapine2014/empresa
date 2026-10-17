package com.aicompany.core.delivery;

import com.aicompany.core.agent.model.ClientContract;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Entrega del producto (spec 2026-10-17 §1, §3): qué targets pide el runner y qué limitaciones dice ENTREGA.md. */
public final class DeliveryTargets {

    public static final String NO_WEB_CSHARP = "Sin versión Web: el juego está en C# y Godot (4.3 y la última estable, "
            + "4.7) no exporta C# a Web.";
    public static final String MACOS_UNSIGNED = "macOS sin firma de Apple: ábrelo con clic derecho → Abrir.";
    public static final String NO_AAB_WITHOUT_KEY = "Sin .aab para Google Play: falta la clave de publicación de Android "
            + "del proyecto (cárgala en la misión → Entregas → Clave de Android y genera la entrega de nuevo).";

    public record Plan(List<String> targets, List<String> limitations) { }

    private DeliveryTargets() { }

    public static Plan plan(String stackProfile, ClientContract.Delivery delivery, boolean lastIncrement,
                            boolean hasReleaseKey) {
        var asked = delivery == null ? List.<String>of() : delivery.platformsOrEmpty().stream()
                .map(p -> p.toUpperCase(Locale.ROOT)).toList();
        var targets = new ArrayList<String>();
        var limits = new ArrayList<String>();
        switch (stackProfile == null ? "" : stackProfile) {
            case "GODOT_DOTNET_GAME" -> {
                if (asked.isEmpty() || asked.contains("WINDOWS")) targets.add("windows");
                if (asked.isEmpty() || asked.contains("MACOS")) { targets.add("macos"); limits.add(MACOS_UNSIGNED); }
                if (asked.isEmpty() || asked.contains("LINUX")) targets.add("linux");
                if (asked.isEmpty() || asked.contains("ANDROID")) {
                    targets.add("android-apk");
                    if (lastIncrement && hasReleaseKey) targets.add("android-aab");
                    else if (lastIncrement) limits.add(NO_AAB_WITHOUT_KEY);
                }
                if (asked.isEmpty() || asked.contains("WEB")) limits.add(0, NO_WEB_CSHARP);
            }
            case "FLUTTER_WEB_APP" -> targets.add("web");
            case "DOTNET_APP" -> targets.add("app");
            default -> limits.add("El perfil " + stackProfile + " no tiene empaquetado.");
        }
        return new Plan(List.copyOf(targets), List.copyOf(limits));
    }
}
