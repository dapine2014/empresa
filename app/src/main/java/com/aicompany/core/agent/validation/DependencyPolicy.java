package com.aicompany.core.agent.validation;

import com.aicompany.core.model.DependencyRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Política de dependencias (spec §3): APPROVED si no hay vulnerabilidades HIGH/CRITICAL, la licencia está permitida y
 * no trae código que se ejecute al compilar; si falla algo, PENDING_APPROVAL (🔴) con los motivos exactos. OSV que no
 * responde nunca es una aprobación.
 */
public final class DependencyPolicy {

    public record PackageFacts(DependencyRef ref, String license, boolean buildCode, List<String> buildCodeFiles,
                               Optional<List<String>> vulnerabilities) {
    }

    public record Decision(String status, List<String> reasons) {
    }

    private DependencyPolicy() {
    }

    public static Decision decide(PackageFacts facts) {
        var reasons = new ArrayList<String>();
        if (facts.vulnerabilities().isEmpty()) {
            reasons.add("No se pudo consultar OSV: no se aprueba sin revisar vulnerabilidades.");
        } else if (!facts.vulnerabilities().get().isEmpty()) {
            reasons.add("Vulnerabilidades HIGH/CRITICAL o sin severidad: " + facts.vulnerabilities().get());
        }
        if (facts.license() == null) {
            reasons.add("Licencia no reconocida o fuera de la lista permitida (MIT, Apache-2.0, BSD-2/3-Clause, ISC, Zlib).");
        }
        if (facts.buildCode()) {
            reasons.add("Trae código que se ejecuta al compilar: " + facts.buildCodeFiles());
        }
        return new Decision(reasons.isEmpty() ? "APPROVED" : "PENDING_APPROVAL", reasons);
    }
}
