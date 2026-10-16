package com.aicompany.core.service;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.agent.validation.InteractionDesignValidator;
import com.aicompany.core.agent.validation.RequirementsText;
import com.aicompany.core.event.CompanyEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Spec 2026-10-14 Kael §1.3: Kael (Interactive Logic & Product Designer) diseña la interacción de una app o la
 * mecánica de un juego desde el spec de Aria; Java lo valida (InteractionDesignValidator) y Kael corrige con el
 * motivo exacto, hasta 3 intentos. Cada diseño válido es una versión DRAFT.
 */
@Service
public class InteractionDesignWriter {

    private static final Logger log = LoggerFactory.getLogger(InteractionDesignWriter.class);
    public static final String KAEL = "interaction-design";
    static final int MAX_ATTEMPTS = 3;

    public record Written(int version, InteractionDesign design) {
    }

    private final CeoService ceo;
    private final MissionMemoryService missions;
    private final InteractionDesignMemoryService designs;
    private final CompanyMemoryService company;
    private final PromptMemoryService prompts;
    private final AgentAvailability availability;
    private final CompanyEventPublisher events;
    private final JsonMapper json;
    private final String defaultModel;

    public InteractionDesignWriter(CeoService ceo, MissionMemoryService missions, InteractionDesignMemoryService designs,
                                   CompanyMemoryService company, PromptMemoryService prompts,
                                   AgentAvailability availability, CompanyEventPublisher events, JsonMapper json,
                                   @Value("${ollama.agent-model}") String defaultModel) {
        this.ceo = ceo;
        this.missions = missions;
        this.designs = designs;
        this.company = company;
        this.prompts = prompts;
        this.availability = availability;
        this.events = events;
        this.json = json;
        this.defaultModel = defaultModel;
    }

    public Written write(String missionId, RequirementsSpec spec, int specVersion, Optional<InteractionDesign> previous,
                         String adjustment) {
        var experience = spec.experienceOrNull();
        if (experience != RequirementsSpec.Experience.APP && experience != RequirementsSpec.Experience.GAME) {
            throw new IllegalStateException("MISSION " + missionId + " no necesita diseño de Kael (experiencia "
                    + experience + ").");
        }
        if (!availability.isEnabled(KAEL)) {
            throw new IllegalStateException("Kael está apagado: la misión " + missionId + " es " + experience
                    + " y necesita su diseño. Enciéndelo en el Command Center.");
        }
        var nextVersion = designs.latest(missionId).map(v -> v.version() + 1).orElse(1);
        var taskId = missionId + "-INTERACTION" + (nextVersion > 1 ? "-V" + nextVersion : "");
        missions.createTask(taskId, missionId, KAEL, "INTERACTION_DESIGN", "DESIGN");
        missions.updateTask(taskId, "RUNNING", "Kael está diseñando la " + (experience == RequirementsSpec.Experience.GAME
                ? "mecánica del juego." : "interacción de la app."));
        missions.setAgentStatus(KAEL, "WORKING");
        try {
            var model = company.agentModel(KAEL, defaultModel);
            var agentPrompt = prompts.activePrompt(KAEL);
            // Si Aria cambió la experiencia (APP→GAME), el diseño anterior no sirve de base.
            var sameExperience = previous.filter(p -> experience.name().equals(p.experience()));
            var base = prompt(spec, experience, sameExperience, adjustment);
            String feedback = null;
            String understood = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                var prompt = feedback == null ? base : base + """

                        CORRECCIÓN DEL INTENTO ANTERIOR (Java rechazó el diseño por esto; corrígelo todo):
                        %s
                        """.formatted(feedback) + (understood == null ? "" : """
                        Así entendió Java tu respuesta anterior (un campo con otro nombre se pierde; usa exactamente
                        los nombres del formato):
                        %s
                        """.formatted(understood));
                InteractionDesign design;
                List<String> errors;
                try {
                    if (experience == RequirementsSpec.Experience.APP) {
                        var app = ceo.writeAppDesign(KAEL, prompt, agentPrompt, model);
                        errors = InteractionDesignValidator.validateApp(app, spec);
                        design = new InteractionDesign("APP", app, null);
                    } else {
                        var game = ceo.writeGameDesign(KAEL, prompt, agentPrompt, model);
                        errors = InteractionDesignValidator.validateGame(game, spec);
                        design = new InteractionDesign("GAME", null, game);
                    }
                } catch (Exception ex) {
                    feedback = "- " + (ex.getMessage() == null ? "Respuesta no procesable." : ex.getMessage());
                    continue;
                }
                if (errors.isEmpty()) {
                    var designJson = json.writeValueAsString(design);
                    var version = designs.saveDraft(missionId, designJson, specVersion, adjustment);
                    missions.updateTask(taskId, "COMPLETED", designJson);
                    log.info("MISSION {} - interaction design v{} accepted experience={}", missionId, version, experience);
                    return new Written(version, design);
                }
                feedback = "- " + String.join("\n- ", errors);
                understood = flat(design);
                log.warn("MISSION {} - interaction design rejected attempt={} errors={} understood={}", missionId,
                        attempt, feedback, understood.length() > 4000 ? understood.substring(0, 4000) + "…" : understood);
            }
            var message = "Kael no produjo un diseño válido después de " + MAX_ATTEMPTS + " intentos:\n" + feedback;
            missions.updateTask(taskId, "FAILED", message);
            throw new IllegalStateException(message);
        } finally {
            missions.setAgentStatus(KAEL, "IDLE");
        }
    }

    private String prompt(RequirementsSpec spec, RequirementsSpec.Experience experience,
                          Optional<InteractionDesign> previous, String adjustment) {
        var allStories = spec.incrementNumbers().stream().map(n -> RequirementsText.incrementBlock(spec, n))
                .collect(Collectors.joining("\n"));
        var rules = experience == RequirementsSpec.Experience.APP ? """
                Diseña la INTERACCIÓN de la app (no el código):
                - screens: PANT-01, PANT-02… (máximo 15) con name, purpose, role (un rol de las HU) y states (solo
                  "empty", "loading", "error" cuando apliquen).
                - flows: uno por cada HU (story: "HU-NN"), con la secuencia de pantallas y las acciones del usuario.
                - scenarioScreens: CADA escenario ESC-NN de las HU → la pantalla donde ocurre.
                - frictionRisks: riesgos de fricción o engagement, cada uno como hipótesis + mitigación.
                Responde SOLO este JSON: {"screens":[{"id":"PANT-01","name":"...","purpose":"...","role":"...","states":["empty"]}],
                 "flows":[{"story":"HU-01","screens":["PANT-01"],"actions":["..."]}],
                 "scenarioScreens":[{"scenario":"ESC-01","screen":"PANT-01"}],"frictionRisks":["..."]}
                """ : """
                Diseña la MECÁNICA del juego 2D (no el código). Sin assets externos: todo se dibuja con formas y texto.
                - states del loop (p. ej. START, PLAYING, GAME_OVER) y transitions {from,to,trigger}.
                - entities con properties numéricas (value es un NÚMERO: velocidad en px/s, tamaño en px, vidas).
                - controls: action (nombre de acción de Godot en minúsculas, p. ej. move_left), key y effect (máximo 8).
                - rules: REGLA-01… con condition y effect (máximo 20); cada control aparece en la condition de una regla.
                - win/lose: ids de reglas. difficulty: escalado con números.
                - scenarioRules: CADA escenario ESC-NN → la REGLA-NN o la action que lo cumple.
                - balanceHypotheses: hipótesis de balance (no afirmes que algo es divertido).
                - Si hay NIVELES Y ESCENAS DEL CONTRATO (arriba): scenes ESCENA-01, ESCENA-02… (máximo 8) con name,
                  script (nombre de la clase C# y de sus archivos: empieza en mayúscula, sin espacios, p. ej. Main, Menu;
                  nunca Playthrough ni PlaythroughRunner) y purpose; ESCENA-01 es la escena principal. levels: uno por
                  cada MOD-NN de esa lista con item (el MOD-NN), la escena donde ocurre (scene), sus parameters numéricos (p. ej.
                  velocidad_pelota=200, pelotas=1, puntaje_meta=50) y advanceRule (la REGLA-NN que pasa al siguiente
                  nivel; vacía en el último LEVEL y en las SCENE).
                Responde SOLO este JSON: {"states":["START"],"transitions":[{"from":"START","to":"PLAYING","trigger":"..."}],
                 "entities":[{"id":"...","name":"...","properties":[{"name":"...","value":1}]}],
                 "controls":[{"action":"move_left","key":"Left","effect":"..."}],
                 "rules":[{"id":"REGLA-01","condition":"...","effect":"..."}],"win":[],"lose":["REGLA-02"],
                 "difficulty":[{"name":"...","value":1.1}],"scenarioRules":[{"scenario":"ESC-01","target":"REGLA-01"}],
                 "balanceHypotheses":["..."],
                 "scenes":[{"id":"ESCENA-01","name":"...","script":"Main","purpose":"..."}],"levels":[{"item":"MOD-01","scene":"ESCENA-01","parameters":[{"name":"...","value":1}],"advanceRule":"REGLA-03"}]}
                """;
        var levels = spec.gameLevels().isEmpty() ? "" : "\nNIVELES Y ESCENAS DEL CONTRATO (cada uno lleva su entrada en levels):\n"
                + spec.gameLevels().stream().map(i -> "- " + i.id() + " [" + i.kind() + "] " + i.name() + ": " + i.description())
                .collect(Collectors.joining("\n")) + "\n";
        var base = "Eres Kael, Interactive Logic & Product Designer del Development Group de Forjai.\n\n"
                + "HISTORIAS DE USUARIO DE ARIA (todas las del producto):\n" + allStories + levels + "\n" + rules;
        if (previous.isEmpty()) {
            return adjustment == null || adjustment.isBlank() ? base : base + "\nAJUSTE DEL FUNDADOR:\n" + adjustment;
        }
        return base + "\nDISEÑO ANTERIOR (conserva lo que no contradiga el ajuste ni las HU actuales):\n"
                + flat(previous.get()) + "\n\nAJUSTE DEL FUNDADOR:\n"
                + (adjustment == null ? "" : adjustment);
    }

    /** El diseño en el mismo formato plano que se le pide a Kael (sin el envoltorio experience/app/game). */
    private String flat(InteractionDesign design) {
        return json.writeValueAsString(design.app() != null ? design.app() : design.game());
    }
}
