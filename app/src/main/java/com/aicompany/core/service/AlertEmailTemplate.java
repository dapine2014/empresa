package com.aicompany.core.service;

/**
 * Plantilla HTML simple y profesional para los correos de {@link AlertMailService}
 * — función pura, testeable sin JavaMail. Sin logo embebido (la mayoría de
 * clientes de correo bloquean o rompen imágenes referenciadas), solo un
 * wordmark de texto ("FORJAI") consistente con el sidebar del Command
 * Center web.
 */
final class AlertEmailTemplate {

    private static final String INFO_COLOR = "#4f8dfd";
    private static final String CRITICAL_COLOR = "#dc2626";
    private static final String BACKGROUND = "#0b0f14";
    private static final String TEXT = "#e5e9f0";
    private static final String MUTED = "#8b98a8";

    private AlertEmailTemplate() {
    }

    static String html(String subject, String body, boolean critical) {
        return html(subject, body, critical, "Alerta automática — Command Center");
    }

    static String html(String subject, String body, boolean critical, String footer) {
        var accent = critical ? CRITICAL_COLOR : INFO_COLOR;

        return "<!doctype html>"
                + "<html><head><meta charset=\"UTF-8\"></head>"
                + "<body style=\"margin:0;padding:24px;background:" + BACKGROUND
                + ";font-family:'Segoe UI',system-ui,sans-serif;\">"
                + "<div style=\"max-width:560px;margin:0 auto;border-radius:8px;overflow:hidden;"
                + "border:1px solid #232c38;\">"
                + "<div style=\"background:" + accent + ";padding:14px 20px;\">"
                + "<span style=\"color:#ffffff;font-weight:700;letter-spacing:0.05em;\">FORJAI</span>"
                + "</div>"
                + "<div style=\"background:#121821;padding:24px;\">"
                + "<h2 style=\"margin:0 0 16px;color:" + TEXT + ";font-size:18px;\">"
                + escape(subject) + "</h2>"
                + "<p style=\"margin:0;color:" + TEXT + ";font-size:14px;line-height:1.6;"
                + "white-space:normal;\">" + escape(body).replace("\n", "<br>") + "</p>"
                + "</div>"
                + "<div style=\"background:#121821;padding:12px 24px;border-top:1px solid #232c38;\">"
                + "<span style=\"color:" + MUTED + ";font-size:12px;\">"
                + escape(footer) + "</span>"
                + "</div>"
                + "</div>"
                + "</body></html>";
    }

    private static String escape(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
