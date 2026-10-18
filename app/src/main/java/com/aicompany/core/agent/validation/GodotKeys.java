package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Ajuste de Kael (Pong de 3 niveles, 2026-10-17): Kael escribía a mano los códigos de tecla de project.godot y puso 40
 * para Enter (4194309 en Godot). Java traduce el nombre de la tecla del diseño al código de Godot 4 (enum Key) y arma el
 * bloque [input] que se copia tal cual.
 */
public final class GodotKeys {

    private static final int SPECIAL = 1 << 22;
    private static final Map<String, Integer> KEYS = new LinkedHashMap<>();
    private static final Pattern EVENT_CODE = Pattern.compile("\"(?:physical_)?keycode\"\\s*:\\s*(\\d+)");
    private static final Pattern ACTION_START = Pattern.compile("^([A-Za-z0-9_]+)=");

    static {
        for (char c = 'A'; c <= 'Z'; c++) {
            KEYS.put(String.valueOf(c), (int) c);
        }
        for (char c = '0'; c <= '9'; c++) {
            KEYS.put(String.valueOf(c), (int) c);
        }
        KEYS.put("SPACE", 32);
        KEYS.put("ESCAPE", SPECIAL | 0x01);
        KEYS.put("TAB", SPECIAL | 0x02);
        KEYS.put("BACKSPACE", SPECIAL | 0x04);
        KEYS.put("ENTER", SPECIAL | 0x05);
        KEYS.put("LEFT", SPECIAL | 0x0F);
        KEYS.put("UP", SPECIAL | 0x10);
        KEYS.put("RIGHT", SPECIAL | 0x11);
        KEYS.put("DOWN", SPECIAL | 0x12);
        KEYS.put("SHIFT", SPECIAL | 0x15);
        KEYS.put("CTRL", SPECIAL | 0x16);
        KEYS.put("ALT", SPECIAL | 0x18);
    }

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("ESPACIO", "SPACE"), Map.entry("BARRA", "SPACE"), Map.entry("BARRAESPACIADORA", "SPACE"),
            Map.entry("SPACEBAR", "SPACE"), Map.entry("RETURN", "ENTER"), Map.entry("INTRO", "ENTER"),
            Map.entry("ESC", "ESCAPE"), Map.entry("CONTROL", "CTRL"), Map.entry("ARRIBA", "UP"),
            Map.entry("ABAJO", "DOWN"), Map.entry("IZQUIERDA", "LEFT"), Map.entry("DERECHA", "RIGHT"),
            Map.entry("UPARROW", "UP"), Map.entry("DOWNARROW", "DOWN"), Map.entry("LEFTARROW", "LEFT"),
            Map.entry("RIGHTARROW", "RIGHT"), Map.entry("ARROWUP", "UP"), Map.entry("ARROWDOWN", "DOWN"),
            Map.entry("ARROWLEFT", "LEFT"), Map.entry("ARROWRIGHT", "RIGHT"), Map.entry("FLECHAARRIBA", "UP"),
            Map.entry("FLECHAABAJO", "DOWN"), Map.entry("FLECHAIZQUIERDA", "LEFT"), Map.entry("FLECHADERECHA", "RIGHT"));

    /** Los nombres que acepta key en controls, para los mensajes de error. */
    public static final String NAMES = "A-Z, 0-9, Space, Enter, Escape, Tab, Backspace, Up, Down, Left, Right, Shift, "
            + "Ctrl, Alt";

    private GodotKeys() {
    }

    /** El código de Godot 4 de la tecla, o vacío si el nombre no se reconoce. */
    public static OptionalInt code(String key) {
        if (key == null) {
            return OptionalInt.empty();
        }
        var normalized = key.strip().toUpperCase(Locale.ROOT).replaceAll("[\\s_\\-]", "");
        normalized = ALIASES.getOrDefault(normalized, normalized);
        var code = KEYS.get(normalized);
        return code == null ? OptionalInt.empty() : OptionalInt.of(code);
    }

    /** El bloque [input] de project.godot para los controles del diseño (los de tecla desconocida se omiten). */
    public static String inputBlock(List<InteractionDesign.Control> controls) {
        return "[input]\n\n" + controls.stream().filter(Objects::nonNull).filter(c -> c.action() != null)
                .filter(c -> code(c.key()).isPresent())
                .map(c -> c.action() + "={\n\"deadzone\": 0.5,\n\"events\": [Object(InputEventKey,"
                        + "\"resource_local_to_scene\":false,\"resource_name\":\"\",\"device\":-1,\"window_id\":0,"
                        + "\"alt_pressed\":false,\"shift_pressed\":false,\"ctrl_pressed\":false,\"meta_pressed\":false,"
                        + "\"pressed\":false,\"keycode\":0,\"physical_keycode\":" + code(c.key()).getAsInt()
                        + ",\"key_label\":0,\"unicode\":0,\"location\":0,\"echo\":false,\"script\":null)\n]\n}\n")
                .collect(Collectors.joining());
    }

    /**
     * Las acciones de [input] de project.godot con los códigos de tecla de sus eventos (keycode y physical_keycode
     * distintos de 0).
     */
    public static Map<String, Set<Integer>> declaredCodes(String projectGodot) {
        var out = new LinkedHashMap<String, Set<Integer>>();
        var inInput = false;
        String action = null;
        for (var line : (projectGodot == null ? "" : projectGodot).split("\\R")) {
            var t = line.strip();
            if (t.startsWith("[") && t.endsWith("]") && !t.contains("\"")) {
                inInput = t.equals("[input]");
                action = null;
                continue;
            }
            if (!inInput) {
                continue;
            }
            var start = ACTION_START.matcher(t);
            if (start.find()) {
                action = start.group(1);
                out.putIfAbsent(action, new LinkedHashSet<>());
            }
            if (action != null) {
                var m = EVENT_CODE.matcher(t);
                while (m.find()) {
                    var code = Integer.parseInt(m.group(1));
                    if (code != 0) {
                        out.get(action).add(code);
                    }
                }
            }
        }
        return out;
    }

    /** Los controles cuya tecla no está en el evento de su acción en project.godot (las ausentes las revisa otro check). */
    public static List<String> mismatches(String projectGodot, List<InteractionDesign.Control> controls) {
        var declared = declaredCodes(projectGodot);
        var problems = new ArrayList<String>();
        for (var c : controls.stream().filter(Objects::nonNull).toList()) {
            var expected = code(c.key());
            var codes = c.action() == null ? null : declared.get(c.action());
            if (expected.isEmpty() || codes == null || codes.contains(expected.getAsInt())) {
                continue;
            }
            problems.add("la acción " + c.action() + " debe usar la tecla " + c.key() + " (\"physical_keycode\":"
                    + expected.getAsInt() + ") y trae " + (codes.isEmpty() ? "ningún código" : codes));
        }
        return problems;
    }
}
