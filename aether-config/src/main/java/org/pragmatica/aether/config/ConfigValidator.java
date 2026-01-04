package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Cause;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates Aether configuration.
 *
 * <p>Validation rules:
 * <ul>
 *   <li>Node count must be odd (3, 5, 7) for quorum</li>
 *   <li>Node count must be at least 3</li>
 *   <li>Heap format must be valid (e.g., 256m, 1g)</li>
 *   <li>Ports must not conflict</li>
 *   <li>TLS certificate paths must exist if provided</li>
 * </ul>
 */
public final class ConfigValidator {
    private static final Set<Integer>VALID_NODE_COUNTS = Set.of(3, 5, 7);
    private static final Pattern HEAP_PATTERN = Pattern.compile("^\\d+[mMgG]$");
    private static final Set<String>VALID_GC = Set.of("zgc", "g1");

    private ConfigValidator() {}

    /**
     * Validate configuration, returning all validation errors.
     */
    public static Result<AetherConfig> validate(AetherConfig config) {
        var errors = new ArrayList<String>();
        validateCluster(config.cluster(), errors);
        validateNode(config.node(), errors);
        if (config.tlsEnabled() && config.tls() != null) {
            validateTls(config.tls(), errors);
        }
        if (errors.isEmpty()) {
            return Result.success(config);
        }
        return ConfigError.validationFailed(errors)
                          .result();
    }

    private static void validateCluster(ClusterConfig cluster, List<String> errors) {
        // Node count validation
        int nodes = cluster.nodes();
        if (nodes < 3) {
            errors.add("Minimum 3 nodes required for fault tolerance. Got: " + nodes);
        }else if (nodes % 2 == 0) {
            errors.add("Node count must be odd (3, 5, 7) for quorum. Got: " + nodes);
        }else if (nodes > 7) {
            errors.add("Maximum recommended node count is 7. Got: " + nodes
                       + ". More nodes add overhead without proportional benefit.");
        }
        // Port validation
        var ports = cluster.ports();
        if (ports.management() == ports.cluster()) {
            errors.add("Management port and cluster port must be different. Both are: " + ports.management());
        }
        if (ports.management() < 1 || ports.management() > 65535) {
            errors.add("Management port must be between 1 and 65535. Got: " + ports.management());
        }
        if (ports.cluster() < 1 || ports.cluster() > 65535) {
            errors.add("Cluster port must be between 1 and 65535. Got: " + ports.cluster());
        }
        // Check for port range overlap with node count
        int nodeCount = cluster.nodes();
        int mgmtEnd = ports.management() + nodeCount - 1;
        int clusterStart = ports.cluster();
        if (mgmtEnd >= clusterStart && ports.management() <= clusterStart + nodeCount - 1) {
            errors.add("Port ranges overlap. Management: " + ports.management() + "-" + mgmtEnd + ", Cluster: " + clusterStart
                       + "-" + (clusterStart + nodeCount - 1));
        }
    }

    private static void validateNode(NodeConfig node, List<String> errors) {
        // Heap validation
        String heap = node.heap();
        if (!HEAP_PATTERN.matcher(heap)
                         .matches()) {
            errors.add("Invalid heap format: " + heap + ". Use: 256m, 512m, 1g, 2g, 4g");
        }
        // GC validation
        String gc = node.gc()
                        .toLowerCase();
        if (!VALID_GC.contains(gc)) {
            errors.add("Invalid GC: " + node.gc() + ". Valid options: zgc, g1");
        }
        // Interval validation
        if (node.metricsInterval()
                .isNegative() || node.metricsInterval()
                                     .isZero()) {
            errors.add("Metrics interval must be positive. Got: " + node.metricsInterval());
        }
        if (node.reconciliation()
                .isNegative() || node.reconciliation()
                                     .isZero()) {
            errors.add("Reconciliation interval must be positive. Got: " + node.reconciliation());
        }
    }

    private static void validateTls(TlsConfig tls, List<String> errors) {
        if (!tls.autoGenerate()) {
            // User-provided certificates - check paths exist
            tls.certFile()
               .ifPresent(path -> {
                   if (!Files.exists(path)) {
                   errors.add("Certificate file not found: " + path);
               }
               });
            tls.keyFile()
               .ifPresent(path -> {
                   if (!Files.exists(path)) {
                   errors.add("Private key file not found: " + path);
               }
               });
            tls.caFile()
               .ifPresent(path -> {
                   if (!Files.exists(path)) {
                   errors.add("CA certificate file not found: " + path);
               }
               });
            // If not auto-generating, cert and key are required
            if (tls.certPath()
                   .isBlank()) {
                errors.add("TLS enabled but no certificate path provided. Set tls.auto_generate = true or provide tls.cert_path");
            }
            if (tls.keyPath()
                   .isBlank()) {
                errors.add("TLS enabled but no key path provided. Set tls.auto_generate = true or provide tls.key_path");
            }
        }
    }

    /**
     * Configuration validation error.
     */
    public sealed interface ConfigError extends Cause {
        record ValidationFailed(List<String> errors) implements ConfigError {
            @Override
            public String message() {
                return "Configuration validation failed:\n- " + String.join("\n- ", errors);
            }
        }

        static ConfigError validationFailed(List<String> errors) {
            return new ValidationFailed(List.copyOf(errors));
        }
    }
}
