package com.aicompany.core.outreach;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Spec contacto con prospectos §1 (función pura): el borrador de Sofía nombra el producto exacto, cita solo el precio de
 * la ficha, no trae URLs ni emails ajenos y respeta los largos. La línea de baja y la firma las agrega Java.
 */
public final class OutreachDraftValidator {

    public static final String OPT_OUT_LINE = "Si no te interesa, responde «no» y no volveremos a escribirte.";
    static final int MAX_SUBJECT = 120;
    static final int MAX_BODY = 1200;

    public record Result(OutreachDraft draft, List<String> problems) {
    }

    private static final Pattern AMOUNT = Pattern.compile(
            "(?:US\\$|\\$)\\s?(\\d+(?:[.,]\\d{1,2})?)|(\\d+(?:[.,]\\d{1,2})?)\\s?(?:USD|US\\$|dólares|dolares)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("https?://[^\\s)>\\]]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");

    private OutreachDraftValidator() {
    }

    public static Result validate(OutreachDraft draft, String productName, Double priceUsd, boolean priceOnRequest,
                                  String signature, Set<String> allowedUrls, String replyTo) {
        var problems = new ArrayList<String>();
        var subject = draft == null || draft.subject() == null ? "" : draft.subject().strip();
        var body = draft == null || draft.body() == null ? "" : draft.body().strip();
        if (subject.isEmpty() || subject.length() > MAX_SUBJECT) {
            problems.add("El asunto debe tener entre 1 y " + MAX_SUBJECT + " caracteres.");
        }
        if (body.isEmpty() || body.length() > MAX_BODY) {
            problems.add("El cuerpo debe tener entre 1 y " + MAX_BODY + " caracteres (sin contar baja y firma).");
        }
        if (!body.toLowerCase(Locale.ROOT).contains(productName.toLowerCase(Locale.ROOT))) {
            problems.add("Debe mencionar el nombre exacto del producto: \"" + productName + "\".");
        }
        var amounts = AMOUNT.matcher(subject + "\n" + body).results()
                .map(m -> new BigDecimal((m.group(1) != null ? m.group(1) : m.group(2)).replace(',', '.')))
                .toList();
        if (priceOnRequest || priceUsd == null) {
            if (!amounts.isEmpty()) {
                problems.add("El producto es a cotizar: no menciones montos.");
            }
        } else {
            var price = BigDecimal.valueOf(priceUsd);
            if (amounts.isEmpty()) {
                problems.add("Debe mencionar el precio exacto: US$" + price.stripTrailingZeros().toPlainString() + ".");
            } else if (amounts.stream().anyMatch(a -> a.compareTo(price) != 0)) {
                problems.add("El único monto permitido es el precio de la ficha: US$"
                        + price.stripTrailingZeros().toPlainString() + ".");
            }
        }
        URL.matcher(body).results().map(m -> m.group().replaceAll("[.,;]+$", ""))
                .filter(u -> allowedUrls.stream().noneMatch(u::startsWith))
                .findFirst().ifPresent(u -> problems.add("No incluyas URLs ajenas (" + u + ")."));
        EMAIL.matcher(body).results().map(m -> m.group())
                .filter(e -> !e.equalsIgnoreCase(replyTo))
                .findFirst().ifPresent(e -> problems.add("No incluyas otro email que no sea el de respuesta (" + e + ")."));
        if (!problems.isEmpty()) {
            return new Result(null, problems);
        }
        var full = body;
        if (!full.contains(OPT_OUT_LINE)) {
            full += "\n\n" + OPT_OUT_LINE;
        }
        if (!full.strip().endsWith(signature.strip())) {
            full += "\n\n" + signature.strip();
        }
        return new Result(new OutreachDraft(subject, full), List.of());
    }
}
