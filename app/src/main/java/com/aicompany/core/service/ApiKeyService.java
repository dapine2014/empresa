package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Keys de modelos editables desde Settings (2026-09-29, decisión del fundador). El .env siembra la key de cada
 * proveedor; la que se guarda desde la pantalla gana: se prueba contra NVIDIA antes de guardarla, se guarda cifrada
 * ({@link SecretCipher}) y se aplica en caliente al cliente del proveedor. Nunca se devuelve ni se loguea una key.
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    /** Si ningún agente del proveedor tiene modelo remoto, se prueba con estos (disponibles en NVIDIA). */
    static final List<String> DEFAULT_CHECK_MODELS = List.of("nvidia/nemotron-3-ultra-550b-a55b", "z-ai/glm-5.3");
    static final int MAX_CHECKS = 3;

    public record ApiKeyStatus(String provider, String hint, String source, String updatedAt, String updatedBy,
                               List<String> agents) {
    }

    private final Map<String, OpenAiCompatibleClient> remotes;
    private final ApiKeyMemoryService memory;
    private final SecretCipher cipher;
    private final CompanyEventPublisher events;
    private final CompanyMemoryService companyMemory;

    public ApiKeyService(Map<String, OpenAiCompatibleClient> remotes, ApiKeyMemoryService memory, SecretCipher cipher,
                         CompanyEventPublisher events, CompanyMemoryService companyMemory) {
        this.remotes = remotes;
        this.memory = memory;
        this.cipher = cipher;
        this.events = events;
        this.companyMemory = companyMemory;
    }

    /** Al arrancar, las keys guardadas reemplazan a las del .env; una ilegible deja la del .env y solo se registra. */
    @EventListener(ApplicationReadyEvent.class)
    public void applyStoredKeys() {
        if (!cipher.available()) {
            return;
        }
        for (var stored : memory.all()) {
            var client = remotes.get(stored.provider());
            if (client == null) {
                continue;
            }
            try {
                client.setApiKey(cipher.decrypt(stored.cipherText()));
            } catch (Exception ex) {
                log.warn("API_KEY provider={} stored key unreadable, keeping the .env one: {}", stored.provider(), ex.getMessage());
            }
        }
    }

    public List<ApiKeyStatus> list() {
        var stored = memory.all().stream().collect(Collectors.toMap(ApiKeyMemoryService.StoredApiKey::provider, k -> k));
        var agents = companyMemory.agents();
        var out = new ArrayList<ApiKeyStatus>();
        for (var entry : new java.util.TreeMap<>(remotes).entrySet()) {
            var provider = entry.getKey();
            var hint = entry.getValue().keyHint();
            var saved = stored.get(provider);
            var source = saved != null ? "FOUNDER" : hint.isBlank() ? "NONE" : "ENV";
            var users = agents.stream().filter(a -> String.valueOf(a.get("model")).startsWith(provider + ":"))
                    .map(a -> String.valueOf(a.get("name"))).toList();
            out.add(new ApiKeyStatus(provider, hint, source, saved == null ? null : saved.updatedAt().toString(),
                    saved == null ? null : saved.updatedBy(), users));
        }
        return out;
    }

    public ApiKeyStatus update(String provider, String apiKey, String actor) {
        var client = remotes.get(provider);
        if (client == null) {
            throw new IllegalArgumentException("Proveedor desconocido: " + provider + ". Proveedores: " + new java.util.TreeSet<>(remotes.keySet()) + ".");
        }
        var key = apiKey == null ? "" : apiKey.strip();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("La key no puede estar vacía.");
        }
        if (!cipher.available()) {
            throw new IllegalStateException("Falta FORJAI_SECRETS_KEY en .env: sin la llave maestra no se guardan keys.");
        }
        var models = checkModels(provider);
        var answered = false;
        for (var model : models) {
            var check = client.checkKey(model, key);
            if (check == OpenAiCompatibleClient.KeyCheck.UNAUTHORIZED) {
                throw new IllegalArgumentException("NVIDIA rechazó la key de " + provider + " (401/403): no se guardó y sigue la anterior.");
            }
            if (check == OpenAiCompatibleClient.KeyCheck.OK) {
                answered = true;
                break;
            }
        }
        if (!answered) {
            throw new IllegalArgumentException("No se pudo comprobar la key de " + provider + ": ningún modelo respondió ("
                    + String.join(", ", models) + "). No se guardó; intenta de nuevo en unos minutos.");
        }
        memory.save(provider, cipher.encrypt(key), actor);
        client.setApiKey(key);
        log.info("API_KEY_UPDATED provider={} actor={}", provider, actor);
        events.publish("EMPRESA_API_KEY_UPDATED", null, null, actor, Map.of("provider", provider));
        return list().stream().filter(k -> k.provider().equals(provider)).findFirst().orElseThrow();
    }

    /** Modelos de los agentes que usan el proveedor (principal y suplente), luego los de respaldo; máximo MAX_CHECKS. */
    private List<String> checkModels(String provider) {
        var models = new LinkedHashSet<String>();
        for (var agent : companyMemory.agents()) {
            for (var field : List.of("model", "fallbackModel")) {
                var value = String.valueOf(agent.get(field));
                if (value.startsWith(provider + ":")) {
                    models.add(value.substring(provider.length() + 1));
                }
            }
        }
        models.addAll(DEFAULT_CHECK_MODELS);
        return models.stream().limit(MAX_CHECKS).toList();
    }
}
