package com.aicompany.core.service;

import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.ProductStatus;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Deriva el estado real del PRODUCTO (distinto de {@code MissionStatus},
 * que es el workflow de análisis/decisión interno) a partir de señales
 * reales en Company Memory. Nunca persiste nada nuevo: se recalcula en
 * cada consulta, siempre fresco. Ver
 * docs/superpowers/specs/2026-09-20-chat-grounding-product-status-design.md.
 *
 * <p>Reglas evaluadas de la señal más fuerte a la más débil — la primera
 * que matchea gana:
 *
 * <ol>
 *   <li>{@code BUSINESS_SUCCESS}: netProfit &gt; seedCapitalUsd.</li>
 *   <li>{@code MONETIZING}: existe al menos una {@code Transaction} real
 *       para la misión.</li>
 *   <li>{@code PUBLISHED}/{@code QA}: hoy no existe ninguna señal real
 *       para estos dos — siguen siendo el punto de enganche pendiente del
 *       Proyecto B (build/test/deploy real tras la generación de código).
 *       Siempre {@code false} hasta que esa parte del proyecto exista.
 *       <b>Importante</b>: una {@code AgentTask} {@code QUALITY_RISK_REVIEW}
 *       (discovery de {@code qa}) nunca cuenta como evidencia de
 *       {@code QA} real — son conceptos distintos.</li>
 *   <li>{@code DEVELOPMENT}: real desde la generación de código tras
 *       {@code APPROVE} — señal real, ver {@code isInDevelopment}.</li>
 *   <li>{@code DESIGN}: la {@code AgentTask} {@code OFFER_DESIGN} de esta
 *       misión está {@code COMPLETED}.</li>
 *   <li>{@code DISCOVERY}: default.</li>
 * </ol>
 */
@Service
public class ProductStatusService {

    private static final Set<String> DEVELOPMENT_ACTIONS = Set.of(
            "ARCHITECTURE_DEVELOPMENT", "BACKEND_DEVELOPMENT", "FRONTEND_DEVELOPMENT"
    );

    private final MissionMemoryService missionMemory;
    private final CustomerMemoryService customerMemory;
    private final AppProperties appProperties;

    public ProductStatusService(
            MissionMemoryService missionMemory,
            CustomerMemoryService customerMemory,
            AppProperties appProperties) {

        this.missionMemory = missionMemory;
        this.customerMemory = customerMemory;
        this.appProperties = appProperties;
    }

    public ProductStatus resolve(String missionId) {

        if (isBusinessSuccess(missionId)) {
            return ProductStatus.BUSINESS_SUCCESS;
        }

        if (isMonetizing(missionId)) {
            return ProductStatus.MONETIZING;
        }

        if (isPublished(missionId)) {
            return ProductStatus.PUBLISHED;
        }

        if (isQaValidated(missionId)) {
            return ProductStatus.QA;
        }

        if (isInDevelopment(missionId)) {
            return ProductStatus.DEVELOPMENT;
        }

        if (isDesigned(missionId)) {
            return ProductStatus.DESIGN;
        }

        return ProductStatus.DISCOVERY;
    }

    private boolean isBusinessSuccess(String missionId) {

        var totals = customerMemory.totalRevenueAndCost(missionId);
        var netProfit = totals[0] - totals[1];

        return netProfit > appProperties.seedCapitalUsd();
    }

    private boolean isMonetizing(String missionId) {
        return customerMemory.transactionCount(missionId) > 0;
    }

    /**
     * Punto de enganche del Proyecto B — hoy no existe ningún
     * artefacto/repositorio/build real registrado en Company Memory.
     */
    private boolean isPublished(String missionId) {
        return false;
    }

    /**
     * Punto de enganche del Proyecto B — QA real sobre un producto que ya
     * existe, distinto de {@code QUALITY_RISK_REVIEW}.
     */
    private boolean isQaValidated(String missionId) {
        return false;
    }

    /**
     * Primera señal real de Proyecto B (subproyecto 1: generación de
     * código) — ver
     * docs/superpowers/specs/2026-09-21-development-generation-design.md.
     * Mismo patrón exacto que {@link #isDesigned}: una {@code AgentTask}
     * real completada, no una inferencia. {@code QUALITY_RISK_REVIEW}
     * (discovery de {@code qa}) sigue sin contar — no está en
     * {@code DEVELOPMENT_ACTIONS}.
     */
    private boolean isInDevelopment(String missionId) {

        return missionMemory.tasks(missionId).stream()
                .anyMatch(t -> DEVELOPMENT_ACTIONS.contains(t.action())
                        && "COMPLETED".equals(t.status()));
    }

    private boolean isDesigned(String missionId) {

        return missionMemory.tasks(missionId).stream()
                .anyMatch(t -> "OFFER_DESIGN".equals(t.action())
                        && "COMPLETED".equals(t.status()));
    }
}
