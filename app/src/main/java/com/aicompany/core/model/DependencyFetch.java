package com.aicompany.core.model;

import java.util.List;

/** Resultado de FETCH_DEPENDENCIES del sandbox-runner (mismo JSON que sandbox-runner/FetchedPackage.FetchResult). */
public record DependencyFetch(String jobId, String status, String outputTail, List<FetchedPackage> packages) {

    public record FetchedPackage(String name, String version, String licenseExpression, String licenseText,
                                 boolean buildCode, List<String> buildCodeFiles) {
    }

    public List<FetchedPackage> packagesOrEmpty() {
        return packages == null ? List.of() : packages;
    }
}
