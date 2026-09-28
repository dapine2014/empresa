package com.aicompany.core.service;

/**
 * El modelo remoto no está disponible (spec salud de modelos 2026-09-28): timeout o conexión, 5xx/429 tras los
 * reintentos, respuesta sin choices o cuerpo ilegible. Un 4xx distinto de 429 NO es esto (es un error del pedido).
 */
public class RemoteUnavailableException extends IllegalStateException {

    public RemoteUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
