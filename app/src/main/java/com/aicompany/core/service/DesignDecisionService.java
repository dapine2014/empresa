package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.DesignDecision;
import com.aicompany.core.model.DesignReviewView;
import com.aicompany.core.model.DesignScreenReview;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.recovery.MissionRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Spec 2026-11-01 front con diseño visual §14-15: el punto único de decisión sobre una pantalla que no alcanzó el umbral
 * ({@code AWAITING_DECISION}). Aceptar o pedir otra ronda pasa siempre por {@link #decide}; según la política
 * {@code DESIGN_MISMATCH_DECIDER} cambia solo quién la invoca (hoy el fundador desde el Command Center; el PO queda sin
 * habilitar). Cuando no queda ninguna pantalla esperando, la misión se retoma en su ronda y vuelve al ciclo de diseño.
 */
@Service
public class DesignDecisionService {

    private static final Logger log = LoggerFactory.getLogger(DesignDecisionService.class);

    public static final String FOUNDER = "FOUNDER";
    public static final String PRODUCT_OWNER = "PRODUCT_OWNER";

    private final MissionMemoryService missions;
    private final DesignReviewMemoryService reviews;
    private final CompanyPolicyService policies;
    private final CompanyEventPublisher events;
    private final MissionRecoveryService recovery;

    public DesignDecisionService(MissionMemoryService missions, DesignReviewMemoryService reviews,
                                 CompanyPolicyService policies, CompanyEventPublisher events,
                                 MissionRecoveryService recovery) {
        this.missions = missions;
        this.reviews = reviews;
        this.policies = policies;
        this.events = events;
        this.recovery = recovery;
    }

    /** Quién decide las diferencias de diseño, resuelto por Java desde la política (0 = FOUNDER). */
    public String decider() {
        return policies.activeValue(PolicyKey.DESIGN_MISMATCH_DECIDER) == 0 ? FOUNDER : PRODUCT_OWNER;
    }

    /** La revisión de diseño de la misión (todas las pantallas de todos los incrementos); vacío si no existe la misión. */
    public Optional<DesignReviewView> view(String missionId) {
        return missions.find(missionId).map(m -> new DesignReviewView(missionId, m.status().name(),
                designBlockReason(m.message()), decider(), reviews.all(missionId)));
    }

    /**
     * La única operación de decisión. Exige la misión BLOCKED por {@code DESIGN_MISMATCH} y la pantalla en
     * {@code AWAITING_DECISION}: el claim atómico de {@link DesignReviewMemoryService#decide} es el guardia del doble clic.
     * {@code ACCEPT} deja la pantalla ACCEPTED; {@code ANOTHER_ROUND} suma una ronda extra, guarda el comentario (que el
     * ciclo agrega a la corrección) y la deja PENDING. Si era la última, retoma la misión.
     */
    public DesignReviewView decide(String missionId, String screen, DesignDecision decision, String note,
                                   String decidedBy) {
        if (screen == null || screen.isBlank() || decision == null) {
            throw new IllegalArgumentException("Falta la pantalla (PANT-NN) o la decisión (ACCEPT u ANOTHER_ROUND).");
        }
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("Falta quién decide.");
        }
        var cleanNote = note == null ? "" : note.strip();
        if (decision == DesignDecision.ANOTHER_ROUND && cleanNote.isEmpty()) {
            throw new IllegalArgumentException("Otra ronda necesita un comentario: se agrega a la corrección del front.");
        }
        var mission = missions.find(missionId)
                .orElseThrow(() -> new IllegalArgumentException("No existe la misión " + missionId));
        var message = mission.message() == null ? "" : mission.message();
        if (mission.status() != MissionStatus.BLOCKED) {
            throw new IllegalStateException("MISSION " + missionId + " no está BLOCKED por el diseño (estado: "
                    + mission.status() + "): no hay pantallas que decidir.");
        }
        if (message.startsWith(DesignReviewLoop.CAPTURE_PREFIX)) {
            // R13: sin captura no hay comparación; no es una diferencia de diseño sino algo que reintentar.
            throw new IllegalStateException("MISSION " + missionId + " está frenada porque no se pudo capturar el diseño, "
                    + "no por diferencias: no hay pantallas que decidir. Retómala con \"reanuda " + missionId + "\".");
        }
        if (!message.startsWith(DesignReviewLoop.BLOCK_PREFIX)) {
            throw new IllegalStateException("MISSION " + missionId + " está BLOCKED por otro motivo, no por el diseño.");
        }

        var accept = decision == DesignDecision.ACCEPT;
        var decided = reviews.decide(missionId, screen, accept ? DesignScreenReview.ACCEPTED : DesignScreenReview.PENDING,
                decidedBy, cleanNote, accept ? 0 : 1);
        if (decided.isEmpty()) {
            throw new IllegalStateException("La pantalla " + screen + " de " + missionId
                    + " no espera una decisión o ya tiene una decisión en curso.");
        }
        events.publish("EMPRESA_DESIGN_DECISION", missionId, null, "human", Map.of("screen", screen,
                "decision", decision.name(), "decidedBy", decidedBy, "increment", decided.get().increment()));

        if (decided.get().stillAwaiting() == 0) {
            try {
                recovery.resumeDesign(missionId);
            } catch (RuntimeException ex) {
                log.warn("MISSION {} - design decision recorded but the mission could not resume: {}", missionId,
                        ex.getMessage());
                throw new IllegalStateException("La decisión sobre " + screen + " quedó registrada, pero no se pudo "
                        + "retomar " + missionId + ": " + ex.getMessage() + " Cuando se resuelva, escribe \"reanuda "
                        + missionId + "\".", ex);
            }
        }
        return view(missionId).orElseThrow();
    }

    /** La primera línea del motivo si la misión está frenada por diseño; si no, null. */
    private static String designBlockReason(String message) {
        if (message == null || !(message.startsWith(DesignReviewLoop.BLOCK_PREFIX)
                || message.startsWith(DesignReviewLoop.CAPTURE_PREFIX))) {
            return null;
        }
        var end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }
}
