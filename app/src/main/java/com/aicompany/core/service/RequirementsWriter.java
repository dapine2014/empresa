package com.aicompany.core.service;

import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.agent.validation.RequirementsSpecValidator;
import com.aicompany.core.event.CompanyEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Optional;

/**
 * Spec 2026-10-03 Aria §1-§2: Aria (Product Owner) convierte el pedido en requisitos; Java los valida
 * (RequirementsSpecValidator) y ella corrige con el motivo exacto, hasta 3 intentos. Cada spec válido es una versión.
 */
@Service
public class RequirementsWriter {

    private static final Logger log = LoggerFactory.getLogger(RequirementsWriter.class);
    public static final String ARIA = "product-owner";
    static final int MAX_ATTEMPTS = 3;

    public record Written(int version, RequirementsSpec spec) {
    }

    private final CeoService ceo;
    private final MissionMemoryService missions;
    private final RequirementsMemoryService requirements;
    private final CompanyMemoryService company;
    private final PromptMemoryService prompts;
    private final CompanyEventPublisher events;
    private final JsonMapper json;
    private final String defaultModel;

    public RequirementsWriter(CeoService ceo, MissionMemoryService missions, RequirementsMemoryService requirements,
                              CompanyMemoryService company, PromptMemoryService prompts, CompanyEventPublisher events,
                              JsonMapper json, @Value("${ollama.agent-model}") String defaultModel) {
        this.ceo = ceo;
        this.missions = missions;
        this.requirements = requirements;
        this.company = company;
        this.prompts = prompts;
        this.events = events;
        this.json = json;
        this.defaultModel = defaultModel;
    }

    public Written write(String missionId, String instruction, Optional<RequirementsSpec> previous, String adjustment) {
        var nextVersion = requirements.latest(missionId).map(v -> v.version() + 1).orElse(1);
        var taskId = missionId + "-REQUIREMENTS" + (nextVersion > 1 ? "-V" + nextVersion : "");
        missions.createTask(taskId, missionId, ARIA, "REQUIREMENTS_SPEC", "REQUIREMENTS");
        missions.updateTask(taskId, "RUNNING", "Aria está escribiendo los requisitos.");
        missions.setAgentStatus(ARIA, "WORKING");
        try {
            var model = company.agentModel(ARIA, defaultModel);
            var agentPrompt = prompts.activePrompt(ARIA);
            var base = prompt(instruction, previous, adjustment);
            String feedback = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                var prompt = feedback == null ? base : base + """

                        CORRECCIÓN DEL INTENTO ANTERIOR (Java rechazó el spec por esto; corrígelo todo):
                        %s
                        """.formatted(feedback);
                RequirementsSpec spec;
                try {
                    spec = ceo.writeRequirements(ARIA, prompt, agentPrompt, model);
                } catch (Exception ex) {
                    feedback = "- " + (ex.getMessage() == null ? "Respuesta no procesable." : ex.getMessage());
                    continue;
                }
                var errors = RequirementsSpecValidator.validate(spec);
                if (errors.isEmpty()) {
                    var specJson = json.writeValueAsString(spec);
                    var version = requirements.saveDraft(missionId, specJson, adjustment);
                    missions.updateTask(taskId, "COMPLETED", specJson);
                    log.info("MISSION {} - requirements v{} accepted stories={}", missionId, version,
                            spec.userStoriesOrEmpty().size());
                    return new Written(version, spec);
                }
                feedback = "- " + String.join("\n- ", errors);
                log.warn("MISSION {} - requirements rejected attempt={} errors={}", missionId, attempt, feedback);
            }
            var message = "Aria no produjo requisitos válidos después de " + MAX_ATTEMPTS + " intentos:\n" + feedback;
            missions.updateTask(taskId, "FAILED", message);
            throw new IllegalStateException(message);
        } finally {
            missions.setAgentStatus(ARIA, "IDLE");
        }
    }

    private String prompt(String instruction, Optional<RequirementsSpec> previous, String adjustment) {
        var base = """
                Eres Aria, Product Owner del Development Group de Forjai. Decides QUÉ se construye, nunca CÓMO
                (tecnología, arquitectura y código son del equipo). Convierte el pedido del fundador en requisitos.

                PEDIDO DEL FUNDADOR:
                %s

                REGLAS:
                - roles: quién usa el producto (p. ej. comprador, administrador, paciente, jugador, sistema externo),
                  cada uno con name y description. Cada historia usa uno de esos roles en "role".
                - userStories: HU-01, HU-02… con iWant, soThat, acceptanceCriteria (verificables) y scenarios ESC-01,
                  ESC-02… (ids únicos en todo el spec) con given/when/then concretos: cada escenario será un test.
                - increments: el 1 es el MVP (lo mínimo que funciona de punta a punta), los siguientes ordenan el resto;
                  cada historia en exactamente un incremento; máximo 12 escenarios por incremento; una historia solo
                  depende (dependsOn) de historias de su incremento o de uno anterior.
                - assumptions: cada ambigüedad del pedido y cómo la resolviste. outOfScope: lo que dejas afuera a
                  propósito. No inventes requisitos: lo que agregues va como supuesto.
                - Español, frases cortas.
                """.formatted(instruction);
        if (previous.isEmpty()) {
            return base;
        }
        return base + """

                VERSIÓN ANTERIOR (el fundador pidió un ajuste; conserva lo que no contradiga el ajuste):
                %s

                AJUSTE DEL FUNDADOR:
                %s
                """.formatted(json.writeValueAsString(previous.get()), adjustment == null ? "" : adjustment);
    }
}
