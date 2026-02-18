package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.pragmatica.lang.Result.success;

/// Validates Aether configuration.
///
///
/// Validation rules:
///
///   - Node count must be odd (3, 5, 7) for quorum
///   - Node count must be at least 3
///   - Heap format must be valid (e.g., 256m, 1g)
///   - Ports must not conflict
///   - TLS certificate paths must exist if provided
///
public final class ConfigValidator {
    private static final Set<Integer> VALID_NODE_COUNTS = Set.of(3, 5, 7);
    private static final Pattern HEAP_PATTERN = Pattern.compile("^\\d+[mMgG]$");
    private static final Set<String> VALID_GC = Set.of("zgc", "g1");

    private ConfigValidator() {}

    /// Validate configuration, returning all validation errors.
    public static Result<AetherConfig> validate(AetherConfig config) {
        var errors = new ArrayList<String>();
        clusterErrors(config.cluster(), errors);
        nodeErrors(config.node(), errors);
        if (config.tlsEnabled()) {
            config.tls()
                  .onPresent(tls -> tlsErrors(tls, errors));
        }
        return toResult(config, errors);
    }

    private static Result<AetherConfig> toResult(AetherConfig config, List<String> errors) {
        return errors.size() == 0
               ? success(config)
               : ConfigError.validationFailed(errors)
                            .result();
    }

    private static void clusterErrors(ClusterConfig cluster, List<String> errors) {
        nodeCountErrors(cluster, errors);
        portErrors(cluster, errors);
    }

    private static void nodeCountErrors(ClusterConfig cluster, List<String> errors) {
        int nodes = cluster.nodes();
        if (nodes < 3) {
            errors.add("Minimum 3 nodes required for fault tolerance. Got: " + nodes);
        } else if (nodes % 2 == 0) {
            errors.add("Node count must be odd (3, 5, 7) for quorum. Got: " + nodes);
        } else if (nodes > 7) {
            errors.add("Maximum recommended node count is 7. Got: " + nodes
                       + ". More nodes add overhead without proportional benefit.");
        }
    }

    private static void portErrors(ClusterConfig cluster, List<String> errors) {
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
        portRangeOverlapErrors(cluster, errors);
    }

    private static void portRangeOverlapErrors(ClusterConfig cluster, List<String> errors) {
        int nodeCount = cluster.nodes();
        var ports = cluster.ports();
        int mgmtEnd = ports.management() + nodeCount - 1;
        int clusterStart = ports.cluster();
        if (mgmtEnd >= clusterStart && ports.management() <= clusterStart + nodeCount - 1) {
            errors.add("Port ranges overlap. Management: " + ports.management() + "-" + mgmtEnd + ", Cluster: " + clusterStart
                       + "-" + (clusterStart + nodeCount - 1));
        }
    }

    private static void nodeErrors(NodeConfig node, List<String> errors) {
        heapErrors(node, errors);
        gcErrors(node, errors);
        durationErrors(node, errors);
    }

    private static void heapErrors(NodeConfig node, List<String> errors) {
        String heap = node.heap();
        if (!HEAP_PATTERN.matcher(heap)
                         .matches()) {
            errors.add("Invalid heap format: " + heap + ". Use: 256m, 512m, 1g, 2g, 4g");
        }
    }

    private static void gcErrors(NodeConfig node, List<String> errors) {
        var gc = node.gc()
                     .toLowerCase();
        var isValid = VALID_GC.stream()
                              .anyMatch(gc::equals);
        if (!isValid) {
            errors.add("Invalid GC: " + node.gc() + ". Valid options: zgc, g1");
        }
    }

    private static void durationErrors(NodeConfig node, List<String> errors) {
        positiveDurationError(node.metricsInterval(), "Metrics interval", errors);
        positiveDurationError(node.reconciliation(), "Reconciliation interval", errors);
    }

    private static void positiveDurationError(Duration duration, String name, List<String> errors) {
        if (duration.isNegative() || duration.isZero()) {
            errors.add(name + " must be positive. Got: " + duration);
        }
    }

    private static void tlsErrors(TlsConfig tls, List<String> errors) {
        if (!tls.autoGenerate()) {
            tlsPathErrors(tls, errors);
            tlsRequiredErrors(tls, errors);
        }
    }

    private static void tlsPathErrors(TlsConfig tls, List<String> errors) {
        tls.certFile()
           .onPresent(path -> fileExistsError(path, "Certificate file", errors));
        tls.keyFile()
           .onPresent(path -> fileExistsError(path, "Private key file", errors));
        tls.caFile()
           .onPresent(path -> fileExistsError(path, "CA certificate file", errors));
    }

    private static void tlsRequiredErrors(TlsConfig tls, List<String> errors) {
        missingCertPathError(tls, errors);
        missingKeyPathError(tls, errors);
    }

    private static void missingCertPathError(TlsConfig tls, List<String> errors) {
        tls.certFile()
           .onEmpty(() -> errors.add("TLS enabled but no certificate path provided."
                                     + " Set tls.auto_generate = true or provide tls.cert_path"));
    }

    private static void missingKeyPathError(TlsConfig tls, List<String> errors) {
        tls.keyFile()
           .onEmpty(() -> errors.add("TLS enabled but no key path provided."
                                     + " Set tls.auto_generate = true or provide tls.key_path"));
    }

    private static void fileExistsError(Path path, String fileType, List<String> errors) {
        if (!Files.exists(path)) {
            errors.add(fileType + " not found: " + path);
        }
    }

    /// Configuration validation error.
    public sealed interface ConfigError extends Cause {
        record unused() implements ConfigError {
            @Override
            public String message() {
                return "unused";
            }
        }

        record ValidationFailed(List<String> errors) implements ConfigError {
            /// Factory method following JBCT naming convention.
            public static Result<ValidationFailed> validationFailed(List<String> errors, boolean validated) {
                return success(new ValidationFailed(List.copyOf(errors)));
            }

            @Override
            public String message() {
                return "Configuration validation failed:\n- " + String.join("\n- ", errors);
            }
        }

        static ConfigError validationFailed(List<String> errors) {
            return ValidationFailed.validationFailed(List.copyOf(errors),
                                                     true)
                                   .unwrap();
        }
    }
}
