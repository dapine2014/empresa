package com.aicompany.core.service;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.InteractionDesign.AppDesign;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.agent.model.VisualDesign;
import com.aicompany.core.agent.validation.InteractionDesignText;
import com.aicompany.core.agent.validation.MockupValidator;
import com.aicompany.core.agent.validation.VisualSystemValidator;
import com.aicompany.core.event.CompanyEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Spec 2026-11-01 front con diseño visual: tras el diseño de Kael, el front (Mila, u Orion si Mila está apagada)
 * escribe el sistema visual y una maqueta por pantalla del incremento; Java valida (VisualSystemValidator y
 * MockupValidator) y el front corrige con el motivo exacto, hasta 3 intentos. Cada diseño válido es una versión DRAFT.
 */
@Service
public class VisualDesignWriter {

    private static final Logger log = LoggerFactory.getLogger(VisualDesignWriter.class);
    static final int MAX_ATTEMPTS = 3;

    public record Written(int version, VisualDesign design) {
    }

    private final CeoService ceo;
    private final MissionMemoryService missions;
    private final VisualDesignMemoryService designs;
    private final RequirementsMemoryService requirements;
    private final InteractionDesignMemoryService interaction;
    private final CompanyMemoryService company;
    private final PromptMemoryService prompts;
    private final AgentAvailability availability;
    private final CompanyEventPublisher events;
    private final JsonMapper json;
    private final String defaultModel;

    public VisualDesignWriter(CeoService ceo, MissionMemoryService missions, VisualDesignMemoryService designs,
                              RequirementsMemoryService requirements, InteractionDesignMemoryService interaction,
                              CompanyMemoryService company, PromptMemoryService prompts, AgentAvailability availability,
                              CompanyEventPublisher events, JsonMapper json,
                              @Value("${ollama.agent-model}") String defaultModel) {
        this.ceo = ceo;
        this.missions = missions;
        this.designs = designs;
        this.requirements = requirements;
        this.interaction = interaction;
        this.company = company;
        this.prompts = prompts;
        this.availability = availability;
        this.events = events;
        this.json = json;
        this.defaultModel = defaultModel;
    }

    private ModelHealthService modelHealth;

    /** Intento extra con el respaldo pago: opcional; sin él no se mira si los modelos están DOWN. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setModelHealth(ModelHealthService modelHealth) {
        this.modelHealth = modelHealth;
    }

    /**
     * @param previous el diseño visual aprobado en un incremento anterior (su sistema es la base), si existe.
     */
    public Written write(String missionId, String frontAgentId, RequirementsSpec spec, AppDesign app, int increment,
                         Optional<VisualDesign> previous, String adjustment) {
        if (!availability.isEnabled(frontAgentId)) {
            throw new IllegalStateException("El agente " + frontAgentId + " está apagado: la misión " + missionId
                    + " necesita su diseño visual. Enciéndelo en el Command Center.");
        }
        var interactionDesign = new InteractionDesign("APP", app, null);
        var screens = InteractionDesignText.screensOf(interactionDesign, spec, increment);
        var nextVersion = designs.latest(missionId).map(v -> v.version() + 1).orElse(1);
        var taskId = missionId + "-VISUAL" + (nextVersion > 1 ? "-V" + nextVersion : "");
        missions.createTask(taskId, missionId, frontAgentId, "VISUAL_DESIGN", "DESIGN");
        missions.updateTask(taskId, "RUNNING", "El front está diseñando el sistema visual y las maquetas.");
        missions.setAgentStatus(frontAgentId, "WORKING");
        try {
            var model = company.agentModel(frontAgentId, defaultModel);
            var agentPrompt = prompts.activePrompt(frontAgentId);
            var base = prompt(spec, app, increment, screens, previous, adjustment);
            String feedback = null;
            String understood = null;
            var paidRetry = PaidRetry.start(frontAgentId, model, company, modelHealth);
            for (int attempt = 1; attempt <= MAX_ATTEMPTS + 1; attempt++) {
                if (attempt > MAX_ATTEMPTS) {
                    var paid = paidRetry.next("visual-design");
                    if (paid.isEmpty()) {
                        break;
                    }
                    model = paid.get();
                }
                var prompt = feedback == null ? base : base + """

                        CORRECCIÓN DEL INTENTO ANTERIOR (Java rechazó el diseño por esto; corrígelo todo):
                        %s
                        """.formatted(feedback) + (understood == null ? "" : """
                        Así entendió Java tu respuesta anterior (un campo con otro nombre se pierde; usa exactamente
                        los nombres del formato):
                        %s
                        """.formatted(understood));
                VisualDesign design;
                try {
                    design = ceo.writeVisualDesign(frontAgentId, prompt, agentPrompt, model);
                } catch (Exception ex) {
                    paidRetry.note(ex);
                    feedback = "- " + (ex.getMessage() == null ? "Respuesta no procesable." : ex.getMessage());
                    continue;
                }
                var errors = new ArrayList<String>();
                if (design == null || design.system() == null || design.mockups() == null) {
                    errors.add("Falta system o mockups en la respuesta.");
                } else {
                    errors.addAll(VisualSystemValidator.validate(design.system()));
                    errors.addAll(MockupValidator.validate(design.mockups(), app, screens, design.system()));
                }
                if (errors.isEmpty()) {
                    var designJson = json.writeValueAsString(design);
                    var specVersion = requirements.latest(missionId).map(RequirementsMemoryService.Version::version).orElse(0);
                    var designVersion = interaction.latest(missionId).map(InteractionDesignMemoryService.Version::version).orElse(0);
                    var version = designs.saveDraft(missionId, designJson, specVersion, designVersion, adjustment);
                    missions.updateTask(taskId, "COMPLETED", designJson);
                    if (paidRetry.used() != null) {
                        missions.setTaskModelUsed(taskId, paidRetry.used());
                    }
                    log.info("MISSION {} - visual design v{} accepted agent={}", missionId, version, frontAgentId);
                    return new Written(version, design);
                }
                feedback = "- " + String.join("\n- ", errors);
                understood = design == null ? "(vacío)" : json.writeValueAsString(design);
                log.warn("MISSION {} - visual design rejected attempt={} errors={}", missionId, attempt, feedback);
            }
            var message = "El front no produjo un diseño visual válido después de " + MAX_ATTEMPTS + " intentos"
                    + paidRetry.suffix() + ":\n" + feedback;
            missions.updateTask(taskId, "FAILED", message);
            throw new IllegalStateException(message);
        } finally {
            missions.setAgentStatus(frontAgentId, "IDLE");
        }
    }

    private String prompt(RequirementsSpec spec, AppDesign app, int increment, java.util.Set<String> screens,
                          Optional<VisualDesign> previous, String adjustment) {
        var screenList = app.screens().stream().filter(s -> screens.contains(s.id()))
                .map(s -> "- " + s.id() + " " + s.name() + " (" + s.role() + "): " + s.purpose()
                        + (s.states().isEmpty() ? "" : " — estados: " + String.join(", ", s.states())))
                .collect(Collectors.joining("\n"));
        var base = """
                Eres el diseñador visual del front del Development Group de Forjai. Escribe el SISTEMA VISUAL de la app
                y UNA MAQUETA HTML/CSS por cada pantalla del incremento %d (solo estas pantallas):
                %s

                Reglas que Java verifica (si fallan, te devuelve el motivo exacto):
                - system.colors: lista de {name, hex} con hex #RRGGBB. Obligatorios: primary, onPrimary, surface,
                  onSurface, error, onError. El texto sobre su fondo debe tener contraste mínimo 4,5 (onPrimary/primary,
                  onSurface/surface, onError/error).
                - system.typography: fontFamily del catálogo de fuentes y sizes con display, title, body y label.
                - system.spacing y system.radii: mapas nombre → número. system.components: button, field, card y/o
                  appBar, con tokens cuyo valor es el NOMBRE de un color.
                - mockups.screens: {screen: "PANT-NN", html}. Solo HTML y CSS inline de un fragmento (sin <script>).
                  Cada elemento relevante lleva data-id único, y el data-id del widget raíz de la pantalla es igual
                  a la Key de Flutter (la Key('PANT-NN') o la del elemento).
                - Sin colores literales (#hex, rgb(, hsl() en las maquetas: usa var(--color-<nombre>) con los colores del
                  sistema. Tampoco referencias de red: sin http(s), url(), @import ni scripts.
                - mockups.svgs: {name, svg} con name en [a-z0-9_]+; mismas reglas de color y red.
                Responde SOLO este JSON: {"system":{"colors":[{"name":"primary","hex":"#1F4E79"}],
                 "typography":{"fontFamily":"Roboto","sizes":{"display":32,"title":20,"body":14,"label":12}},
                 "spacing":{"md":16},"radii":{"md":8},"components":[{"component":"button","tokens":{"background":"primary","text":"onPrimary"}}]},
                 "mockups":{"screens":[{"screen":"PANT-NN","html":"<div data-id=\\"PANT-NN\\">...</div>"}],
                 "svgs":[{"name":"logo","svg":"<svg>...</svg>"}]}}
                """.formatted(increment, screenList);
        var story = spec.storiesOf(increment).stream().map(s -> "- " + s.id() + ": " + s.iWant())
                .collect(Collectors.joining("\n"));
        var withStories = base + "\nHISTORIAS DEL INCREMENTO:\n" + story + "\n";
        if (previous.isPresent() && previous.get().system() != null) {
            withStories += "\nSISTEMA VISUAL APROBADO EN UN INCREMENTO ANTERIOR (es la base: devuélvelo igual en system, salvo lo que el ajuste pida cambiar; no repitas las maquetas anteriores):\n"
                    + json.writeValueAsString(previous.get().system()) + "\n";
        }
        return adjustment == null || adjustment.isBlank() ? withStories : withStories + "\nAJUSTE DEL FUNDADOR:\n" + adjustment + "\n";
    }
}
