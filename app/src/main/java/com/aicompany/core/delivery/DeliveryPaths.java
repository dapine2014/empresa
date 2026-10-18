package com.aicompany.core.delivery;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Rutas de las entregas: la real (dentro del contenedor) y la que ve el fundador en su máquina
 * ({@code products.deliveries-host-path}, solo para mostrar).
 */
@Component
public class DeliveryPaths {

    private final Path root;
    private final String hostPath;
    private final Path productsRoot;
    private final String productsHostPath;

    @Autowired
    public DeliveryPaths(@Value("${products.deliveries-root}") String root,
                         @Value("${products.deliveries-host-path:~/entregas-forja}") String hostPath,
                         @Value("${products.workspace-root}") String productsRoot,
                         @Value("${products.workspace-host-path:~/forjai-products}") String productsHostPath) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.hostPath = hostPath;
        this.productsRoot = Path.of(productsRoot).toAbsolutePath().normalize();
        this.productsHostPath = productsHostPath;
    }

    /** Solo las entregas (sin mapeo de los repositorios). */
    public DeliveryPaths(String root, String hostPath) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.hostPath = hostPath;
        this.productsRoot = null;
        this.productsHostPath = null;
    }

    /** La ruta real con el prefijo de deliveries-root cambiado por el del equipo del fundador. */
    public String display(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        var real = Path.of(path).toAbsolutePath().normalize();
        if (real.startsWith(root)) {
            return mapped(root, hostPath, real);
        }
        if (productsRoot != null && real.startsWith(productsRoot)) {
            return mapped(productsRoot, productsHostPath, real);
        }
        return path;
    }

    private static String mapped(Path from, String hostBase, Path real) {
        var rel = from.relativize(real).toString();
        var base = hostBase.endsWith("/") ? hostBase.substring(0, hostBase.length() - 1) : hostBase;
        return rel.isEmpty() ? base : base + "/" + rel;
    }

    /** La ruta real si es un archivo dentro de deliveries-root; si no, null. */
    public Path insideRoot(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        var real = Path.of(path).toAbsolutePath().normalize();
        return real.startsWith(root) ? real : null;
    }
}
