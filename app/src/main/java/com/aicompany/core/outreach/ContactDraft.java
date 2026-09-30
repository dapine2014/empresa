package com.aicompany.core.outreach;

import java.time.Instant;

/** Correo a un prospecto: PENDING_APPROVAL → APPROVED → SENT, o DISCARDED. */
public record ContactDraft(String id, String prospectId, String prospectName, String productId, String to, String subject,
                           String body, String status, String error, Instant createdAt, Instant sentAt) {
}
