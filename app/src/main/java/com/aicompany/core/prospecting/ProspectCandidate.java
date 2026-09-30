package com.aicompany.core.prospecting;

/** Lo que devuelve Sofía por prospecto; Java lo valida antes de guardarlo. */
public record ProspectCandidate(String name, String url, String contactEmail, String contactFormUrl,
                                String contactSourceUrl, String fitReason) {
}
