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

    private static final Pattern SUPPORTED = Pattern.compile(
            "(?is)^(CREATE\\s+(UNIQUE\\s+)?INDEX\\b"
                    + "|CREATE\\s+((GLOBAL\\s+|LOCAL\\s+)?(TEMP|TEMPORARY)\\s+|UNLOGGED\\s+)?TABLE\\b"
                    + "|ALTER\\s+TABLE\\b|INSERT\\s+INTO\\b|UPDATE\\b|DELETE\\s+FROM\\b|DROP\\s+(TABLE|INDEX)\\b).*");

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
                // Cadenas $tag$ y E'…\\…' dentro de una sentencia son válidas en PostgreSQL y JSqlParser no las conoce: no se analiza.
                if (statement.unknownLiteral() || !SUPPORTED.matcher(statement.code()).matches()) {
                    continue;
                }
                var problem = syntaxProblem(statement.text());
                if (problem != null) {
                    errors.add(file.path() + ", sentencia " + (i + 1) + " (línea " + statement.line() + "): " + problem
                            + ". Corrige el SQL de PostgreSQL.");
                }
            }
        }
        return errors;
    }

    private static String syntaxProblem(String sql) {
        try {
            CCJSqlParserUtil.parse(sql);
            return null;
        } catch (net.sf.jsqlparser.JSQLParserException ex) {
            var cause = ex.getCause() != null ? ex.getCause() : ex;
            var message = cause.getMessage() == null ? "error de sintaxis" : cause.getMessage();
            message = message.replaceFirst("^[\\w.$]+Exception: ", "").replaceAll("\\s+", " ").trim();
            return message.length() > 300 ? message.substring(0, 300) + "…" : message;
        } catch (RuntimeException | StackOverflowError ex) {
            return null; // el parser se rompió, no la migración: nunca bloquear
        }
    }

    /** {@code text}: la sentencia tal cual; {@code code}: sin comentarios iniciales; {@code line}: donde empieza el código. */
    record Statement(String text, String code, int line, boolean unknownLiteral) {
    }

    static List<Statement> split(String sql) {
        var out = new ArrayList<Statement>();
        var text = new StringBuilder();
        var code = new StringBuilder();
        int line = 1;
        int codeLine = -1;
        boolean unknown = false;
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
                while (i < n) {
                    char d = sql.charAt(i);
                    if (escapes && d == '\\') {
                        i += 2;
                    } else if (d == c) {
                        if (i + 1 < n && sql.charAt(i + 1) == c) {
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        i++;
                    }
                }
            } else if (c == '$' && (i == 0 || !isIdentChar(sql.charAt(i - 1))) && dollarTagEnd(sql, i) > 0) {
                int tagEnd = dollarTagEnd(sql, i);
                var tag = sql.substring(i, tagEnd);
                int close = sql.indexOf(tag, tagEnd);
                i = close < 0 ? n : close + tag.length();
                unknown = true;
            } else if (c == ';') {
                i++;
                if (!code.toString().isBlank()) {
                    out.add(new Statement(text.toString().strip(), code.toString().strip(), codeLine, unknown));
                }
                text.setLength(0);
                code.setLength(0);
                codeLine = -1;
                unknown = false;
                continue;
            } else {
                i++;
                isCode = !Character.isWhitespace(c);
            }
            var piece = sql.substring(start, i);
            if (isCode && codeLine < 0) {
                codeLine = line;
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
            out.add(new Statement(text.toString().strip(), code.toString().strip(), codeLine, unknown));
        }
        return out;
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
