package com.aicompany.core.prospecting;

import java.util.List;

public record ProspectBatch(List<ProspectCandidate> prospects) {

    public ProspectBatch {
        prospects = prospects == null ? List.of() : List.copyOf(prospects);
    }
}
