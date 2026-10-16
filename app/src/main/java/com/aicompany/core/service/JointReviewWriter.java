package com.aicompany.core.service;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.JointReview;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.agent.validation.JointReviewValidator;
import com.aicompany.core.agent.validation.RequirementsText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Revisión conjunta (spec 2026-10-15): Neo (líder técnico) detecta las contradicciones entre las HU de Aria y el diseño
 * de Kael y propone; Aria (PO, dueña del alcance) decide cada una. Java valida y reintenta con el motivo exacto, hasta
 * 3 intentos por paso. Las instrucciones de la revisión van fijas en Java (como CorrectionStrategist).
 */
@Service
public class JointReviewWriter {

    private static final Logger log = LoggerFactory.getLogger(JointReviewWriter.class);
    public static final String NEO = "engineering";
    static final int MAX_ATTEMPTS = 3;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CeoService ceo;
    private final CompanyMemoryService company;
    private final PromptMemoryService prompts;
    private final String defaultModel;

    public JointReviewWriter(CeoService ceo, CompanyMemoryService company, PromptMemoryService prompts,
                             @Value("${ollama.agent-model}") String defaultModel) {
        this.ceo = ceo;
        this.company = company;
        this.prompts = prompts;
        this.defaultModel = defaultModel;
    }

    private ModelHealthService modelHealth;

    /** Intento extra con el respaldo pago (2026-10-16): opcional; sin él no se mira si los modelos están DOWN. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setModelHealth(ModelHealthService modelHealth) {
        this.modelHealth = modelHealth;
    }

    public List<JointReview.Contradiction> detect(String missionId, RequirementsSpec spec, InteractionDesign design,
                                                  List<String> founderAdjustments) {
        var base = context(spec, design) + """

                Eres Neo, líder técnico del Development Group. Revisa si el DISEÑO contradice las HISTORIAS (o al revés).
                Es contradicción: algo que el diseño hace y las HU, sus escenarios o sus supuestos niegan (o al revés);
                valores incompatibles (tamaños, límites, velocidades, cantidades); una regla o pantalla que no puede
                cumplir un escenario (p. ej. el game over por un borde donde no está la paleta); y, en un juego, una
                regla de colisión o rebote sin condición de dirección (la pelota invierte cada frame y se queda pegada).
                NO es contradicción: estilo, nombres o preferencias.
                Para cada una: id CONTRA-01, CONTRA-02… en orden; requirementRefs (HU-NN/ESC-NN/MOD-NN) y designRefs (ids del
                diseño: PANT-NN en apps; REGLA-NN, ESCENA-NN, MOD-NN o el id de una entidad en juegos); qué dice cada lado; y tu propuesta:
                side DESIGN (cambiar el diseño) o REQUIREMENTS (cambiar las HU) y el cambio concreto (con números).
                Máximo 10. Si no hay ninguna, responde {"contradictions": []}.
                """ + founder(founderAdjustments);
        return withRetries(missionId, "detect", NEO, base,
                (prompt, model) -> ceo.detectContradictions(NEO, prompt, prompts.activePrompt(NEO), model),
                detection -> JointReviewValidator.validateDetection(detection, spec, design),
                JointReview.Detection::contradictions);
    }

    public List<JointReview.Decision> decide(String missionId, RequirementsSpec spec, InteractionDesign design,
                                             List<JointReview.Contradiction> contradictions,
                                             List<String> founderAdjustments) {
        var base = context(spec, design) + """

                Eres Aria, Product Owner: el alcance es tuyo. Neo (líder técnico) encontró estas contradicciones entre
                tus historias y el diseño de Kael, cada una con su propuesta:
                %s
                Decide CADA una exactamente una vez: side DESIGN (se cambia el diseño) o REQUIREMENTS (se cambian tus
                HU), el cambio concreto que se aplica (puedes aceptar el de Neo o escribir otro) y la razón. No puedes
                descartar una contradicción.
                """.formatted(JSON.writeValueAsString(contradictions)) + founder(founderAdjustments);
        return withRetries(missionId, "decide", RequirementsWriter.ARIA, base,
                (prompt, model) -> ceo.decideContradictions(RequirementsWriter.ARIA, prompt,
                        prompts.activePrompt(RequirementsWriter.ARIA), model),
                resolution -> JointReviewValidator.validateResolution(resolution, contradictions),
                JointReview.Resolution::decisions);
    }

    private <R, T> List<T> withRetries(String missionId, String step, String agentId, String base,
                                       BiFunction<String, String, R> call, Function<R, List<String>> validate,
                                       Function<R, List<T>> items) {
        String feedback = null;
        var model = model(agentId);
        var paidRetry = PaidRetry.start(agentId, model, company, modelHealth);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS + 1; attempt++) {
            if (attempt > MAX_ATTEMPTS) {
                // Decisión del fundador (2026-10-16): un intento más con el respaldo pago, con la corrección.
                var paid = paidRetry.next("joint-review-" + step);
                if (paid.isEmpty()) {
                    break;
                }
                model = paid.get();
            }
            var prompt = feedback == null ? base : base + """

                    CORRECCIÓN DEL INTENTO ANTERIOR (Java rechazó la respuesta por esto; corrígelo todo):
                    %s
                    """.formatted(feedback);
            try {
                var result = call.apply(prompt, model);
                var errors = validate.apply(result);
                if (errors.isEmpty()) {
                    return items.apply(result);
                }
                feedback = "- " + String.join("\n- ", errors);
            } catch (MissionCancellations.MissionCancelledException ex) {
                throw ex; // cancelar la misión no es una respuesta inválida: no se reintenta.
            } catch (RuntimeException ex) {
                feedback = "- " + (ex.getMessage() == null ? "Respuesta no procesable." : ex.getMessage());
            }
            log.warn("MISSION {} - joint review {} rejected attempt={} errors={}", missionId, step, attempt, feedback);
        }
        throw new IllegalStateException("La revisión conjunta (" + step + ") no produjo una respuesta válida después de "
                + MAX_ATTEMPTS + " intentos" + paidRetry.suffix() + ":\n" + feedback);
    }

    private static String context(RequirementsSpec spec, InteractionDesign design) {
        var stories = spec.incrementNumbers().stream().map(n -> RequirementsText.incrementBlock(spec, n))
                .collect(Collectors.joining("\n"));
        var flat = JSON.writeValueAsString(design.app() != null ? design.app() : design.game());
        return "HISTORIAS DE USUARIO DE ARIA (con supuestos y fuera de alcance):\n" + stories
                + "\nSupuestos: " + String.join("; ", spec.assumptionsOrEmpty())
                + "\n\nDISEÑO DE KAEL (" + design.experience() + "):\n" + flat;
    }

    private static String founder(List<String> adjustments) {
        if (adjustments == null || adjustments.isEmpty()) {
            return "";
        }
        return "\nDECISIONES DEL FUNDADOR (nunca propongas un cambio que las deshaga):\n- "
                + String.join("\n- ", adjustments) + "\n";
    }

    private String model(String agentId) {
        return company.agentModel(agentId, defaultModel);
    }
}
