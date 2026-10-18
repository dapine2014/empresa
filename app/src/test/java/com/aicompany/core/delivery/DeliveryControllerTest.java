package com.aicompany.core.delivery;

import com.aicompany.core.delivery.AndroidSigningKeyService.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryControllerTest {

    @TempDir
    Path root;

    private final DeliveryService deliveries = mock(DeliveryService.class);
    private final AndroidSigningKeyService keys = mock(AndroidSigningKeyService.class);

    private DeliveryController controller() {
        return new DeliveryController(deliveries, keys, new DeliveryPaths(root.toString(), "~/entregas-forja"));
    }

    private DeliveryRecord record(int n, Path zip) {
        return new DeliveryRecord("MISSION-X", n, "READY", zip.toString(), 3, List.of(), "", "2026-10-17T10:00:00Z");
    }

    @Test
    void downloadReturnsTheZipAsAttachment() throws Exception {
        var zip = Files.write(root.resolve("MISSION-X.zip"), new byte[]{1, 2, 3});
        when(deliveries.list("MISSION-X")).thenReturn(List.of(record(2, zip)));

        var response = controller().download("MISSION-X", 2);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("attachment; filename=\"MISSION-X-incremento-2.zip\"",
                response.getHeaders().getFirst("Content-Disposition"));
        assertEquals("application/zip", response.getHeaders().getContentType().toString());
        assertArrayEquals(new byte[]{1, 2, 3}, response.getBody().getContentAsByteArray());
    }

    @Test
    void downloadOfAnIncrementWithoutRecordOrFileIs404() throws Exception {
        when(deliveries.list("MISSION-X")).thenReturn(List.of(record(2, root.resolve("no-esta.zip"))));

        assertEquals(404, controller().download("MISSION-X", 5).getStatusCode().value());
        assertEquals(404, controller().download("MISSION-X", 2).getStatusCode().value());
    }

    @Test
    void downloadOutsideTheDeliveriesRootIs404(@TempDir Path elsewhere) throws Exception {
        var zip = Files.write(elsewhere.resolve("x.zip"), new byte[]{1});
        when(deliveries.list("MISSION-X")).thenReturn(List.of(record(1, zip)));

        assertEquals(404, controller().download("MISSION-X", 1).getStatusCode().value());
    }

    @Test
    void listShowsTheHostPath() throws Exception {
        var zip = Files.write(Files.createDirectories(root.resolve("MISSION-X")).resolve("a.zip"), new byte[]{1});
        when(deliveries.list("MISSION-X")).thenReturn(List.of(record(1, zip)));

        assertEquals("~/entregas-forja/MISSION-X/a.zip", controller().list("MISSION-X").get(0).path());
    }

    @Test
    void theKeyStatusNeverSerializesThePasswordOrTheKeystore() throws Exception {
        when(keys.status("MISSION-X")).thenReturn(new Status(true, "upload", "2026-10-17T10:00:00Z"));

        var jsonText = JsonMapper.builder().build().writeValueAsString(controller().signingKey("MISSION-X"));

        assertFalse(jsonText.contains("password"), jsonText);
        assertFalse(jsonText.contains("keystoreBase64"), jsonText);
        assertTrue(jsonText.contains("upload"), jsonText);
    }

    @Test
    void putDelegatesToTheServiceAndDeleteIsForwarded() {
        when(keys.save("MISSION-X", "QUJD", "upload", "pw")).thenReturn(new Status(true, "upload", "t"));

        var status = controller().saveSigningKey("MISSION-X", new DeliveryController.SigningKeyRequest("QUJD", "upload", "pw"));
        controller().deleteSigningKey("MISSION-X");

        assertTrue(status.configured());
        verify(keys).save("MISSION-X", "QUJD", "upload", "pw");
        verify(keys).delete("MISSION-X");
    }
}
