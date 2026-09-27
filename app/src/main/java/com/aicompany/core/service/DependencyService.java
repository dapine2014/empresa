package com.aicompany.core.service;

import com.aicompany.core.agent.validation.DependencyPolicy;
import com.aicompany.core.agent.validation.LicenseClassifier;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.BaselineDependencies;
import com.aicompany.core.model.DependencyRef;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dependencias gobernadas (spec §3): lo que no es baseline ni está aprobado se descarga aislado (FETCH_DEPENDENCIES),
 * cada paquete (incluidos transitivos) pasa OSV + licencia + código en el build, lo aprobado por política se promueve
 * a la caché y lo demás queda PENDING_APPROVAL (🔴) para el fundador.
 */
@Service
public class DependencyService {

    public record Outcome(List<DependencyRef> pending, List<DependencyRef> approvedNow, String error) {
    }

    private final DependencyMemoryService memory;
    private final SandboxRunnerClient runner;
    private final OsvClient osv;
    private final CompanyEventPublisher events;

    public DependencyService(DependencyMemoryService memory, SandboxRunnerClient runner, OsvClient osv,
                             CompanyEventPublisher events) {
        this.memory = memory;
        this.runner = runner;
        this.osv = osv;
        this.events = events;
    }

    public Outcome resolve(String missionId, String agentId, List<DependencyRef> requested) {

        var pending = new ArrayList<DependencyRef>();
        var approvedNow = new ArrayList<DependencyRef>();
        var errors = new ArrayList<String>();
        var missingByEcosystem = new LinkedHashMap<String, List<DependencyRef>>();

        for (var ref : requested.stream().filter(Objects::nonNull).distinct().toList()) {
            if (available(ref)) {
                continue;
            }
            var known = memory.status(ref);
            if (known.isPresent()) {
                pending.add(ref); // PENDING_APPROVAL o REJECTED: ya se evaluó, espera al fundador.
                continue;
            }
            missingByEcosystem.computeIfAbsent(ref.ecosystem(), k -> new ArrayList<>()).add(ref);
        }

        for (var entry : missingByEcosystem.entrySet()) {
            var ecosystem = entry.getKey();
            var fetch = runner.fetchDependencies(ecosystem, entry.getValue());
            if (fetch.isEmpty() || !"PASS".equals(fetch.get().status())) {
                errors.add("No se pudieron descargar " + entry.getValue().stream().map(DependencyRef::id).toList() + ": "
                        + fetch.map(f -> f.outputTail()).orElse(runner.lastError()));
                pending.addAll(entry.getValue());
                continue;
            }

            var jobId = fetch.get().jobId();
            var toPromote = new ArrayList<DependencyRef>();

            for (var pkg : fetch.get().packagesOrEmpty()) {
                var ref = new DependencyRef(ecosystem, pkg.name(), pkg.version());
                if (available(ref)) {
                    continue;
                }
                var license = ("NUGET".equals(ecosystem)
                        ? LicenseClassifier.nuget(pkg.licenseExpression())
                        : LicenseClassifier.text(pkg.licenseText())).orElse(null);
                var decision = DependencyPolicy.decide(new DependencyPolicy.PackageFacts(ref, license, pkg.buildCode(),
                        pkg.buildCodeFiles() == null ? List.of() : pkg.buildCodeFiles(), osv.blockingVulnerabilities(ref)));
                if ("APPROVED".equals(decision.status())) {
                    memory.record(ref, "APPROVED", "policy", List.of(), license, agentId, missionId, jobId);
                    toPromote.add(ref);
                } else {
                    memory.record(ref, "PENDING_APPROVAL", null, decision.reasons(), license, agentId, missionId, jobId);
                    pending.add(ref);
                    events.publish("EMPRESA_DEPENDENCY_REQUESTED", missionId, null, agentId,
                            Map.of("dependency", ref.id(), "reasons", decision.reasons()));
                }
            }

            if (!toPromote.isEmpty()) {
                if (runner.promoteDependencies(ecosystem, jobId, toPromote)) {
                    approvedNow.addAll(toPromote);
                    toPromote.forEach(ref -> events.publish("EMPRESA_DEPENDENCY_APPROVED", missionId, null, agentId,
                            Map.of("dependency", ref.id(), "approvedBy", "policy")));
                } else {
                    var reason = List.of("Aprobada por política pero no se pudo copiar a la caché: " + runner.lastError());
                    for (var ref : toPromote) {
                        memory.record(ref, "PENDING_APPROVAL", null, reason, null, agentId, missionId, jobId);
                    }
                    pending.addAll(toPromote);
                }
            }
        }

        return new Outcome(List.copyOf(pending), List.copyOf(approvedNow), errors.isEmpty() ? null : String.join("; ", errors));
    }

    private boolean available(DependencyRef ref) {
        return BaselineDependencies.contains(ref) || memory.status(ref).filter("APPROVED"::equals).isPresent();
    }
}
