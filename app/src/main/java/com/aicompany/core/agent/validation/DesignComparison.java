package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.VisualSystem;
import com.aicompany.core.agent.model.VisualSystem.ColorToken;
import com.aicompany.core.model.Box;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spec 2026-11-01 front con diseño visual: compara la captura de la app con la maqueta aprobada, determinista y sin
 * modelo (SSIM global + cajas por elemento), y arma la corrección corta en español para el front.
 */
public final class DesignComparison {

    /** Diferencia de posición/tamaño tolerada, en px. */
    static final int TOLERANCIA_PX = 8;

    private DesignComparison() { }

    public record ElementDiff(String id, Box mockup, Box app, String reason) { }

    public record ScreenResult(String screen, String size, double ssim, List<ElementDiff> diffs,
                               List<String> missing, boolean passed) { }

    public static ScreenResult compare(String screen, String size, BufferedImage mockup, List<Box> mockupBoxes,
                                       BufferedImage app, List<Box> appBoxes, VisualSystem vs, double threshold) {
        double ssim = Ssim.score(mockup, app);
        Map<String, Box> porId = new HashMap<>();
        for (Box b : appBoxes) porId.putIfAbsent(b.id(), b);
        List<ElementDiff> diffs = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Box m : mockupBoxes) {
            Box a = porId.get(m.id());
            if (a == null) { missing.add(m.id()); continue; }
            List<String> motivos = new ArrayList<>();
            if (Math.abs(m.x() - a.x()) > TOLERANCIA_PX || Math.abs(m.y() - a.y()) > TOLERANCIA_PX) {
                motivos.add("posición maqueta x=" + m.x() + " y=" + m.y() + " / app x=" + a.x() + " y=" + a.y());
            }
            if (Math.abs(m.w() - a.w()) > TOLERANCIA_PX || Math.abs(m.h() - a.h()) > TOLERANCIA_PX) {
                motivos.add("tamaño maqueta " + m.w() + "x" + m.h() + " / app " + a.w() + "x" + a.h());
            }
            if (!mismoColor(m.color(), a.color(), vs)) {
                motivos.add(describirColor(m.color(), a.color(), vs));
            }
            if (!motivos.isEmpty()) diffs.add(new ElementDiff(m.id(), m, a, String.join("; ", motivos)));
        }
        boolean passed = ssim >= threshold && missing.isEmpty();
        return new ScreenResult(screen, size, ssim, List.copyOf(diffs), List.copyOf(missing), passed);
    }

    public static String correction(List<ScreenResult> failed) {
        StringBuilder sb = new StringBuilder("CORRECCIÓN DE DISEÑO — la app no coincide con la maqueta aprobada.");
        for (ScreenResult r : failed) {
            sb.append("\nPantalla ").append(r.screen()).append(" (").append(r.size()).append("), similitud ")
                    .append(String.format(Locale.of("es"), "%.2f", r.ssim())).append(":");
            for (ElementDiff d : r.diffs()) sb.append("\n- ").append(d.id()).append(": ").append(d.reason()).append(".");
            if (!r.missing().isEmpty()) {
                // Revisión final (C2): una Key sola no llega a la captura; ForjaiId pone Semantics(identifier:) y la Key.
                sb.append("\n- Faltan en la app (envuelve cada uno con ForjaiId de lib/forjai/forjai_id.dart): ")
                        .append(r.missing().stream().map(id -> "ForjaiId('" + id + "', child: …)")
                                .collect(java.util.stream.Collectors.joining(", "))).append(".");
            }
            sb.append("\nLo demás coincide: no lo cambies.");
        }
        return sb.toString();
    }

    private static String token(String hex, VisualSystem vs) {
        if (hex == null) return null;
        for (ColorToken t : vs.colorsOrEmpty()) if (t.hex() != null && t.hex().equalsIgnoreCase(hex)) return t.name();
        return null;
    }

    private static boolean mismoColor(String m, String a, VisualSystem vs) {
        if (m == null || a == null) return true; // sin color dominante medible no hay diferencia que afirmar
        String tm = token(m, vs), ta = token(a, vs);
        if (tm == null && ta == null) return m.equalsIgnoreCase(a);
        return tm != null && tm.equals(ta);
    }

    private static String describirColor(String m, String a, VisualSystem vs) {
        String tm = token(m, vs);
        String antes = "color maqueta " + (tm != null ? tm : m);
        String ta = token(a, vs);
        if (ta != null) return antes + " / app " + ta + ". Usa AppTheme." + (tm != null ? tm : ta);
        return antes + " / app " + a + ", que no está en la paleta. Usa AppTheme." + (tm != null ? tm : masCercano(a, vs));
    }

    private static String masCercano(String hex, VisualSystem vs) {
        String mejor = "primary";
        long min = Long.MAX_VALUE;
        for (ColorToken t : vs.colorsOrEmpty()) {
            long d = distancia(hex, t.hex());
            if (d < min) { min = d; mejor = t.name(); }
        }
        return mejor;
    }

    private static long distancia(String h1, String h2) {
        int a = Integer.parseInt(h1.substring(1), 16), b = Integer.parseInt(h2.substring(1), 16);
        long dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF), dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF),
                db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db;
    }
}
