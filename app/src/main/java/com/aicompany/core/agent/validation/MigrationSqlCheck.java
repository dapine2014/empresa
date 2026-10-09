package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Sintaxis SQL de las migraciones de Diego sin base (Spec Diego ambientes §3). Puro: divide cada migración en
 * sentencias (respetando comillas, comentarios y bloques {@code $tag$…$tag$}) y parsea con JSqlParser solo las que
 * soporta bien (CREATE TABLE, CREATE INDEX, ALTER TABLE, INSERT, UPDATE, DELETE, DROP TABLE/INDEX). Todo lo demás
 * (funciones, DO, extensiones, COMMENT, tipos propios…) pasa sin analizar: este chequeo nunca debe bloquear SQL
 * válido de PostgreSQL, así que un fallo del parser que no sea un error de sintaxis tampoco bloquea.
 */
public final class MigrationSqlCheck {

    /** Solo lo que JSqlParser entiende sin falsos positivos; ALTER/DROP/UPDATE/DELETE y lo demás pasan sin parsear. */
    private static final Pattern SUPPORTED = Pattern.compile(
            "(?is)^(CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(?!CONCURRENTLY\\b).*"
                    + "|CREATE\\s+((GLOBAL\\s+|LOCAL\\s+)?(TEMP|TEMPORARY)\\s+|UNLOGGED\\s+)?TABLE\\b.*"
                    + "|INSERT\\s+INTO\\b.*)");
    private static final Pattern ENCOUNTERED = Pattern.compile(
            "Encountered:? (?:<[^>]*> / )?(\"[^\"]*\"|\\S+),? at line (\\d+), column (\\d+)");

    private MigrationSqlCheck() {
    }

    /** Un mensaje por error: {@code <archivo>, sentencia <k> (línea <n>): <parser>. Corrige el SQL de PostgreSQL.} */
    public static List<String> errors(List<DevelopmentResult.GeneratedFile> files) {
        var errors = new ArrayList<String>();
        for (var file : files.stream().filter(Objects::nonNull).toList()) {
            if (file.path() == null || file.content() == null || !file.path().startsWith(MigrationFilesGate.ROOT)
                    || !file.path().endsWith(".sql")) {
                continue;
            }
            var statements = split(file.content());
            for (int i = 0; i < statements.size(); i++) {
                var statement = statements.get(i);
                var prefix = file.path() + ", sentencia " + (i + 1) + " (línea " + statement.line() + "): ";
                // Chequeos estructurales: siempre seguros, valen para toda sentencia (también las que no se parsean).
                if (statement.structural() != null) {
                    errors.add(prefix + statement.structural() + ". Corrige el SQL de PostgreSQL.");
                    continue;
                }
                // Cadenas $tag$ y E'…\\…' dentro de una sentencia son válidas en PostgreSQL y JSqlParser no las conoce.
                if (statement.unknownLiteral() || !SUPPORTED.matcher(statement.code()).matches()
                        || createTableAs(statement.code())) {
                    continue;
                }
                var problem = syntaxProblem(statement.text(), statement.textLine());
                if (problem != null) {
                    errors.add(prefix + problem + ". Corrige el SQL de PostgreSQL.");
                }
            }
        }
        return errors;
    }

    private static String syntaxProblem(String sql, int textLine) {
        try {
            CCJSqlParserUtil.parse(sql);
            return null;
        } catch (net.sf.jsqlparser.JSQLParserException ex) {
            // Sin los internos de JavaCC: solo el token y la posición.
            var raw = String.valueOf(ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
            var m = ENCOUNTERED.matcher(raw);
            if (m.find()) {
                return "Encountered " + m.group(1) + " en la línea " + (textLine + Integer.parseInt(m.group(2)) - 1)
                        + ", columna " + m.group(3);
            }
            return "error de sintaxis";
        } catch (RuntimeException | StackOverflowError ex) {
            return null; // el parser se rompió, no la migración: nunca bloquear
        }
    }

    private static final Pattern CREATE_TABLE = Pattern.compile("(?is)^CREATE\\s+[^(]*?\\bTABLE\\b.*");

    /**
     * Revisión final Diego ambientes: {@code CREATE TABLE … AS <consulta>} (también con {@code WITH NO DATA}) no se
     * parsea: JSqlParser lo rechaza siendo válido. Se reconoce por un {@code AS} fuera de paréntesis y de comillas
     * (un {@code GENERATED ALWAYS AS (…)} de una columna queda dentro de los paréntesis de la tabla).
     */
    static boolean createTableAs(String code) {
        if (!CREATE_TABLE.matcher(code).matches()) {
            return false;
        }
        int depth = 0;
        int n = code.length();
        for (int i = 0; i < n; i++) {
            char c = code.charAt(i);
            if (c == '\'' || c == '"') {
                int close = code.indexOf(c, i + 1);
                i = close < 0 ? n : close;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && (c == 'A' || c == 'a') && i + 1 < n
                    && (code.charAt(i + 1) == 'S' || code.charAt(i + 1) == 's')
                    && (i == 0 || !isIdentChar(code.charAt(i - 1)))
                    && (i + 2 >= n || !isIdentChar(code.charAt(i + 2)))) {
                return true;
            }
        }
        return false;
    }

    /** {@code text}: la sentencia tal cual; {@code code}: sin comentarios iniciales; {@code line}: donde empieza el código. */
    record Statement(String text, String code, int line, int textLine, boolean unknownLiteral, String structural) {
    }

    static List<Statement> split(String sql) {
        var out = new ArrayList<Statement>();
        var text = new StringBuilder();
        var code = new StringBuilder();
        var open = new java.util.ArrayDeque<Integer>(); // líneas de los '(' sin cerrar
        int line = 1;
        int codeLine = -1;
        int textLine = -1;
        boolean unknown = false;
        String structural = null;
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            int start = i;
            boolean isCode = true;
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                isCode = false;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
                    if (sql.startsWith("/*", i)) {
                        depth++;
                        i += 2;
                    } else if (sql.startsWith("*/", i)) {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                isCode = false;
            } else if (c == '\'' || c == '"') {
                boolean escapes = c == '\'' && i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
                        && (i < 2 || !isIdentChar(sql.charAt(i - 2)));
                unknown |= escapes;
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (escapes && d == '\\') {
                        i += 2;
                    } else if (d == c) {
                        if (i + 1 < n && sql.charAt(i + 1) == c) {
                            i += 2;
                        } else {
                            i++;
                            closed = true;
                            break;
                        }
                    } else {
                        i++;
                    }
                }
                if (!closed && structural == null) {
                    structural = (c == '"' ? "identificador entre comillas dobles" : "cadena entre comillas simples")
                            + " sin cerrar (abre en la línea " + line + ")";
                    i = Math.min(i, n);
                }
            } else if (c == '$' && (i == 0 || !isIdentChar(sql.charAt(i - 1))) && dollarTagEnd(sql, i) > 0) {
                int tagEnd = dollarTagEnd(sql, i);
                var tag = sql.substring(i, tagEnd);
                int close = sql.indexOf(tag, tagEnd);
                if (close < 0 && structural == null) {
                    structural = "bloque " + tag + " sin cerrar (abre en la línea " + line + ")";
                }
                i = close < 0 ? n : close + tag.length();
                unknown = true;
            } else if (c == ';') {
                i++;
                if (!code.toString().isBlank()) {
                    out.add(finish(text, code, codeLine, textLine, unknown, structural, open));
                }
                text.setLength(0);
                code.setLength(0);
                open.clear();
                codeLine = -1;
                textLine = -1;
                unknown = false;
                structural = null;
                continue;
            } else {
                i++;
                isCode = !Character.isWhitespace(c);
                if (c == '(') {
                    open.push(line);
                } else if (c == ')') {
                    if (open.isEmpty()) {
                        if (structural == null) {
                            structural = "paréntesis ')' sobrante en la línea " + line;
                        }
                    } else {
                        open.pop();
                    }
                }
            }
            var piece = sql.substring(start, i);
            if (isCode && codeLine < 0) {
                codeLine = line;
            }
            if (textLine < 0 && !piece.isBlank()) {
                textLine = line;
            }
            text.append(piece);
            if (isCode || (!code.isEmpty() && !isComment(piece))) {
                code.append(piece);
            } else if (!code.isEmpty()) {
                code.append(' ');
            }
            line += (int) piece.chars().filter(ch -> ch == '\n').count();
        }
        if (!code.toString().isBlank()) {
            out.add(finish(text, code, codeLine, textLine, unknown, structural, open));
        }
        return out;
    }

    private static Statement finish(StringBuilder text, StringBuilder code, int codeLine, int textLine,
                                    boolean unknown, String structural, java.util.Deque<Integer> open) {
        if (structural == null && !open.isEmpty()) {
            structural = "paréntesis '(' sin cerrar (abre en la línea " + open.peekLast() + ")";
        }
        return new Statement(text.toString().strip(), code.toString().strip(), codeLine, textLine, unknown, structural);
    }

    private static boolean isComment(String piece) {
        return piece.startsWith("--") || piece.startsWith("/*");
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Fin de la etiqueta {@code $tag$} que empieza en {@code i}, o -1 si no es una (p. ej. {@code $1}). */
    private static int dollarTagEnd(String sql, int i) {
        int j = i + 1;
        while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
        }
        if (j < sql.length() && sql.charAt(j) == '$'
                && (j == i + 1 || !Character.isDigit(sql.charAt(i + 1)))) {
            return j + 1;
        }
        return -1;
    }
}
