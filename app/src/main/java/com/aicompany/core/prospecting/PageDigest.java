package com.aicompany.core.prospecting;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Verificado en vivo (2026-09-30, primera búsqueda real): Sofía solo recibía título y fragmento de cada página y, sin
 * emails a la vista, devolvía una lista vacía. Java le entrega el texto de la página (sin HTML, acotado) y los contactos
 * que encuentra en ella; la validez de cada prospecto la sigue decidiendo ProspectValidator.
 */
public record PageDigest(String excerpt, List<String> emails, List<String> contactLinks) {

    public static final int MAX_EXCERPT = 3000;
    static final int MAX_CONTACT_LINKS = 10;

    private static final Pattern HIDDEN = Pattern.compile("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)*\\.[A-Za-z]{2,}");
    private static final Pattern HREF = Pattern.compile("(?i)<a\\s[^>]*href\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern CONTACT = Pattern.compile("(?i)(contact|contacto|about|nosotros|team|equipo)");

    public static PageDigest of(String html, String baseUrl) {
        var visible = HIDDEN.matcher(html == null ? "" : html).replaceAll(" ");
        var emails = new LinkedHashSet<String>();
        var links = new LinkedHashSet<String>();
        var href = HREF.matcher(visible);
        while (href.find()) {
            var target = href.group(1).strip();
            if (target.toLowerCase(Locale.ROOT).startsWith("mailto:")) {
                var email = target.substring(7).split("\\?")[0].strip();
                if (EMAIL.matcher(email).matches()) {
                    emails.add(email.toLowerCase(Locale.ROOT));
                }
            } else if (CONTACT.matcher(target).find() && links.size() < MAX_CONTACT_LINKS) {
                absolute(baseUrl, target).ifPresent(links::add);
            }
        }
        var text = unescape(TAG.matcher(visible).replaceAll(" ")).replaceAll("\\s+", " ").strip();
        var found = EMAIL.matcher(text);
        while (found.find()) {
            var email = found.group().toLowerCase(Locale.ROOT);
            if (!email.matches(".*\\.(png|jpe?g|gif|svg|webp)$")) {
                emails.add(email);
            }
        }
        var excerpt = text.length() <= MAX_EXCERPT ? text : text.substring(0, MAX_EXCERPT);
        return new PageDigest(excerpt, new ArrayList<>(emails), new ArrayList<>(links));
    }

    private static java.util.Optional<String> absolute(String baseUrl, String target) {
        try {
            var uri = URI.create(baseUrl).resolve(target);
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return scheme.equals("http") || scheme.equals("https") ? java.util.Optional.of(uri.toString())
                    : java.util.Optional.empty();
        } catch (RuntimeException ex) {
            return java.util.Optional.empty();
        }
    }

    private static String unescape(String text) {
        return text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'");
    }
}
