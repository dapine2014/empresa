package com.aicompany.core.delivery;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.database.DatabaseConnectionService;
import com.aicompany.core.database.MigrationSet;
import com.aicompany.core.model.SandboxResult;
import com.aicompany.core.service.ContractMemoryService;
import com.aicompany.core.service.DevelopmentWorkspaceService;
import com.aicompany.core.service.InteractionDesignMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import com.aicompany.core.service.ModelCostService;
import com.aicompany.core.service.RequirementsMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Entrega del producto: lee spec, contrato, diseño, tests, bases y costo y arma {@link DeliveryFacts}. Todo viene de
 * Java y de la memoria; cada lectura que falla deja su dato vacío (ENTREGA.md dice "no disponible"), nunca tumba la entrega.
 */
@Component
public class DeliveryFactsBuilder {

    private static final Logger log = LoggerFactory.getLogger(DeliveryFactsBuilder.class);

    private final RequirementsMemoryService requirements;
    private final ContractMemoryService contracts;
    private final InteractionDesignMemoryService designs;
    private final DatabaseConnectionService databases;
    private final ModelCostService cost;
    private final DevelopmentWorkspaceService workspace;
    private final MissionMemoryService missions;
    private final JsonMapper json;

    public DeliveryFactsBuilder(RequirementsMemoryService requirements, ContractMemoryService contracts,
                                InteractionDesignMemoryService designs, DatabaseConnectionService databases,
                                ModelCostService cost, DevelopmentWorkspaceService workspace,
                                MissionMemoryService missions, JsonMapper json) {
        this.requirements = requirements;
        this.contracts = contracts;
        this.designs = designs;
        this.databases = databases;
        this.cost = cost;
        this.workspace = workspace;
        this.missions = missions;
        this.json = json;
    }

    /** El spec aprobado; vacío si no hay o no se puede leer. */
    public Optional<RequirementsSpec> spec(String missionId) {
        try {
            var v = requirements.approved(missionId);
            return v == null || v.isEmpty() ? Optional.empty()
                    : Optional.of(json.readValue(v.get().specJson(), RequirementsSpec.class));
        } catch (Exception ex) {
            log.warn("DELIVERY_FACTS mission={} spec unreadable: {}", missionId, ex.getMessage());
            return Optional.empty();
        }
    }

    /** El contrato aprobado; null si no hay o no se puede leer. */
    public ClientContract contract(String missionId) {
        try {
            var v = contracts.approved(missionId);
            return v == null || v.isEmpty() ? null : json.readValue(v.get().contractJson(), ClientContract.class);
        } catch (Exception ex) {
            log.warn("DELIVERY_FACTS mission={} contract unreadable: {}", missionId, ex.getMessage());
            return null;
        }
    }

    /** El último incremento construido (0 = ninguno o no se pudo leer). */
    public int builtIncrement(String missionId) {
        try {
            return requirements.builtIncrement(missionId);
        } catch (Exception ex) {
            return 0;
        }
    }

    /**
     * Migraciones SQL del commit: nombre del archivo → contenido, en orden numérico de versión (revisión final, 8: V2
     * antes que V10). Solo las versionadas que aplica Diego ({@link MigrationSet}); cualquier otro archivo no entra.
     */
    public Map<String, String> migrations(String missionId, String commitSha) {
        var out = new LinkedHashMap<String, String>();
        try {
            var contents = new LinkedHashMap<String, String>();
            for (var f : workspace.filesAtCommit(missionId, commitSha)) {
                if (MigrationSet.isMigrationPath(f)) {
                    contents.put(f, workspace.readFileAtCommit(missionId, commitSha, f));
                }
            }
            MigrationSet.parse(contents).forEach(m -> out.put(MigrationSet.fileName(m), m.sql()));
        } catch (Exception ex) {
            log.warn("DELIVERY_FACTS mission={} migrations unreadable: {}", missionId, ex.getMessage());
        }
        return out;
    }

    public DeliveryFacts build(String missionId, int increment, String commitSha, String stackProfile,
                               int correctionRounds, SandboxResult verifiedRun,
                               List<DeliveryFacts.TargetResult> targets, List<String> limitations, String zipPath,
                               RequirementsSpec spec, ClientContract contract, List<String> migrationNames) {
        var design = design(missionId);
        var items = spec != null && !spec.contractItemsOrEmpty().isEmpty() ? spec.contractItemsOrEmpty()
                : contract == null ? List.<ClientContract.Item>of() : contract.itemsOrEmpty();
        var experience = spec != null && spec.experienceOrNull() != null ? spec.experienceOrNull().name()
                : contract != null && contract.experience() != null ? contract.experience() : "NONE";

        var stories = new ArrayList<DeliveryFacts.Story>();
        var scenarios = new ArrayList<DeliveryFacts.Scenario>();
        var total = 0;
        if (spec != null) {
            total = spec.incrementNumbers().stream().mapToInt(Integer::intValue).max().orElse(0);
            for (var inc : spec.incrementsOrEmpty()) {
                if (inc.number() > increment) continue;
                for (var s : spec.storiesOf(inc.number())) {
                    stories.add(new DeliveryFacts.Story(s.id(), inc.number(), "Como " + s.role() + " quiero "
                            + s.iWant() + (s.soThat() == null || s.soThat().isBlank() ? "" : " para " + s.soThat())));
                    // Solo llega aquí un incremento VERIFIED: la validación exige cobertura de todos sus escenarios.
                    for (var sc : s.scenariosOrEmpty()) {
                        scenarios.add(new DeliveryFacts.Scenario(sc.id(), sc.title(), inc.number(), "VERIFICADO"));
                    }
                }
            }
        }

        var controls = new ArrayList<DeliveryFacts.Control>();
        var levels = new ArrayList<DeliveryFacts.Level>();
        var screens = new ArrayList<String>();
        if (design != null) {
            if (design.game() != null) {
                design.game().controls().forEach(c -> controls.add(new DeliveryFacts.Control(c.action(), c.key(), c.effect())));
                for (var l : design.game().levels()) {
                    var name = items.stream().filter(i -> i.id().equals(l.item())).map(ClientContract.Item::name)
                            .findFirst().orElse(l.item());
                    var params = String.join(", ", l.parameters().stream().map(p -> p.name() + "=" + p.value()).toList());
                    levels.add(new DeliveryFacts.Level(l.item(), name, params, l.advanceRule()));
                }
            }
            if (design.app() != null && design.app().screens() != null) {
                design.app().screens().forEach(s -> screens.add(s.name() == null || s.name().isBlank() ? s.id() : s.name()));
            }
        }

        var testStep = verifiedRun == null ? null : verifiedRun.stepsOrEmpty().stream()
                .filter(s -> "test".equals(s.name())).findFirst().orElse(null);
        var passed = testStep == null ? 0 : testStep.testsPassed();
        var failed = testStep == null ? 0 : testStep.testsFailed();

        var dbs = new ArrayList<DeliveryFacts.Database>();
        try {
            databases.connectionsOf(missionId).forEach(c -> dbs.add(new DeliveryFacts.Database(c.name(), c.environment(),
                    c.host(), c.port(), c.database(), c.username(), sslMode(c.tls()))));
        } catch (Exception ex) {
            log.warn("DELIVERY_FACTS mission={} connections unreadable: {}", missionId, ex.getMessage());
        }

        var costUsd = 0.0;
        try {
            costUsd = cost.missionCost(missionId).totalUsd();
        } catch (Exception ex) {
            log.warn("DELIVERY_FACTS mission={} cost unreadable: {}", missionId, ex.getMessage());
        }

        return new DeliveryFacts(missionId, "", contract == null ? "" : nul(contract.summary()), experience,
                stackProfile, increment, total, Instant.now().toString(), commitSha, repoPath(missionId), zipPath,
                items.stream().map(i -> new DeliveryFacts.Item(i.id(), i.kind(), i.name(), i.description())).toList(),
                stories, scenarios, controls, levels, screens, passed, passed + failed, correctionRounds,
                targets, limitations, dbs, migrationNames, costUsd, agents(missionId));
    }

    private InteractionDesign design(String missionId) {
        try {
            var v = designs.approved(missionId);
            return v == null || v.isEmpty() ? null : json.readValue(v.get().designJson(), InteractionDesign.class);
        } catch (Exception ex) {
            log.warn("DELIVERY_FACTS mission={} design unreadable: {}", missionId, ex.getMessage());
            return null;
        }
    }

    private String repoPath(String missionId) {
        try {
            return String.valueOf(workspace.missionWorkspace(missionId));
        } catch (Exception ex) {
            return "";
        }
    }

    private List<String> agents(String missionId) {
        try {
            var plan = missions.lastTeamPlanJson(missionId);
            if (plan == null || plan.isEmpty()) return List.of();
            var out = new LinkedHashSet<String>();
            json.readValue(plan.get(), TeamPlan.class).tasks().forEach(t -> out.add(t.agentId()));
            return List.copyOf(out);
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** El mismo mapeo que {@code JdbcPostgresConnector}. */
    static String sslMode(String tls) {
        return switch (tls == null ? "" : tls) {
            case "DISABLE" -> "disable";
            case "REQUIRE" -> "require";
            case "VERIFY_FULL" -> "verify-full";
            default -> "";
        };
    }

    private static String nul(String s) {
        return s == null ? "" : s;
    }
}
