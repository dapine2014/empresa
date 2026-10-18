package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Cadena de modelos gratis (decisión del fundador 2026-10-15: "primero validar con los modelos gratuitos y pasar
 * automáticamente a Bedrock si los modelos gratis fallan o están caídos"; una sola cadena global). Un agente cuyo
 * modelo principal está en la cadena, si ese modelo cae, prueba los demás en orden antes del respaldo pago y del
 * suplente local. La env var solo siembra; después manda lo que el fundador guarda en Settings.
 */
@Service
public class FreeModelChainService {

    private static final Logger log = LoggerFactory.getLogger(FreeModelChainService.class);

    public record FreeModelChain(List<String> models, String updatedAt, String updatedBy) {
    }

    private final FreeModelChainMemoryService memory;
    private final Map<String, OpenAiCompatibleClient> remotes;
    private final CompanyEventPublisher events;
    private final List<String> seed;

    public FreeModelChainService(FreeModelChainMemoryService memory, Map<String, OpenAiCompatibleClient> remotes,
                                 CompanyEventPublisher events,
                                 @Value("${forjai.free-model-chain:}") String seed) {
        this.memory = memory;
        this.remotes = remotes;
        this.events = events;
        this.seed = parse(seed);
    }

    static List<String> parse(String csv) {
        return csv == null ? List.of() : Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .distinct().toList();
    }

    public FreeModelChain chain() {
        try {
            return memory.load().map(c -> new FreeModelChain(c.models(), c.updatedAt().toString(), c.updatedBy()))
                    .orElseGet(() -> new FreeModelChain(seed, null, null));
        } catch (RuntimeException ex) {
            // Sin Neo4j la llamada al modelo no se cae por esto: sigue con la semilla.
            log.warn("FREE_CHAIN unreadable, using the seed: {}", ex.getMessage());
            return new FreeModelChain(seed, null, null);
        }
    }

    public List<String> models() {
        return chain().models();
    }

    /** Los otros modelos de la cadena, en orden, si {@code model} está en ella; si no, ninguno. */
    public List<String> alternativesFor(String model) {
        var models = models();
        if (!models.contains(model)) {
            return List.of();
        }
        return models.stream().filter(m -> !m.equals(model)).toList();
    }

    public FreeModelChain update(List<String> requested, String actor) {
        if (requested == null) {
            throw new IllegalArgumentException("Falta la lista de modelos gratis.");
        }
        var models = new LinkedHashSet<String>();
        for (var raw : requested) {
            var model = raw == null ? "" : raw.strip();
            if (model.isEmpty()) {
                continue;
            }
            var remote = CeoService.remoteModel(model).orElseThrow(() -> new IllegalArgumentException(
                    "\"" + model + "\" no es un modelo remoto: se escribe <proveedor>:<modelo>, p. ej. nvidia:z-ai/glm-5.3."));
            if (!remotes.containsKey(remote.provider())) {
                throw new IllegalArgumentException("Proveedor desconocido en \"" + model + "\". Proveedores: "
                        + new java.util.TreeSet<>(remotes.keySet()) + ".");
            }
            if ("bedrock".equals(remote.provider())) {
                throw new IllegalArgumentException("\"" + model + "\" es de pago (Bedrock): va como respaldo pago del "
                        + "agente, no en la cadena de modelos gratis.");
            }
            models.add(model);
        }
        var saved = new ArrayList<>(models);
        memory.save(saved, actor);
        log.info("FREE_CHAIN_UPDATED actor={} models={}", actor, saved);
        events.publish("EMPRESA_FREE_MODEL_CHAIN_UPDATED", null, null, actor, Map.of("models", saved));
        return chain();
    }
}
