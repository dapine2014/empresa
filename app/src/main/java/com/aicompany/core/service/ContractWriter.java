package com.aicompany.core.service;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.validation.ContractValidator;
import com.aicompany.core.event.CompanyEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * Contrato (spec 2026-10-16 contrato §1): Aria levanta el contrato con el pedido del cliente (hoy, el fundador); Java lo
 * valida (ContractValidator) y ella corrige con el motivo exacto, hasta 3 intentos. Cada contrato válido es una versión.
 */
@Service
public class ContractWriter {

    private static final Logger log = LoggerFactory.getLogger(ContractWriter.class);
    public static final String ARIA = RequirementsWriter.ARIA;
    static final int MAX_ATTEMPTS = 3;

    public record Written(int version, ClientContract contract) {
    }

    private final CeoService ceo;
    private final MissionMemoryService missions;
    private final ContractMemoryService contracts;
    private final CompanyMemoryService company;
    private final PromptMemoryService prompts;
    private final CompanyEventPublisher events;
    private final JsonMapper json;
    private final String defaultModel;

    public ContractWriter(CeoService ceo, MissionMemoryService missions, ContractMemoryService contracts,
                          CompanyMemoryService company, PromptMemoryService prompts, CompanyEventPublisher events,
                          JsonMapper json, @Value("${ollama.agent-model}") String defaultModel) {
        this.ceo = ceo;
        this.missions = missions;
        this.contracts = contracts;
        this.company = company;
        this.prompts = prompts;
        this.events = events;
        this.json = json;
        this.defaultModel = defaultModel;
    }

    public Written write(String missionId, String instruction, Optional<ClientContract> previous, String adjustment) {
        var nextVersion = contracts.latest(missionId).map(v -> v.version() + 1).orElse(1);
        var taskId = missionId + "-CONTRACT" + (nextVersion > 1 ? "-V" + nextVersion : "");
        missions.createTask(taskId, missionId, ARIA, "CLIENT_CONTRACT", "REQUIREMENTS");
        missions.updateTask(taskId, "RUNNING", "Aria está levantando el contrato.");
        missions.setAgentStatus(ARIA, "WORKING");
        try {
            var model = company.agentModel(ARIA, defaultModel);
            var agentPrompt = prompts.activePrompt(ARIA);
            var base = prompt(instruction, previous, adjustment);
            String feedback = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                var prompt = feedback == null ? base : base + """

                        CORRECCIÓN DEL INTENTO ANTERIOR (Java rechazó el contrato por esto; corrígelo todo):
                        %s
                        """.formatted(feedback);
                ClientContract contract;
                try {
                    contract = ceo.writeContract(ARIA, prompt, agentPrompt, model);
                } catch (MissionCancellations.MissionCancelledException ex) {
                    throw ex; // cancelar la misión no es una respuesta inválida: no se reintenta.
                } catch (Exception ex) {
                    feedback = "- " + (ex.getMessage() == null ? "Respuesta no procesable." : ex.getMessage());
                    continue;
                }
                var errors = ContractValidator.validate(contract);
                if (errors.isEmpty()) {
                    var contractJson = json.writeValueAsString(contract);
                    var version = contracts.saveDraft(missionId, contractJson, adjustment);
                    missions.updateTask(taskId, "COMPLETED", contractJson);
                    log.info("MISSION {} - contract v{} accepted items={}", missionId, version,
                            contract.itemsOrEmpty().size());
                    return new Written(version, contract);
                }
                feedback = "- " + String.join("\n- ", errors);
                log.warn("MISSION {} - contract rejected attempt={} errors={}", missionId, attempt, feedback);
            }
            var message = "Aria no produjo un contrato válido después de " + MAX_ATTEMPTS + " intentos:\n" + feedback;
            missions.updateTask(taskId, "FAILED", message);
            throw new IllegalStateException(message);
        } finally {
            missions.setAgentStatus(ARIA, "IDLE");
        }
    }

    private String prompt(String instruction, Optional<ClientContract> previous, String adjustment) {
        var base = """
                Eres Aria, Product Owner del Development Group de Forjai. Antes de escribir historias levantas el CONTRATO
                con el cliente: su solicitud, sin detalle técnico (el detalle va después, en las historias de usuario,
                que saldrán de este contrato). Decides QUÉ se pide, nunca CÓMO se construye.

                PEDIDO DEL CLIENTE (hoy, el fundador):
                %s

                REGLAS:
                - summary: qué pide el cliente, en 2 o 3 frases.
                - experience: GAME si es un juego; APP si las personas lo usan con pantallas; NONE solo si no tiene
                  ninguna interfaz (API o servicio).
                - items: lo que pidió el cliente, una línea cada uno: MOD-01, MOD-02… en orden (máximo 10). kind: en APP
                  o NONE, MODULE (un módulo, p. ej. Inventarios en un software contable) o SYSTEM (un sistema base que
                  usan varios: usuarios, permisos); en GAME, LEVEL (un nivel jugable), SCENE (una escena que no es nivel:
                  menú, game over, créditos) o SYSTEM (controles, puntaje, guardado). En un juego los niveles van en el
                  orden en que se juegan. description: qué tiene de distinto, en una frase con los números que dio el
                  cliente (p. ej. "pelota normal hasta 50 puntos").
                - outOfScope: lo que queda fuera a propósito.
                - useEpics: true si varias historias van a tocar el mismo módulo, nivel o escena (cada ítem será una
                  épica que agrupa sus historias); false si el producto es pequeño y no hace falta agrupar. Un juego con
                  varios niveles o escenas siempre lleva épicas. epicsReason: por qué sí o por qué no.
                - No inventes lo que el cliente no pidió. Español, frases cortas.

                Responde SOLO este JSON, con exactamente estos nombres de campos:
                {"summary": "...", "experience": "APP|GAME|NONE",
                 "items": [{"id": "MOD-01", "kind": "MODULE|SYSTEM|LEVEL|SCENE", "name": "...", "description": "..."}],
                 "outOfScope": ["..."], "useEpics": true, "epicsReason": "..."}
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
