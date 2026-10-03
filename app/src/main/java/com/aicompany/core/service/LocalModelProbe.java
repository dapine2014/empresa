package com.aicompany.core.service;

import java.util.Optional;
import java.util.Set;

/** Modelos instalados en Ollama, o vacío si Ollama no responde (decisión del fundador 2026-10-03: vigilar suplentes). */
@FunctionalInterface
public interface LocalModelProbe {
    Optional<Set<String>> installedModels();
}
