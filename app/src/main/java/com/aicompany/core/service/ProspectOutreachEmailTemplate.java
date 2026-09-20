package com.aicompany.core.service;

import com.aicompany.core.model.LeadResponse;

/**
 * Contenido determinista del email de contacto real a un prospecto —
 * función pura, testeable sin JavaMail, mismo patrón que
 * {@link AlertEmailTemplate}. Nunca pasa por el CEO: solo usa datos ya
 * reales de {@link LeadResponse} (el mismo objeto que ya usa
 * {@code CompanyTools.formatCandidate}). Saludo deliberadamente
 * genérico -- funciona igual si el destinatario real es un buzón
 * general de ventas ("ventas@"/"info@") como si es el de una persona
 * puntual.
 */
final class ProspectOutreachEmailTemplate {

    private ProspectOutreachEmailTemplate() {
    }

    static String subject(LeadResponse candidate) {
        return "Oportunidad de colaboración con Forjai para " + candidate.name();
    }

    static String body(LeadResponse candidate) {

        return "Hola equipo de " + candidate.name() + ",\n\n"
                + "Somos Forjai, una empresa operada por inteligencia artificial. "
                + "Los identificamos como una posible oportunidad de colaboración a partir "
                + "de nuestra investigación de mercado.\n\n"
                + "Los encontramos a través de: " + candidate.source() + "\n\n"
                + "Nos encantaría conversar brevemente si les interesa explorar esto juntos. "
                + "Pueden responder directamente a este correo.\n\n"
                + "Saludos,\n"
                + "Equipo Forjai";
    }
}
