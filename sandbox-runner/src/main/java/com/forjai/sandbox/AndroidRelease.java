package com.forjai.sandbox;

/** Clave de publicación de Android (solo en el último incremento). toString nunca expone el keystore ni la contraseña. */
public record AndroidRelease(String keystoreBase64, String alias, String password) {
    @Override public String toString() { return "AndroidRelease[" + alias + "]"; }
}
