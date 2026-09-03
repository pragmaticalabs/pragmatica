// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.FileOps.exists;
import static org.pragmatica.lang.Result.success;


public final class ConfigValidator {
    private static final Set<Integer> VALID_NODE_COUNTS = Set.of(3, 5, 7);
    private static final Pattern HEAP_PATTERN = Pattern.compile("^\\d+[mMgG]$");
    private static final Set<String> VALID_GC = Set.of("zgc", "g1");

    private ConfigValidator() {}

    public static Result<AetherConfig> validate(AetherConfig config) {
        var errors = new ArrayList<String>();

        clusterErrors(config.cluster(), errors);
        nodeErrors(config.node(), errors);
        absenceWindowErrors(config.timeouts().cluster(),
                            errors);
        streamingErrors(config.streaming(), errors);
        if (config.tlsEnabled()) {
            config.tls().onPresent(tls -> tlsErrors(tls, errors));
        }

        return toResult(config, errors);
    }

    /// #590 — the two absence windows are the two halves of one mechanism and their ORDER is a
    /// correctness property, not a preference. A community must stop serving before the core hands its
    /// slices to other nodes; inverted (or equal) windows put both live on the same slices at once.
    ///
    /// Reported rather than clamped: substituting a working pair would hide that the operator asked
    /// for something whose failure mode is two live writers. Reported here rather than thrown from a
    /// factory so it joins every other config problem in one collected report.
    private static void absenceWindowErrors(TimeoutsConfig.ClusterTimeouts cluster, List<String> errors) {
        if (!cluster.absenceWindowsOrdered()) {
            errors.add(("timeouts.cluster.core_absence (%s) must be strictly less than "
                       + "timeouts.cluster.community_absence (%s): a community has to stop serving before the core "
                       + "re-places its slices, or both run at once").formatted(cluster.coreAbsence(),
                                                                                cluster.communityAbsence()));
        }
    }

    /// `reshuffle_concurrency` bounds how many partitions one node materializes+backfills at once. Zero or
    /// negative would stall every REPLICA materialization permanently — the exact starvation the bound
    /// exists to pace — so it is rejected here rather than silently floored, and joins the collected report
    /// with every other config problem.
    private static void streamingErrors(StreamingConfig streaming, List<String> errors) {
        if (streaming.reshuffleConcurrency() < 1) {
            errors.add("streaming.reshuffle_concurrency must be >= 1 (0 would stall every replica backfill). Got: " + streaming.reshuffleConcurrency());
        }
        // A negative bound would reject every peer including a perfectly in-sync one, so reads would stop
        // being served from replicas and the ring-release catch-up gate could never be satisfied. Zero is
        // ALLOWED and means "exact watermark parity", which is legitimate though very strict: replication
        // is asynchronous, so a healthy peer is transiently behind on every write.
        if (streaming.caughtUpMaxLagOffsets() < 0) {
            errors.add("streaming.caught_up_max_lag_offsets must be >= 0 (a negative bound rejects every replica, "
                      + "stopping replica-served reads and blocking the ring-release catch-up gate). Got: " + streaming.caughtUpMaxLagOffsets());
        }
    }

    private static Result<AetherConfig> toResult(AetherConfig config, List<String> errors) {
        return errors.size() == 0
               ? success(config)
               : ConfigError.validationFailed(errors).result();
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
            errors.add("Port ranges overlap. Management: " + ports.management()
                      + "-" + mgmtEnd
                      + ", Cluster: " + clusterStart
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

        if (!HEAP_PATTERN.matcher(heap).matches()) {
            errors.add("Invalid heap format: " + heap + ". Use: 256m, 512m, 1g, 2g, 4g");
        }
    }

    private static void gcErrors(NodeConfig node, List<String> errors) {
        var gc = node.gc().toLowerCase();
        var isValid = VALID_GC.stream().anyMatch(gc::equals);

        if (!isValid) {
            errors.add("Invalid GC: " + node.gc() + ". Valid options: zgc, g1");
        }
    }

    private static void durationErrors(NodeConfig node, List<String> errors) {
        positiveTimeSpanError(node.metricsInterval(), "Metrics interval", errors);
        positiveTimeSpanError(node.reconciliation(), "Reconciliation interval", errors);
    }

    private static void positiveTimeSpanError(TimeSpan timeSpan, String name, List<String> errors) {
        if (timeSpan.millis() <= 0) {
            errors.add(name + " must be positive. Got: " + timeSpan.millis() + "ms");
        }
    }

    private static void tlsErrors(TlsConfig tls, List<String> errors) {
        if (!tls.autoGenerate()) {
            tlsPathErrors(tls, errors);
            tlsRequiredErrors(tls, errors);
        }
    }

    private static void tlsPathErrors(TlsConfig tls, List<String> errors) {
        tls.certFile().onPresent(path -> fileExistsError(path, "Certificate file", errors));
        tls.keyFile().onPresent(path -> fileExistsError(path, "Private key file", errors));
        tls.caFile().onPresent(path -> fileExistsError(path, "CA certificate file", errors));
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
        if (!exists(path)) {
            errors.add(fileType + " not found: " + path);
        }
    }

    public sealed interface ConfigError extends Cause {
        record unused() implements ConfigError {
            @Override
            public String message() {
                return "unused";
            }
        }

        record ValidationFailed(List<String> errors) implements ConfigError {
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

    /// #782 — a cluster is at least three nodes; there is no supported single-node topology.
    ///
    /// This gate runs on the RESOLVED peer count a node actually assembles at boot
    /// (`Main`'s `peers.size()`, computed in `parsePeers` from `--peers=`/`CLUSTER_PEERS`/cloud
    /// discovery/config, in that order) — not on the declarative `[cluster] nodes` TOML field
    /// `nodeCountErrors` above already checks. That existing check only fires when a TOML loads,
    /// and today `Main#loadConfigFile` discards ANY validation failure into `Option.none()`
    /// instead of aborting boot, so it never actually stops a sub-3-node start in practice.
    ///
    /// Kept as a separate top-level entry point — not folded into `validate(AetherConfig)` — so a
    /// caller holding only the resolved integer (no `AetherConfig` required, no TOML load
    /// required) can invoke it at the exact point the real count becomes known.
    public static Result<Unit> validateExpectedClusterSize(int expectedSize) {
        return expectedSize < MINIMUM_SUPPORTED_CLUSTER_SIZE
               ? ClusterSizeError.clusterTooSmall(expectedSize).result()
               : Result.unitResult();
    }

    private static final int MINIMUM_SUPPORTED_CLUSTER_SIZE = 3;

    public sealed interface ClusterSizeError extends Cause {
        record ClusterTooSmall(int size) implements ClusterSizeError {
            @Override
            public String message() {
                return "Expected cluster size " + size + " is not a supported topology: a cluster is "
                     + "at least three nodes. For a single machine, run the documented three-container "
                     + "quick start (docs/operators/docker-deployment.md, section "
                     + "\"Single machine (three containers)\") instead of one node.";
            }
        }

        static ClusterSizeError clusterTooSmall(int size) {
            return new ClusterTooSmall(size);
        }
    }
}
