// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.FileOps.exists;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.lang.Result.success;


public final class ConfigValidator {
    /// STRUCTURAL floor — below three no majority quorum exists. Deliberately NOT the supported
    /// minimum of 5 from the 2026-09-12 ruling. The policy minimum is enforced where configs are
    /// CREATED — `CoreWorkerSplit`, reached from `aether cluster init` and `scaffold`.
    ///
    /// WHAT A FAILURE HERE ACTUALLY DOES (#1019 round-1 review S1, corrected by #2052): `ConfigLoader.load` calls [#validate] and `Main`
    /// loads a GIVEN `--config=` file through it, so this runs on every such node boot, and a validation failure REFUSES the boot:
    /// `Main#resolveConfig` fails and `Main#refuseConfig` exits 65 with a FATAL line on stderr naming the file and the cause. (Before
    /// #2052 the failure was logged and discarded and the node booted on defaults without the file's TLS, port and secret settings.)
    ///
    /// That makes raising this floor as consequential as raising [ClusterSizeGate]'s: a 3-node cluster whose config stopped validating
    /// could no longer restart. With NO config file given nothing here runs, and [ClusterSizeGate] is the only floor on that boot.
    private static final int MINIMUM_CLUSTER_SIZE = 3;
    private static final TimeSpan OFFLINE_BUFFER_CAP_FLOOR = timeSpan(5).seconds();
    /// Upper bound on the CONSENSUS tier, not on the fleet. `[cluster] nodes` is the quorum basis
    /// (`TopologyConfig#clusterSize`) and every consensus round is broadcast across it. Fleet size is
    /// bounded separately by `ClusterConfig#maxNodes`, which #298 deliberately leaves UNBOUNDED, so
    /// capacity beyond this limit is added as workers rather than refused. Raised 7 -> 9 by #1019.
    ///
    /// This replaces a `VALID_NODE_COUNTS = Set.of(3, 5, 7)` constant that was DEAD — it had exactly
    /// one occurrence in the tree, its own declaration, while the live rule was the literal chain in
    /// [#nodeCountErrors]. Updating it would have changed no behaviour AND left a plausible-looking
    /// constant for the next reader to believe, which is worse than deleting it.
    ///
    /// The figure is [ConsensusTierBounds#MAXIMUM_CORE_NODES] rather than a local literal so that this
    /// bound, `ClusterBootstrapConfigValidator`'s and `ClusterTopologyManager#setDesiredCount`'s cannot
    /// drift apart — the exact failure the dead constant above demonstrates. Note this is a bound that
    /// was already here and is RAISED (it refused above 7 before); the ruling adds no NEW boot-path
    /// enforcement, and per the note on [#MINIMUM_CLUSTER_SIZE] a failure here does not refuse a boot.
    private static final int MAXIMUM_CLUSTER_SIZE = ConsensusTierBounds.MAXIMUM_CORE_NODES;
    private static final Pattern HEAP_PATTERN = Pattern.compile("^\\d+[mMgG]$");
    private static final Set<String> VALID_GC = Set.of("zgc", "g1");
    /// #250 review: floor below which a storage-maintenance pass (walks every lifecycle in every
    /// local tier) is more likely to be re-scheduled on top of itself than to finish and idle. Chosen
    /// two orders of magnitude below the 5-minute production default, not derived from a measured
    /// worst-case pass duration.
    private static final TimeSpan MIN_STORAGE_MAINTENANCE_INTERVAL = timeSpan(1).seconds();

    private ConfigValidator() {}

    public static Result<AetherConfig> validate(AetherConfig config) {
        var security = securityMisconfiguration(config.appHttp());

        if (security.isPresent()) {
            return security.unwrap()
                           .result();
        }

        var errors = new ArrayList<String>();

        clusterErrors(config.cluster(), errors);
        nodeErrors(config.node(), errors);
        absenceWindowErrors(config.timeouts().cluster(),
                            errors);
        offlineBufferCapErrors(config.timeouts().cluster(),
                               errors);
        storageMaintenanceErrors(config.timeouts().storageMaintenance(),
                                 errors);
        streamingErrors(config.streaming(), errors);
        promotionEscapeErrors(config.streaming(),
                              config.timeouts().swim().suspectTimeout(),
                              errors);
        archiveRetentionErrors(config.slice(), errors);
        if (config.tlsEnabled()) {
            config.tls().onPresent(tls -> tlsErrors(tls, errors));
        }

        return toResult(config, errors);
    }

    /// #909 — `security_mode = "jwt"` on a server that will serve, with nothing to verify a token against.
    /// The factory and `ConfigLoader` accept it (`jwtConfig` exists only when `jwks_url` is present), and
    /// the request-time deny floor from #888 only turns every non-public route into a `401`. A declared
    /// contradiction is refused here, as a typed [ConfigError.SecurityMisconfigured] that names the missing
    /// setting. A given `--config=` that fails validation refuses the boot (exit 65, #2052), so this is
    /// what stops the node. A disabled server refuses nothing, so it is not refused.
    private static Option<ConfigError> securityMisconfiguration(AppHttpConfig appHttp) {
        if (!appHttp.enabled() || appHttp.securityMode() != SecurityMode.JWT) {
            return Option.empty();
        }

        return appHttp.jwtConfig()
                      .fold(() -> Option.some(ConfigError.securityMisconfigured(JWT_WITHOUT_JWKS_REASON)),
                            jwt -> unusableJwksUrl(jwt.jwksUrl()));
    }

    private static Option<ConfigError> unusableJwksUrl(String jwksUrl) {
        return JwksUrl.jwksUrl(jwksUrl).fold(cause -> Option.some(ConfigError.securityMisconfigured("[app-http] security_mode = \"jwt\" but " + cause.message()
                                                                                                   + ". " + JWKS_URL_RULE)),
                                             _ -> Option.empty());
    }

    static final String JWKS_URL_RULE = "jwks_url must be an absolute https URL (http only to a loopback host).";

    static final String JWT_WITHOUT_JWKS_REASON = "[app-http] security_mode = \"jwt\" but [app-http] jwks_url is missing:"
                                                + " there is nothing to verify tokens against. jwks_url is required"
                                                + " (issuer/audience optional): set [app-http] jwks_url, or change security_mode.";

    /// #590 — the two absence windows are the two halves of one mechanism and their ORDER is a
    /// correctness property, not a preference. A community must stop serving before the core hands its
    /// slices to other nodes; inverted (or equal) windows put both live on the same slices at once.
    ///
    /// Reported rather than clamped: substituting a working pair would hide that the operator asked
    /// for something whose failure mode is two live writers. Reported here rather than thrown from a
    /// factory so it joins every other config problem in one collected report.
    /// #1996: the cap bounds how long ANY frame may wait in an offline buffer, including a request whose caller waits
    /// longer. A cap below the shortest caller wait the cluster ships with (the 5s replication ack) silently shortens
    /// that wait to the cap, and a zero or negative one expires every buffered frame. The 5s floor is a guess matching
    /// that wait, not a measured value.
    private static void offlineBufferCapErrors(TimeoutsConfig.ClusterTimeouts cluster, List<String> errors) {
        if (cluster.offlineBufferCap().nanos() < OFFLINE_BUFFER_CAP_FLOOR.nanos()) {
            errors.add("timeouts.cluster.offline_buffer_cap (%s) must be at least %s: a smaller cap cuts the wait of every buffered request".formatted(cluster.offlineBufferCap(),
                                                                                                                                                       OFFLINE_BUFFER_CAP_FLOOR));
        }
    }

    private static void absenceWindowErrors(TimeoutsConfig.ClusterTimeouts cluster, List<String> errors) {
        if (!cluster.absenceWindowsOrdered()) {
            errors.add(("timeouts.cluster.core_absence (%s) must be strictly less than "
                       + "timeouts.cluster.community_absence (%s): a community has to stop serving before the core "
                       + "re-places its slices, or both run at once").formatted(cluster.coreAbsence(),
                                                                                cluster.communityAbsence()));
        }
    }

    /// #250 review: `StorageMaintenanceDriver` schedules `demote()`+`collectGarbage()` on this interval
    /// unconditionally; a non-positive value would either fail scheduler wiring or spin the tick with
    /// no pacing, so it is rejected here rather than discovered at startup, joining the collected report
    /// with every other config problem.
    ///
    /// #250 review (round 2): positivity alone let 1ms through. A pass that iterates every lifecycle in
    /// every local tier must not run back to back with itself, so a positive-but-below-floor interval is
    /// reported as its own error rather than folded into the existing positivity message.
    private static void storageMaintenanceErrors(TimeoutsConfig.StorageMaintenanceTimeouts storageMaintenance,
                                                 List<String> errors) {
        var interval = storageMaintenance.interval();

        positiveTimeSpanError(interval, "Storage maintenance interval", errors);
        if (interval.millis() > 0 && interval.millis() < MIN_STORAGE_MAINTENANCE_INTERVAL.millis()) {
            errors.add("Storage maintenance interval must be at least " + MIN_STORAGE_MAINTENANCE_INTERVAL.millis()
                      + "ms (a pass that iterates all lifecycles must not run back to back). Got: " + interval.millis()
                      + "ms");
        }
    }

    /// `[slice] artifact_archive_retention` is the minimum age before a version may be archived (#1778). Zero or
    /// negative would let a version be archived the moment it is stored, and an unparseable value is carried here
    /// as a negative sentinel, so both are reported rather than defaulted.
    private static void archiveRetentionErrors(SliceConfig slice, List<String> errors) {
        if (slice.artifactArchiveRetention().millis() <= 0) {
            errors.add("slice.artifact_archive_retention must be a positive duration such as \"7d\" or \"36h\"");
        }

        if (slice.artifactMaxVersions() < 1) {
            errors.add("slice.artifact_max_versions must be at least 1. Got: " + slice.artifactMaxVersions());
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
        // #1604: 0 means "derive from the filesystem"; a negative cap has no meaning.
        if (streaming.segmentDiskMaxBytes() < 0) {
            errors.add("streaming.segment_disk_max_bytes must be >= 0 (0 derives the cap from the filesystem). Got: " + streaming.segmentDiskMaxBytes());
        }
    }

    /// `promotion_escape_after` (#2080) may not be below the alarm bounds it follows: the owner gate's (two SWIM suspect windows) and the
    /// replica contest's (`backfillSourceWaitBound`). An escape earlier than the alarm would go ahead before the operator was told anything
    /// was wrong, and earlier than a slow boot completes.
    private static void promotionEscapeErrors(StreamingConfig streaming, TimeSpan suspectTimeout, List<String> errors) {
        var alarm = StreamingConfig.ownerPromotionAlarmWindow(suspectTimeout);
        var contest = streaming.backfillSourceWaitBound();
        var floor = alarm.millis() >= contest.millis()
                    ? alarm
                    : contest;

        if (streaming.promotionEscapeAfter().millis() < floor.millis()) {
            errors.add("streaming.promotion_escape_after (%dms) must be at least %dms (the larger of two swim suspect_timeout windows and the replica contest's source-wait bound): an escape earlier than the alarm goes ahead before the operator is told".formatted(streaming.promotionEscapeAfter()
                                                                                                                                                                                                                                                                                .millis(),
                                                                                                                                                                                                                                                                       floor.millis()));
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

        if (nodes < MINIMUM_CLUSTER_SIZE) {
            errors.add("cluster.nodes is " + nodes
                      + ", below the structural minimum of " + MINIMUM_CLUSTER_SIZE
                      + ": fewer than three nodes have no majority quorum at all. Note the supported "
                      + "minimum for NEW clusters is 5 — a 3-node cluster has no fault budget during "
                      + "maintenance, since a rolling restart leaves 2 of 3 and any further fault "
                      + "loses quorum.");
        } else if (nodes % 2 == 0) {
            errors.add("cluster.nodes is " + nodes
                      + ", which is even. Quorum needs an odd count so that no split is a tie. Use 5, "
                      + "7 (recommended) or " + MAXIMUM_CLUSTER_SIZE
                      + ".");
        } else if (nodes > MAXIMUM_CLUSTER_SIZE) {
            errors.add("cluster.nodes is " + nodes
                      + ", above the maximum consensus tier of " + MAXIMUM_CLUSTER_SIZE
                      + ". cluster.nodes sizes the CONSENSUS tier, which every consensus round is "
                      + "broadcast across — it is not the fleet size, which cluster.max_nodes leaves "
                      + "unbounded. Keep it at " + MAXIMUM_CLUSTER_SIZE
                      + " or below and add further capacity as workers.");
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

        /// A security setting that contradicts itself (#909). Distinct from [ValidationFailed] so the refusal
        /// names the contradiction rather than a list of generic validation errors.
        record SecurityMisconfigured(String reason) implements ConfigError {
            @Override
            public String message() {
                return "Security misconfiguration: " + reason;
            }
        }

        static ConfigError securityMisconfigured(String reason) {
            return new SecurityMisconfigured(reason);
        }

        static ConfigError validationFailed(List<String> errors) {
            return ValidationFailed.validationFailed(List.copyOf(errors),
                                                     true)
                                   .unwrap();
        }
    }
}
