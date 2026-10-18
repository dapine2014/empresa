package com.aicompany.core.model;

import com.aicompany.core.agent.model.RequirementsSpec;

/** Spec 2026-10-03 Aria §2: requisitos de una misión (versión vigente, aprobada e incremento construido). */
public record RequirementsView(String missionId, String status, Integer version, String versionStatus,
                               Integer approvedVersion, int builtIncrement, int incrementsTotal, RequirementsSpec spec,
                               String summary) {
}
