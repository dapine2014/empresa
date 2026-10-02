package com.aicompany.core.prospecting;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.AlertMailService;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Spec búsqueda de prospectos §2: una propuesta semanal de Kira; entra al catálogo solo si el fundador la aprueba. */
@Service
public class StrategyProposalService {

    public record StrategyView(String id, String name, String description, String status, String proposedBy, int runs,
                               double validPerRun) {
    }

    private static final Logger log = LoggerFactory.getLogger(StrategyProposalService.class);

    private final ProspectingMemoryService memory;
    private final CeoService ceo;
    private final CompanyMemoryService companyMemory;
    private final CompanyEventPublisher events;
    private final AlertMailService mail;
    private final String defaultModel;

    public StrategyProposalService(ProspectingMemoryService memory, CeoService ceo, CompanyMemoryService companyMemory,
                                   CompanyEventPublisher events, AlertMailService mail,
                                   @Value("${ollama.agent-model}") String defaultModel) {
        this.memory = memory;
        this.ceo = ceo;
        this.companyMemory = companyMemory;
        this.events = events;
        this.mail = mail;
        this.defaultModel = defaultModel;
    }

    private com.aicompany.core.service.AgentAvailability agentAvailability;

    /** Decisión del fundador (2026-10-01): un agente apagado nunca recibe una llamada al modelo (opcional en tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAgentAvailability(com.aicompany.core.service.AgentAvailability agentAvailability) {
        this.agentAvailability = agentAvailability;
    }

    private boolean agentOn(String agentId) {
        return agentAvailability == null || agentAvailability.isEnabled(agentId);
    }

    @Scheduled(cron = "0 0 9 * * MON", zone = "UTC")
    public synchronized Optional<StoredStrategy> proposeWeekly() {
        if (!agentOn("growth-content")) {
            return Optional.empty();
        }
        var stored = memory.strategies();
        if (stored.stream().anyMatch(s -> "PENDING_APPROVAL".equals(s.status()))) {
            return Optional.empty();
        }
        try {
            var proposal = ceo.proposeProspectingStrategy(performanceText(),
                    companyMemory.agentModel("growth-content", defaultModel));
            var problem = problem(proposal, stored);
            if (problem != null) {
                log.warn("PROSPECTING strategy proposal discarded: {}", problem);
                return Optional.empty();
            }
            var strategy = new StoredStrategy("STRATEGY-" + UUID.randomUUID(), proposal.name().strip(),
                    proposal.description().strip(), proposal.searchHints() == null ? "" : proposal.searchHints().strip(),
                    "PENDING_APPROVAL", "growth-content", Instant.now(), null);
            memory.saveStrategy(strategy);
            events.publish("EMPRESA_PROSPECTING_STRATEGY_PROPOSED", null, null, "growth-content",
                    Map.of("strategyId", strategy.id(), "name", strategy.name()));
            mail.send("Forjai: Kira propone una estrategia para buscar clientes",
                    strategy.name() + ": " + strategy.description()
                            + "\n\nApruébala o recházala en la pantalla Prospectos o en el chat "
                            + "(\"aprueba la estrategia " + strategy.name() + "\").", false);
            return Optional.of(strategy);
        } catch (Exception ex) {
            log.error("PROSPECTING strategy proposal failed", ex);
            return Optional.empty();
        }
    }

    public StoredStrategy approve(String id) {
        return decide(id, "APPROVED", "EMPRESA_PROSPECTING_STRATEGY_APPROVED");
    }

    public StoredStrategy reject(String id) {
        return decide(id, "REJECTED", "EMPRESA_PROSPECTING_STRATEGY_REJECTED");
    }

    private StoredStrategy decide(String id, String status, String event) {
        var strategy = memory.strategy(id)
                .orElseThrow(() -> new IllegalArgumentException("No existe la estrategia " + id + "."));
        if (!"PENDING_APPROVAL".equals(strategy.status())) {
            throw new IllegalArgumentException("La estrategia \"" + strategy.name() + "\" ya fue decidida ("
                    + strategy.status() + ").");
        }
        var now = Instant.now();
        memory.decideStrategy(id, status, now);
        events.publish(event, null, null, "human", Map.of("strategyId", id, "name", strategy.name()));
        return new StoredStrategy(strategy.id(), strategy.name(), strategy.description(), strategy.searchHints(), status,
                strategy.proposedBy(), strategy.proposedAt(), now);
    }

    public List<StrategyView> views() {
        var stats = memory.stats();
        var base = BaseStrategy.options().stream()
                .map(o -> view(o.id(), o.name(), o.description(), "BASE", null, stats));
        var stored = memory.strategies().stream()
                .map(s -> view(s.id(), s.name(), s.description(), s.status(), s.proposedBy(), stats));
        return Stream.concat(base, stored).toList();
    }

    /** Pendientes por nombre: el nombre exacto gana; si no, las que lo contienen. */
    public List<StoredStrategy> findByName(String text) {
        var wanted = ProspectValidator.normalize(text);
        var pending = memory.strategies().stream().filter(s -> "PENDING_APPROVAL".equals(s.status())).toList();
        var exact = pending.stream().filter(s -> ProspectValidator.normalize(s.name()).equals(wanted)).toList();
        return exact.isEmpty()
                ? pending.stream().filter(s -> ProspectValidator.normalize(s.name()).contains(wanted)).toList()
                : exact;
    }

    private static StrategyView view(String id, String name, String description, String status, String proposedBy,
                                     List<RunStat> stats) {
        var own = stats.stream().filter(r -> id.equals(r.strategyId())).toList();
        var perRun = own.stream().mapToInt(RunStat::valid).average().orElse(0);
        return new StrategyView(id, name, description, status, proposedBy, own.size(), perRun);
    }

    private String performanceText() {
        return views().stream()
                .map(v -> "- " + v.name() + " [" + v.status() + "]: " + v.runs() + " corridas, "
                        + String.format(java.util.Locale.ROOT, "%.1f", v.validPerRun()) + " válidos por corrida. "
                        + v.description())
                .collect(Collectors.joining("\n"));
    }

    private static String problem(StrategyProposal proposal, List<StoredStrategy> stored) {
        if (proposal == null || proposal.name() == null || proposal.name().isBlank()
                || proposal.description() == null || proposal.description().isBlank()) {
            return "sin nombre o sin descripción";
        }
        var name = ProspectValidator.normalize(proposal.name());
        var taken = Stream.concat(BaseStrategy.options().stream().map(StrategyOption::name),
                stored.stream().map(StoredStrategy::name)).map(ProspectValidator::normalize);
        return taken.anyMatch(name::equals) ? "nombre repetido: " + proposal.name() : null;
    }
}
