package com.aicompany.core.service;

import com.aicompany.core.model.LeadResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProspectOutreachEmailTemplateTest {

    private final LeadResponse candidate = new LeadResponse(
            "MISSION-1-CANDIDATE-SALES-0",
            "Panadería El Sol",
            "Panadería artesanal con presencia en 3 barrios de Bogotá",
            "https://panaderiaelsol.com",
            "WEB",
            "MISSION-1",
            "MISSION-1-OPPORTUNITY",
            Instant.now(),
            "LEAD",
            null,
            null,
            0.72
    );

    @Test
    void subjectAndBodyContainRealCandidateData() {
        var subject = ProspectOutreachEmailTemplate.subject(candidate);
        var body = ProspectOutreachEmailTemplate.body(candidate);

        assertTrue(subject.contains("Panadería El Sol"));
        assertTrue(body.contains("Panadería El Sol"));
        assertTrue(body.contains("https://panaderiaelsol.com"));
    }

    @Test
    void neverQuotesTheAgentsInternalDescriptionVerbatimToTheProspect() {
        var body = ProspectOutreachEmailTemplate.body(candidate);

        assertFalse(body.contains("Panadería artesanal con presencia en 3 barrios de Bogotá"));
    }

    @Test
    void greetingIsGenericNotAssumingAPersonName() {
        var body = ProspectOutreachEmailTemplate.body(candidate);

        assertTrue(body.contains("equipo de Panadería El Sol"));
        assertFalse(body.contains("Estimado"));
        assertFalse(body.contains("Sr."));
        assertFalse(body.contains("Sra."));
    }

    @Test
    void neverContainsUnresolvedPlaceholders() {
        var body = ProspectOutreachEmailTemplate.body(candidate);
        var subject = ProspectOutreachEmailTemplate.subject(candidate);

        assertFalse(body.contains("{"));
        assertFalse(body.contains("}"));
        assertFalse(subject.contains("{"));
        assertFalse(subject.contains("}"));
    }
}
