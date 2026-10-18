package com.aicompany.core.model;

import com.aicompany.core.agent.model.ClientContract;

/** Contrato (spec 2026-10-16 contrato §1): el último contrato de la misión, su estado y el aprobado. */
public record ContractView(int version, String status, Integer approvedVersion, ClientContract contract, String summary) {
}
