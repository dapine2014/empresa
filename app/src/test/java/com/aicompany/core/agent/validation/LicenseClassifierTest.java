package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LicenseClassifierTest {

    @Test
    void nugetExpressions() {
        assertEquals(Optional.of("MIT"), LicenseClassifier.nuget("MIT"));
        assertEquals(Optional.of("MIT OR GPL-3.0"), LicenseClassifier.nuget("MIT OR GPL-3.0"));
        assertEquals(Optional.empty(), LicenseClassifier.nuget("MIT AND GPL-3.0"));
        assertEquals(Optional.empty(), LicenseClassifier.nuget(null));
    }

    @Test
    void licenseTexts() {
        assertEquals(Optional.of("MIT"), LicenseClassifier.text("MIT License\n\nPermission is hereby granted, free of charge, to any person"));
        assertEquals(Optional.of("Apache-2.0"), LicenseClassifier.text("Apache License\nVersion 2.0, January 2004"));
        assertEquals(Optional.of("BSD-3-Clause"), LicenseClassifier.text("Redistribution and use in source and binary forms ... Neither the name of"));
        assertEquals(Optional.of("BSD-2-Clause"), LicenseClassifier.text("Redistribution and use in source and binary forms, with or without modification"));
        assertEquals(Optional.empty(), LicenseClassifier.text("GNU GENERAL PUBLIC LICENSE Version 3"));
    }

    // Spec 2026-10-02 §2.5: la licencia de Npgsql (PostgreSQL License) es permisiva.
    @Test
    void thePostgresqlLicenseIsPermissive() {
        org.junit.jupiter.api.Assertions.assertTrue(LicenseClassifier.ALLOWED.contains("PostgreSQL"));
    }
}
