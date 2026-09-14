package com.aicompany.core.model;

/**
 * Nunca incluye la clave del correo del sistema (`Company.mailPassword`) —
 * solo se escribe, nunca se lee por API.
 */
public record SettingsResponse(
        String alertEmail,
        String systemEmail
) {
}
