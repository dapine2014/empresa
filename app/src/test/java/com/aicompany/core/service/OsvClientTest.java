package com.aicompany.core.service;

import com.aicompany.core.model.DependencyRef;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OsvClientTest {

    private MockRestServiceServer server;

    private OsvClient client() {
        var builder = RestClient.builder().baseUrl("https://osv.test");
        server = MockRestServiceServer.bindTo(builder).build();
        return new OsvClient(builder.build());
    }

    @Test
    void highAndUnknownSeverityAreBlockingLowIsNot() {
        var client = client();
        server.expect(requestTo("https://osv.test/v1/query"))
                .andExpect(jsonPath("$.package.name").value("A"))
                .andExpect(jsonPath("$.package.ecosystem").value("NuGet"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andRespond(withSuccess("""
                        {"vulns":[{"id":"GHSA-1","database_specific":{"severity":"HIGH"}},
                                  {"id":"GHSA-2","database_specific":{"severity":"LOW"}},
                                  {"id":"GHSA-3"}]}""", MediaType.APPLICATION_JSON));

        assertEquals(Optional.of(List.of("GHSA-1", "GHSA-3")), client.blockingVulnerabilities(new DependencyRef("NUGET", "A", "1.0.0")));
    }

    @Test
    void noVulnerabilitiesIsAnEmptyList() {
        var client = client();
        server.expect(requestTo("https://osv.test/v1/query"))
                .andExpect(jsonPath("$.package.ecosystem").value("Pub"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertEquals(Optional.of(List.of()), client.blockingVulnerabilities(new DependencyRef("PUB", "equatable", "2.0.5")));
    }

    // Review Focus: OSV no responde → vacío (la política lo deja pendiente).
    @Test
    void anUnavailableOsvIsEmpty() {
        var client = client();
        server.expect(requestTo("https://osv.test/v1/query")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertEquals(Optional.empty(), client.blockingVulnerabilities(new DependencyRef("NUGET", "A", "1.0.0")));
    }
}
