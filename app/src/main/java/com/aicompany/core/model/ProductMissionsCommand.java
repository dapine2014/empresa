package com.aicompany.core.model;

import java.util.List;

/** Misiones que respaldan la demanda (discovery) y las que construyen el producto. */
public record ProductMissionsCommand(List<String> validatedBy, List<String> builtBy) {
}
