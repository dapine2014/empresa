package com.forjai.sandbox;

import java.util.List;

public record FetchedPackage(String name, String version, String licenseExpression, String licenseText,
                             boolean buildCode, List<String> buildCodeFiles) {

    public record FetchResult(String jobId, String status, String outputTail, List<FetchedPackage> packages) {
    }
}
