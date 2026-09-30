package com.aicompany.core.prospecting;

import java.time.Instant;

/** Prospecto guardado (Customer {status:'LEAD'} unido a su producto por HAS_PROSPECT). */
public record Prospect(String id, String productId, String productName, String name, String url, String contactEmail,
                       String contactEmailSource, String contactFormUrl, String fitReason, String strategyId,
                       Instant foundAt, String outreachStatus) {
}
