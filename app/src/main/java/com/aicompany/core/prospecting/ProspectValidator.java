package com.aicompany.core.prospecting;

import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Spec búsqueda de prospectos §1: un prospecto vale solo si su nombre aparece en su propia página y tiene un contacto
 * público verificable (email literalmente en la página que lo cita, o formulario que responde), sin repetir dominio.
 * Lección de la rama del 18-sep: los agentes devolvían segmentos de mercado con nombre y una fuente genérica.
 */
public final class ProspectValidator {

    public record Result(List<ProspectCandidate> valid, List<String> rejections) {
    }

    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");

    /**
     * Revisión final: redes sociales, foros, directorios y marketplaces no son la página propia de una empresa; guardarlos
     * además bloquearía su dominio para todo prospecto futuro. Sirven como fuente del contacto, no como {@code url}.
     */
    private static final Set<String> NOT_OWN_PAGE = Set.of("reddit.com", "facebook.com", "linkedin.com",
            "instagram.com", "x.com", "twitter.com", "youtube.com", "tiktok.com", "medium.com", "quora.com",
            "clutch.co", "g2.com", "capterra.com", "trustpilot.com", "yelp.com", "producthunt.com", "crunchbase.com",
            "wikipedia.org", "google.com", "upwork.com", "fiverr.com", "amazon.com", "github.com", "substack.com");

    private final Function<String, String> fetch;

    public ProspectValidator(Function<String, String> fetch) {
        this.fetch = fetch;
    }

    public Result validate(List<ProspectCandidate> candidates, Set<String> knownDomains) {
        var valid = new ArrayList<ProspectCandidate>();
        var rejections = new ArrayList<String>();
        var seen = new HashSet<>(knownDomains);
        for (var c : candidates) {
            var problem = problem(c, seen);
            if (problem == null) {
                valid.add(verifiedEmail(c) ? c : new ProspectCandidate(c.name(), c.url(), null, c.contactFormUrl(),
                        null, c.fitReason()));
                seen.add(domain(c.url()));
            } else {
                rejections.add((blank(c.name()) ? "(sin nombre)" : c.name()) + ": " + problem);
            }
        }
        return new Result(valid, rejections);
    }

    private String problem(ProspectCandidate c, Set<String> seen) {
        if (blank(c.name()) || blank(c.url()) || blank(c.fitReason())) {
            return "faltan nombre, URL o por qué le sirve el producto";
        }
        var domain = domain(c.url());
        if (!http(c.url()) || domain == null) {
            return "la URL no es http(s)";
        }
        if (NOT_OWN_PAGE.stream().anyMatch(d -> domain.equals(d) || domain.endsWith("." + d))) {
            return "no es su página propia (" + domain + " es un sitio de terceros)";
        }
        if (seen.contains(domain)) {
            return "repetido (" + domain + " ya fue prospectado)";
        }
        var page = page(c.url());
        if (page == null) {
            return "su página no responde";
        }
        if (!normalize(page).contains(normalize(c.name()))) {
            return "el nombre no aparece en su página";
        }
        if (verifiedEmail(c)) {
            return null;
        }
        // Sin email verificable (vacío, "N/A" o ausente de su fuente) vale el formulario que responde; el email no se guarda.
        if (http(c.contactFormUrl()) && page(c.contactFormUrl()) != null) {
            return null;
        }
        return blank(c.contactEmail())
                ? "sin contacto verificable (ni email ni formulario)"
                : "contacto no verificable (el email no aparece en su fuente y no hay formulario que responda)";
    }

    private boolean verifiedEmail(ProspectCandidate c) {
        if (blank(c.contactEmail()) || !http(c.contactSourceUrl())) {
            return false;
        }
        var email = c.contactEmail().strip();
        if (!EMAIL.matcher(email).matches()) {
            return false;
        }
        var source = page(c.contactSourceUrl());
        return source != null && source.toLowerCase(Locale.ROOT).contains(email.toLowerCase(Locale.ROOT));
    }

    private String page(String url) {
        try {
            var text = fetch.apply(url);
            return text == null || text.isBlank() ? null : text;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static String domain(String url) {
        try {
            var host = URI.create(url.strip()).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean http(String url) {
        return url != null && (url.strip().startsWith("http://") || url.strip().startsWith("https://"));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("\\s+", " ").strip();
    }
}
