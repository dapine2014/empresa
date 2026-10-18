package com.aicompany.core.delivery;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.util.List;

/** Entrega del producto (spec 2026-10-17): listar, descargar, generar y la clave de firma de Android. */
@RestController
@RequestMapping("/api/company/missions/{id}")
public class DeliveryController {

    public record DeliveryView(int increment, String status, String path, long sizeBytes,
                               List<DeliveryFacts.TargetResult> targets, String error, String createdAt) {
    }

    public record SigningKeyRequest(String keystoreBase64, String alias, String password) {
    }

    private final DeliveryService deliveries;
    private final AndroidSigningKeyService keys;
    private final DeliveryPaths paths;

    public DeliveryController(DeliveryService deliveries, AndroidSigningKeyService keys, DeliveryPaths paths) {
        this.deliveries = deliveries;
        this.keys = keys;
        this.paths = paths;
    }

    @GetMapping("/deliveries")
    public List<DeliveryView> list(@PathVariable String id) {
        return deliveries.list(id).stream().map(this::view).toList();
    }

    @PostMapping("/deliveries")
    public DeliveryView generate(@PathVariable String id) {
        return view(deliveries.deliverVerified(id));
    }

    @GetMapping("/deliveries/{n}/download")
    public ResponseEntity<Resource> download(@PathVariable String id, @PathVariable int n) {
        var record = deliveries.list(id).stream().filter(r -> r.increment() == n).findFirst().orElse(null);
        var file = record == null ? null : paths.insideRoot(record.path());
        if (file == null || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + "-incremento-" + n + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(new FileSystemResource(file));
    }

    @GetMapping("/android-signing-key")
    public AndroidSigningKeyService.Status signingKey(@PathVariable String id) {
        return keys.status(id);
    }

    @PutMapping("/android-signing-key")
    public AndroidSigningKeyService.Status saveSigningKey(@PathVariable String id, @RequestBody SigningKeyRequest body) {
        return keys.save(id, body.keystoreBase64(), body.alias(), body.password());
    }

    @DeleteMapping("/android-signing-key")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteSigningKey(@PathVariable String id) {
        keys.delete(id);
    }

    private DeliveryView view(DeliveryRecord r) {
        return new DeliveryView(r.increment(), r.status(), paths.display(r.path()), r.sizeBytes(),
                r.targets() == null ? List.of() : r.targets(), r.error(), r.createdAt());
    }
}
