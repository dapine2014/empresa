package com.aicompany.core.evidence;

import java.util.List;

/**
 * Puerto de búsqueda web, desacoplado del proveedor concreto. La primera
 * implementación es {@link BraveSearchAdapter}; el objetivo de esta
 * interfaz es poder agregar después otros adaptadores (p. ej. un
 * `DuckDuckGoSearchAdapter` u otro) sin tocar
 * {@link EvidenceAcquisitionService} ni el resto de la empresa.
 */
public interface WebSearchPort {

    List<WebSearchResult> search(
            String query,
            String country,
            String language,
            int limit);
}
