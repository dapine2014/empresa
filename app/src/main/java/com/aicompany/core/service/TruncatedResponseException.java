package com.aicompany.core.service;

/**
 * La respuesta del modelo se cortó (límite de tokens de salida): el proveedor marcó "length" o el JSON terminó
 * a mitad. Spec 2026-10-01 §5: quien llama pide la continuación en lotes más chicos en vez de descartarla.
 */
public class TruncatedResponseException extends IllegalStateException {

    public TruncatedResponseException(String message) {
        super(message);
    }
}
