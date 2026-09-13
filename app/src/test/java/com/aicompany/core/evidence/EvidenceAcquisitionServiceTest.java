package com.aicompany.core.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvidenceAcquisitionServiceTest {

    @Test
    void searchEvidenceBuildsCandidatesFromSearchResults() {
        var searchPort = mock(WebSearchPort.class);
        var pageFetcher = mock(WebPageFetcher.class);

        when(searchPort.search("precios asesoría PyME Colombia", "CO", "es", 10)).thenReturn(List.of(
                new WebSearchResult("Título", "https://example.com/a", "descripción")
        ));

        var service = new EvidenceAcquisitionService(searchPort, pageFetcher, "CO", "es");

        var candidates = service.searchEvidence("precios asesoría PyME Colombia");

        assertEquals(1, candidates.size());
        assertEquals("https://example.com/a", candidates.get(0).url());
        assertEquals("Título", candidates.get(0).title());
        assertEquals("WEB", candidates.get(0).sourceType());
        assertEquals("precios asesoría PyME Colombia", candidates.get(0).claim());
    }

    @Test
    void searchEvidenceReturnsEmptyListWhenNoResults() {
        var searchPort = mock(WebSearchPort.class);
        var pageFetcher = mock(WebPageFetcher.class);

        when(searchPort.search(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of());

        var service = new EvidenceAcquisitionService(searchPort, pageFetcher, "CO", "es");

        assertTrue(service.searchEvidence("consulta sin resultados").isEmpty());
    }

    @Test
    void confirmReachableNeverSetsVerifiedTrue() {
        var searchPort = mock(WebSearchPort.class);
        var pageFetcher = mock(WebPageFetcher.class);

        when(pageFetcher.fetch("https://example.com/a")).thenReturn("<html>contenido real</html>");

        var service = new EvidenceAcquisitionService(searchPort, pageFetcher, "CO", "es");

        var candidate = new EvidenceCandidate(
                "precio promedio del servicio", "https://example.com/a",
                "Título", "snippet", "WEB"
        );

        var evidence = service.confirmReachable(candidate);

        assertFalse(evidence.verified(),
                "confirmReachable NO debe marcar verified=true solo porque la URL respondió");
        assertEquals("https://example.com/a", evidence.source());
        assertEquals("WEB", evidence.sourceType());
        assertTrue(evidence.description().contains("precio promedio del servicio"));
    }

    @Test
    void confirmReachableFailsOnBlankContent() {
        var searchPort = mock(WebSearchPort.class);
        var pageFetcher = mock(WebPageFetcher.class);

        when(pageFetcher.fetch("https://example.com/vacio")).thenReturn("");

        var service = new EvidenceAcquisitionService(searchPort, pageFetcher, "CO", "es");

        var candidate = new EvidenceCandidate(
                "afirmación", "https://example.com/vacio", "Título", "snippet", "WEB"
        );

        assertThrows(IllegalStateException.class, () -> service.confirmReachable(candidate));
    }

    @Test
    void confirmReachableWorksEndToEndAgainstRealPublicUrl() {
        // Verificación en vivo real (sin API key, sin mocks en el fetcher):
        // WebPageFetcher real contra example.com.
        var searchPort = mock(WebSearchPort.class);
        var realFetcher = new WebPageFetcher();

        var service = new EvidenceAcquisitionService(searchPort, realFetcher, "CO", "es");

        var candidate = new EvidenceCandidate(
                "el dominio example.com existe y responde",
                "https://example.com/", "Example Domain", "snippet", "WEB"
        );

        var evidence = service.confirmReachable(candidate);

        assertFalse(evidence.verified());
        assertEquals("https://example.com/", evidence.source());
    }
}
