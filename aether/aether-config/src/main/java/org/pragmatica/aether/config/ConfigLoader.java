// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.pragmatica.aether.environment.ClusterSecretSource;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.ClusterIdentityEnv;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.DataSize;
import org.pragmatica.lang.parse.Number;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public final class ConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    /// The instances a node always has, placed on the default paths whether or not their section names any.
    private static final Set<String> SYNTHESIZED_INSTANCES = Set.of("artifacts", "content");
    /// The only key a `[storage.streams]` section carries (#634-3); the `streams` instance is built from the
    /// node's stream settings, not from this section.
    private static final Set<String> STREAMS_SECTION_KEYS = Set.of("wal_path");

    private ConfigLoader() {}

    public static Result<AetherConfig> load(Path path) {
        return TomlParser.parseFile(path).flatMap(doc -> loadDocument(doc, Map.of(), System::getenv, FileOps::exists));
    }

    public static Result<AetherConfig> loadFromString(String content) {
        return TomlParser.parse(content).flatMap(doc -> loadDocument(doc, Map.of(), System::getenv, FileOps::exists));
    }

    public static Result<AetherConfig> loadWithOverrides(Path path, Map<String, String> overrides) {
        return TomlParser.parseFile(path).flatMap(doc -> loadDocument(doc, overrides, System::getenv, FileOps::exists));
    }

    /// THE node load path, shared by every entry point and by cluster bootstrap's pre-flight ([#checkNodeDocument]):
    /// parse the document into a config, then run the configuration and storage-encryption validators. One function, so
    /// pre-flight and boot cannot drift apart; only the environment and the filesystem differ.
    ///
    /// ASSUMPTION (guarded by `ConfigLoaderPreflightTest#preflightVerdict_doesNotDependOnTheValuesTheEnvironmentSupplies`): the
    /// three values the environment feeds into the built config (the TLS cluster secret, the API keys, `${env:...}`
    /// placeholders of the cloud section) are read by NO validator, so an empty environment cannot change a verdict. A
    /// validator that starts reading one of them turns that test red, and has to decide what pre-flight does about it.
    private static Result<AetherConfig> loadDocument(TomlDocument doc,
                                                     Map<String, String> overrides,
                                                     Fn1<String, String> env,
                                                     Predicate<Path> fileExists) {
        return fromDocumentWithOverrides(doc, overrides, env).flatMap(config -> ConfigValidator.validate(config,
                                                                                                         fileExists))
                                        .flatMap(StorageEncryptionConfigValidator::validate);
    }

    public static AetherConfig aetherConfig(Environment env) {
        return AetherConfig.aetherConfig(env);
    }

    private static Result<AetherConfig> fromDocumentWithOverrides(TomlDocument doc,
                                                                  Map<String, String> overrides,
                                                                  Fn1<String, String> env) {
        var envStr = overrides.getOrDefault("environment",
                                            doc.getString("cluster", "environment").or("docker"));
        // #1455: every typed read above and below answers Option, so a present-but-wrong-typed value
        // (`port = "80x"`) took the reader's default; the document recorded each such read, and the
        // load refuses here naming all of them.
        return Environment.environment(envStr)
                          .flatMap(environment -> assembleConfig(doc,
                                                                 overrides,
                                                                 environment,
                                                                 environmentIsExplicit(doc, overrides),
                                                                 env))
                          .flatMap(config -> doc.requireNoTypeMismatches()
                                                .map(_ -> config));
    }

    @SuppressWarnings("JBCT-UTIL-01")
    private static Result<AetherConfig> assembleConfig(TomlDocument doc,
                                                       Map<String, String> overrides,
                                                       Environment environment,
                                                       boolean environmentExplicit,
                                                       Fn1<String, String> env) {
        try {
            var builder = populateBuilder(doc, environment, environmentExplicit, env);

            mergeCliOverrides(overrides, builder);

            return refuseRemovedTargetRf(doc).flatMap(_ -> refuseUnknownUploadKeys(doc))
                                        .flatMap(_ -> refuseNonPositiveStreamingMemory(doc))
                                        .flatMap(_ -> refuseUnsupportedSecurityModes(doc))
                                        .flatMap(_ -> parseReadLinearization(doc))
                                        .map(mode -> applyReadLinearization(builder.build(),
                                                                            mode))
                                        .flatMap(config -> refuseUnknownStorageSubSections(doc).flatMap(_ -> refuseBlankStoragePaths(doc))
                                                                                          .flatMap(_ -> attachSharedTiers(doc,
                                                                                                                          config)));
        } catch (IllegalArgumentException e) {
            return ConfigError.invalidConfig(e.getMessage()).result();
        }
    }

    /// #1777 track 1: the DHT takes its replication from the cluster's committed `[replication]` section, like
    /// streams. A node-local `[dht.replication] target_rf` would be a second source of truth — and one that
    /// could differ between nodes, which then disagree on placement — so a config that still sets it is refused,
    /// naming where the setting lives now, rather than silently ignored.
    private static Result<Unit> refuseRemovedTargetRf(TomlDocument doc) {
        return doc.getString("dht.replication", "target_rf")
                  .isPresent() || doc.getInt("dht.replication", "target_rf")
                                     .isPresent()
               ? ConfigError.invalidConfig("[dht.replication] target_rf is removed: the DHT replication factor and "
                                          + "confirmation factor come from the cluster's [replication] section "
                                          + "(replication_factor, confirmation_factor)").result()
               : Result.unitResult();
    }

    /// Parse the `[durable-entity] read-linearization` ops knob (spec §8.1, durable-entity primitive).
    /// Absent → the default no-op round. `no-op-round` → [ReadLinearizationMode#NO_OP_ROUND]. `lease` →
    /// REJECTED with a named error: the lease mechanism ships only once its clock-skew chaos validation
    /// gate is green, so deployments run no-op-round until then. Any other value is an invalid config.
    /// Result-based (like {@code Environment.environment}), so a rejection fails the whole config load.
    private static Result<ReadLinearizationMode> parseReadLinearization(TomlDocument doc) {
        return doc.getString("durable-entity", "read-linearization")
                  .fold(() -> success(StreamingConfig.DEFAULT_READ_LINEARIZATION),
                        ConfigLoader::readLinearizationOf);
    }

    private static Result<ReadLinearizationMode> readLinearizationOf(String raw) {
        return switch (raw.trim()) {
            case "no-op-round" -> success(ReadLinearizationMode.NO_OP_ROUND);
            case "lease" -> ConfigError.invalidConfig("lease linearization not implemented — use no-op-round").result();
            default -> ConfigError.invalidConfig("unknown read-linearization '" + raw + "' — use no-op-round").result();
        };
    }

    private static AetherConfig applyReadLinearization(AetherConfig config, ReadLinearizationMode mode) {
        return config.withStreaming(config.streaming().withReadLinearization(mode));
    }

    /// Whether the environment was SAID (a `[cluster] environment` key or a CLI override) rather than defaulted to docker. A composed
    /// cloud node TOML never says it, so it must not be treated as a Docker node by rules that only hold on a Docker node (#1968).
    static boolean environmentIsExplicit(TomlDocument doc, Map<String, String> overrides) {
        return overrides.containsKey("environment") || doc.getString("cluster", "environment")
                                                          .isPresent();
    }

    private static AetherConfig.Builder populateBuilder(TomlDocument doc,
                                                        Environment environment,
                                                        boolean environmentExplicit,
                                                        Fn1<String, String> env) {
        var builder = AetherConfig.builder().withEnvironment(environment);

        populateClusterConfig(doc, builder);
        populateNodeConfig(doc, builder);
        populateTlsConfig(doc, builder, environment, env);
        populateDockerConfig(doc, builder, environment);
        populateKubernetesConfig(doc, builder, environment);
        populateTtmConfig(doc, builder);
        populateSliceConfig(doc, builder);
        populateAppHttpConfig(doc, builder, env);
        populateBackupConfig(doc, builder, environment, environmentExplicit, env);
        populateDhtReplicationConfig(doc, builder);
        populateTimeoutsConfig(doc, builder);
        populateStorageConfig(doc, builder);
        populateStorageEncryptionConfig(doc, builder);
        populateCloudConfig(doc, builder, env);
        populateEndpointsConfig(doc, builder);
        populateStreamingConfig(doc, builder);
        populateMembershipConfig(doc, builder);
        populateAlertConfig(doc, builder);

        return builder;
    }

    /// Membership v2 — Stage 6 wiring. Maps the optional `[membership]` TOML section into
    /// a [`MembershipConfigBinding`]. Defaults (spec §14) are applied per-field when a
    /// key is absent, so a partial section is well-defined.
    ///
    /// E2 Phase 2a (2026-05-28): the `ntt_observation` migration-ramp feature flag is
    /// removed from the binding shape; if present in TOML it is silently ignored. NTT
    /// instrumentation now wires unconditionally.
    ///
    /// cluster-topology-overhaul Wave 9 (item 2, CONFIG-BREAKING): the former
    /// `ntt_departure_timeout` + `quorum_loss_drain_threshold` keys are collapsed into a single
    /// `split_timeout` (`T`). The legacy keys are no longer accepted — only `split_timeout` is read,
    /// defaulting per spec §14 when absent.
    private static void populateMembershipConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var hasSection = doc.sectionNames().stream().anyMatch("membership"::equals);

        if (!hasSection) {
            return;
        }

        var splitTimeout = parseTimeSpan(doc,
                                         "membership",
                                         "split_timeout",
                                         MembershipConfigBinding.DEFAULT_SPLIT_TIMEOUT);

        builder.membership(new MembershipConfigBinding(splitTimeout));
    }

    private static void populateStreamingConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var hasSection = doc.sectionNames().stream().anyMatch(s -> s.equals("streaming"));

        if (!hasSection) {
            return;
        }

        var defaults = StreamingConfig.streamingConfig();
        var publishTimeout = parseTimeSpan(doc, "streaming", "publish_forward_timeout", defaults.publishForwardTimeout());
        var readTimeout = parseTimeSpan(doc, "streaming", "read_forward_timeout", defaults.readForwardTimeout());
        var maxBytes = parseDataSize(doc, "streaming", "max_read_response_bytes", defaults.maxReadResponseBytes());
        var reshuffleConcurrency = parseInt(doc, "streaming", "reshuffle_concurrency", defaults.reshuffleConcurrency());
        var caughtUpMaxLagOffsets = parseLong(doc,
                                              "streaming",
                                              "caught_up_max_lag_offsets",
                                              defaults.caughtUpMaxLagOffsets());
        var segmentDiskMaxBytes = parseDataSize(doc,
                                                "streaming",
                                                "segment_disk_max_bytes",
                                                defaults.segmentDiskMaxBytes());
        var dhtSegmentMaxBytes = parseDataSize(doc, "streaming", "dht_segment_max_bytes", defaults.dhtSegmentMaxBytes());
        var isrLagMax = parseTimeSpan(doc, "streaming", "isr_lag_max", defaults.isrLagMax());
        var promotionEscapeAfter = parseTimeSpan(doc,
                                                 "streaming",
                                                 "promotion_escape_after",
                                                 defaults.promotionEscapeAfter());
        var sealRunBytes = parseDataSize(doc, "streaming", "seal_run_bytes", defaults.sealRunBytes());
        var sealRunEvents = parseInt(doc, "streaming", "seal_run_events", defaults.sealRunEvents());
        var sealRunAge = parseTimeSpan(doc, "streaming", "seal_run_age", defaults.sealRunAge());
        var sealMergeBytes = parseDataSize(doc, "streaming", "seal_merge_bytes", defaults.sealMergeBytes());
        var maxMemoryBytes = parseDataSize(doc, "streaming", "max_memory_bytes", defaults.maxMemoryBytes());
        var sealPendingCapBytes = parseDataSize(doc, "streaming", "seal_pending_cap_bytes", defaults.sealPendingCapBytes());

        builder.streaming(StreamingConfig.streamingConfig(publishTimeout,
                                                          readTimeout,
                                                          maxBytes,
                                                          defaults.readLinearization(),
                                                          reshuffleConcurrency,
                                                          caughtUpMaxLagOffsets)
                                         .withSegmentDiskMaxBytes(segmentDiskMaxBytes)
                                         .withDhtSegmentMaxBytes(dhtSegmentMaxBytes)
                                         .withIsrLagMax(isrLagMax)
                                         .withPromotionEscapeAfter(promotionEscapeAfter)
                                         .withSealRun(sealRunBytes, sealRunEvents, sealRunAge, sealMergeBytes)
                                         .withMemoryBudget(maxMemoryBytes, sealPendingCapBytes));
    }

    /// #2201 C-03 (spec 4.4 items 1 and 3, D-12): a written `[streaming] max_memory_bytes` or `seal_pending_cap_bytes` must be
    /// a positive size. Unset derives it (0 is the derive sentinel in the built config), so a written zero or negative is
    /// refused here naming the key, never read as "derive". Text that is no size at all is refused by the #1455 gate.
    private static Result<Unit> refuseNonPositiveStreamingMemory(TomlDocument doc) {
        return Option.from(Stream.of("max_memory_bytes", "seal_pending_cap_bytes")
                                 .filter(key -> writtenNonPositive(doc, key))
                                 .findFirst())
                     .fold(Result::unitResult,
                           key -> ConfigError.invalidConfig("[streaming] " + key + " must be a positive size such as 512MB; omit it"
                                                            + " to derive it. Got: " + doc.getString("streaming", key)
                                                                                          .or(""))
                                             .result());
    }

    private static boolean writtenNonPositive(TomlDocument doc, String key) {
        return doc.getString("streaming", key)
                  .flatMap(ConfigLoader::sizeOrWholeNumber)
                  .filter(bytes -> bytes <= 0)
                  .isPresent();
    }

    private static Option<Long> sizeOrWholeNumber(String raw) {
        return DataSize.dataSize(raw)
                       .map(DataSize::bytes)
                       .option()
                       .orElse(() -> Number.parseLong(raw)
                                           .option());
    }

    private static long parseDataSize(TomlDocument doc, String section, String key, long defaultValue) {
        var stringVal = doc.getString(section, key).flatMap(v -> DataSize.dataSize(v).option()).map(DataSize::bytes);

        if (stringVal.isPresent()) {
            return stringVal.or(defaultValue);
        }

        return doc.getLong(section, key)
                  .or(defaultValue);
    }

    /// A present but unparseable retention is NOT defaulted: it becomes [SliceConfig#INVALID_ARTIFACT_ARCHIVE_RETENTION],
    /// which `ConfigValidator` reports, so a typo cannot silently run the store under a period nobody chose.
    private static TimeSpan parseRetentionOrInvalid(String value) {
        return org.pragmatica.lang.parse.TimeSpan.timeSpan(value)
                                                 .option()
                                                 .map(ts -> TimeSpan.fromDuration(ts.duration()))
                                                 .or(SliceConfig.INVALID_ARTIFACT_ARCHIVE_RETENTION);
    }

    private static TimeSpan parseTimeSpan(TomlDocument doc, String section, String key, TimeSpan defaultValue) {
        return doc.getParsed(section,
                             key,
                             "a duration such as 30s",
                             v -> org.pragmatica.lang.parse.TimeSpan.timeSpan(v)
                                                                    .option())
                  .map(ts -> TimeSpan.fromDuration(ts.duration()))
                  .or(defaultValue);
    }

    private static long parseLong(TomlDocument doc, String section, String key, long defaultValue) {
        return doc.getLong(section, key)
                  .or(defaultValue);
    }

    private static int parseInt(TomlDocument doc, String section, String key, int defaultValue) {
        return doc.getInt(section, key)
                  .or(defaultValue);
    }

    private static int parseDataSize(TomlDocument doc, String section, String key, int defaultValue) {
        return doc.getParsed(section,
                             key,
                             "a size such as 64MB",
                             v -> DataSize.dataSize(v).option())
                  .map(DataSize::bytesAsInt)
                  .or(defaultValue);
    }

    @SuppressWarnings("JBCT-RET-07")
    private static void populateClusterConfig(TomlDocument doc, AetherConfig.Builder builder) {
        doc.getInt("cluster", "nodes").onPresent(builder::nodes);
        doc.getBoolean("cluster", "tls").onPresent(builder::tls);
        doc.getInt("cluster", "core_max").onPresent(builder::coreMax);
        doc.getInt("cluster", "max_nodes").onPresent(builder::maxNodes);
        builder.ports(portsFromDocument(doc));
    }

    private static PortsConfig portsFromDocument(TomlDocument doc) {
        var mgmtPort = doc.getInt("cluster.ports", "management").or(PortsConfig.DEFAULT_MANAGEMENT_PORT);
        var clusterPort = doc.getInt("cluster.ports", "cluster").or(PortsConfig.DEFAULT_CLUSTER_PORT);
        var mgmtProtocol = doc.getParsed("cluster.ports",
                                         "management_protocol",
                                         "one of: h1, h3, both",
                                         HttpProtocol::httpProtocol)
                              .or(HttpProtocol.H1);

        return PortsConfig.portsConfig(mgmtPort, clusterPort, mgmtProtocol).unwrap();
    }

    @SuppressWarnings("JBCT-RET-07")
    private static void populateNodeConfig(TomlDocument doc, AetherConfig.Builder builder) {
        doc.getString("node", "heap").onPresent(builder::heap);
        doc.getString("node", "gc").onPresent(builder::gc);
    }

    private static void populateTlsConfig(TomlDocument doc,
                                          AetherConfig.Builder builder,
                                          Environment environment,
                                          Fn1<String, String> env) {
        var tlsEnabled = isTlsEnabled(doc, environment);

        if (tlsEnabled) {
            builder.tlsConfig(tlsFromDocument(doc, env));
        }
    }

    private static boolean isTlsEnabled(TomlDocument doc, Environment environment) {
        return doc.getBoolean("cluster", "tls")
                  .or(environment.defaultTls());
    }

    /// The `[tls]` section with the host environment supplied: `cluster_secret`, else the ambient secret (`AETHER_CLUSTER_SECRET` or
    /// the file `AETHER_CLUSTER_SECRET_FILE` names, #828).
    static TlsConfig tlsFromDocument(TomlDocument doc, Fn1<String, String> env) {
        var autoGen = doc.getBoolean("tls", "auto_generate").or(true);
        var certPath = doc.getString("tls", "cert_path").or("");
        var keyPath = doc.getString("tls", "key_path").or("");
        var caPath = doc.getString("tls", "ca_path").or("");
        var clusterSecret = doc.getString("tls", "cluster_secret")
                               .orElse(() -> ClusterSecretSource.resolve(env).fold(_ -> Option.<String> none(),
                                                                                   value -> value))
                               .or("");

        return new TlsConfig(autoGen, certPath, keyPath, caPath, clusterSecret);
    }

    private static void populateDockerConfig(TomlDocument doc, AetherConfig.Builder builder, Environment environment) {
        if (environment == Environment.DOCKER) {
            builder.dockerConfig(dockerFromDocument(doc));
        }
    }

    private static DockerConfig dockerFromDocument(TomlDocument doc) {
        var network = doc.getString("docker", "network").or(DockerConfig.DEFAULT_NETWORK);
        var image = doc.getString("docker", "image").or(DockerConfig.DEFAULT_IMAGE);

        return DockerConfig.dockerConfig(network, image).unwrap();
    }

    private static void populateKubernetesConfig(TomlDocument doc,
                                                 AetherConfig.Builder builder,
                                                 Environment environment) {
        if (environment == Environment.KUBERNETES) {
            builder.kubernetesConfig(kubernetesFromDocument(doc));
        }
    }

    private static KubernetesConfig kubernetesFromDocument(TomlDocument doc) {
        var namespace = doc.getString("kubernetes", "namespace").or(KubernetesConfig.DEFAULT_NAMESPACE);
        var serviceType = doc.getString("kubernetes", "service_type").or(KubernetesConfig.DEFAULT_SERVICE_TYPE);
        var storageClass = doc.getString("kubernetes", "storage_class").or("");

        return KubernetesConfig.kubernetesConfig(namespace, serviceType, storageClass).unwrap();
    }

    private static void populateTtmConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var ttmEnabled = doc.getBoolean("ttm", "enabled").or(false);

        if (ttmEnabled) {
            builder.ttm(ttmFromDocument(doc));
        }
    }

    private static TtmConfig ttmFromDocument(TomlDocument doc) {
        var modelPath = doc.getString("ttm", "model_path").or("models/ttm-aether.onnx");
        var inputWindow = doc.getInt("ttm", "input_window_minutes").or(60);
        var predictionHorizon = doc.getInt("ttm", "prediction_horizon").or(1);
        var evalInterval = parseTimeSpanOrMs(doc,
                                             "ttm",
                                             "evaluation_interval",
                                             "evaluation_interval_ms",
                                             timeSpan(60).seconds());
        var confidence = doc.getDouble("ttm", "confidence_threshold").or(0.7);

        return TtmConfig.ttmConfig(modelPath, inputWindow, predictionHorizon, evalInterval, confidence, true).or(TtmConfig.ttmConfig());
    }

    @SuppressWarnings({"JBCT-STY-05", "JBCT-RET-07"})
    private static void populateSliceConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var retention = doc.getString("slice", "artifact_archive_retention")
                           .map(ConfigLoader::parseRetentionOrInvalid)
                           .or(SliceConfig.DEFAULT_ARTIFACT_ARCHIVE_RETENTION);

        builder.sliceConfig(doc.getStringList("slice", "repositories")
                               .map(repos -> SliceConfig.sliceConfigFromNames(repos))
                               .flatMap(Result::option)
                               .or(SliceConfig.sliceConfig())
                               .withArtifactArchiveRetention(retention)
                               .withArtifactMaxVersions(doc.getInt("slice", "artifact_max_versions")
                                                           .or(SliceConfig.DEFAULT_ARTIFACT_MAX_VERSIONS)));
    }

    @SuppressWarnings("JBCT-STY-05")
    private static void populateAppHttpConfig(TomlDocument doc, AetherConfig.Builder builder, Fn1<String, String> env) {
        var enabled = doc.getBoolean("app-http", "enabled").or(false);
        var port = doc.getInt("app-http", "port").or(AppHttpConfig.DEFAULT_APP_HTTP_PORT);
        var maxRequestSize = parseDataSize(doc, "app-http", "max_request_size", AppHttpConfig.DEFAULT_MAX_REQUEST_SIZE);
        var explicitMode = doc.getString("app-http", "security_mode").flatMap(SecurityMode::securityMode);
        var apiKeys = resolveApiKeys(doc, env);
        // #290: secure by default. When `security_mode` is not configured the effective mode is
        // API_KEY (was: NONE unless api-keys present), so a default-config node's management plane
        // requires authentication rather than serving the control plane wide open. The
        // cluster-wide bootstrap admin key (BootstrapAdminKeyRegistrar) supplies a credential when
        // none was provisioned. An explicit `security_mode` (including "none") always wins.
        var securityMode = explicitMode.or(SecurityMode.API_KEY);
        var jwtConfig = parseJwtConfig(doc);
        var httpProtocol = doc.getParsed("app-http", "protocol", "one of: h1, h3, both", HttpProtocol::httpProtocol)
                              .or(HttpProtocol.H1);
        // #198 §7: cluster-level API-version detection mode + header name (per-slice override is a
        // documented follow-up). Defaults keep path mode (byte-identical to pre-#198-C3b behavior).
        var apiVersioningDetection = doc.getParsed("app-http",
                                                   "api_versioning_detection",
                                                   "one of: path, header",
                                                   ApiVersioningDetection::apiVersioningDetection)
                                        .or(ApiVersioningDetection.PATH);
        var apiVersionHeaderName = doc.getString("app-http", "api_version_header")
                                      .or(AppHttpConfig.DEFAULT_API_VERSION_HEADER);
        var appHttp = AppHttpConfig.appHttpConfig(enabled,
                                                  port,
                                                  apiKeys,
                                                  maxRequestSize,
                                                  securityMode,
                                                  jwtConfig,
                                                  httpProtocol,
                                                  apiVersioningDetection,
                                                  apiVersionHeaderName).unwrap();
        var readTimeout = parseTimeSpan(doc, "app-http", "read_timeout", AppHttpConfig.DEFAULT_READ_TIMEOUT);
        var minBodyRate = parseDataSize(doc, "app-http", "min_body_rate", AppHttpConfig.DEFAULT_MIN_BODY_RATE);
        var withReadTimeout = appHttp.withReadTimeout(readTimeout)
                                     .withMinBodyRate(minBodyRate)
                                     .withUpload(parseUploadConfig(doc));

        builder.appHttp(parseAppTls(doc).map(withReadTimeout::withTls)
                                   .or(withReadTimeout)
                                   .withManagementSecurityMode(parseManagementSecurityMode(doc)));
    }

    /// `[app-http.upload]`: the node-wide bounds on routes that receive an upload body. Values are checked by
    /// [ConfigValidator]; an unknown key is refused by [#refuseUnknownUploadKeys].
    private static UploadConfig parseUploadConfig(TomlDocument doc) {
        var section = "app-http.upload";
        var defaults = UploadConfig.DEFAULT;

        return UploadConfig.uploadConfig(parseDataSize(doc, section, "max_size_ceiling", defaults.maxSizeCeiling()),
                                         parseInt(doc, section, "max_concurrent", defaults.maxConcurrent()),
                                         parseInt(doc,
                                                  section,
                                                  "max_concurrent_per_principal",
                                                  defaults.maxConcurrentPerPrincipal()),
                                         parseTimeSpan(doc, section, "idle_timeout", defaults.idleTimeout()),
                                         parseDataSize(doc, section, "min_body_rate", defaults.minBodyRate()),
                                         parseDataSize(doc, section, "max_in_flight_size", defaults.maxInFlightBytes()));
    }

    private static final Set<String> UPLOAD_KEYS = Set.of("max_size_ceiling",
                                                          "max_concurrent",
                                                          "max_concurrent_per_principal",
                                                          "idle_timeout",
                                                          "min_body_rate",
                                                          "max_in_flight_size");

    /// A mistyped `[app-http.upload]` key (`max_concurent = 8`) would leave the cap at its default with nothing to say
    /// so, so it is refused, naming the keys that exist.
    private static Result<Unit> refuseUnknownUploadKeys(TomlDocument doc) {
        var unknown = doc.keys("app-http.upload").stream().filter(key -> !UPLOAD_KEYS.contains(key)).sorted().toList();

        return unknown.isEmpty()
               ? Result.unitResult()
               : ConfigError.invalidConfig("[app-http.upload] unknown key(s) " + String.join(", ", unknown)
                                          + "; valid keys: " + String.join(", ", new java.util.TreeSet<>(UPLOAD_KEYS))).result();
    }

    /// `[management] security_mode` (#962): optional, `none` or `api-key`. Absent, the Management API inherits
    /// `[app-http] security_mode`. Any other value was already refused by [#refuseUnsupportedSecurityModes], so what
    /// reaches here is a mode the management plane has.
    private static Option<SecurityMode> parseManagementSecurityMode(TomlDocument doc) {
        return doc.getString("management", "security_mode")
                  .flatMap(SecurityMode::securityMode);
    }

    /// A `security_mode` the node cannot honour is refused, never rewritten: a value that is not recognised used to read as the
    /// default (`[app-http]`) or as `api-key` (`[management]`), so an operator who wrote `jwt` for the management plane, or
    /// mistyped `nnone`, got a different mode than the one in the file and no word of it (#1455: refuses, never drops).
    /// Shared with the bootstrap pre-flight (PF-24), so a cloud bootstrap refuses the same values, in the same words, before it
    /// provisions anything: the two can never disagree about what a node would refuse.
    public static Result<Unit> refuseUnsupportedSecurityModes(TomlDocument doc) {
        return refuseUnrecognisedAppHttpMode(doc).flatMap(_ -> refuseUnsupportedManagementMode(doc));
    }

    private static Result<Unit> refuseUnrecognisedAppHttpMode(TomlDocument doc) {
        return doc.getString("app-http", "security_mode")
                  .filter(raw -> SecurityMode.securityMode(raw).isEmpty())
                  .fold(Result::unitResult,
                        raw -> ConfigError.invalidConfig("[app-http] security_mode = \"" + raw
                                                        + "\" is not a security mode."
                                                        + " Allowed values: none, api-key, jwt.").result());
    }

    private static Result<Unit> refuseUnsupportedManagementMode(TomlDocument doc) {
        return doc.getString("management", "security_mode")
                  .fold(Result::unitResult, ConfigLoader::managementModeVerdict);
    }

    private static Result<Unit> managementModeVerdict(String raw) {
        return SecurityMode.securityMode(raw).fold(() -> unsupportedManagementMode(raw, "is not a security mode"),
                                                   mode -> managementModeSupport(raw, mode));
    }

    private static Result<Unit> managementModeSupport(String raw, SecurityMode mode) {
        return mode == SecurityMode.JWT
               ? unsupportedManagementMode(raw, "is not supported: the management plane has no JWT mode")
               : Result.unitResult();
    }

    private static Result<Unit> unsupportedManagementMode(String raw, String reason) {
        return ConfigError.invalidConfig("[management] security_mode = \"" + raw
                                        + "\" " + reason
                                        + ". Allowed values: none, api-key.").result();
    }

    /// `[app-http.tls]` — an operator-supplied certificate for the USER-facing listener.
    ///
    /// Both keys are required together: a certificate without its key, or a key without its
    /// certificate, is a configuration mistake rather than a request for a partial identity, and
    /// silently falling back to the cluster certificate would hide it. See [AppHttpConfig.AppTls]
    /// for why the cluster identity cannot serve public traffic (#967).
    private static Option<AppHttpConfig.AppTls> parseAppTls(TomlDocument doc) {
        return Option.all(doc.getString("app-http.tls", "cert_path"),
                          doc.getString("app-http.tls", "key_path"))
                     .map(AppHttpConfig.AppTls::new);
    }

    private static Option<JwtConfig> parseJwtConfig(TomlDocument doc) {
        return doc.getString("app-http", "jwks_url")
                  .map(jwksUrl -> buildJwtConfig(doc, jwksUrl));
    }

    private static JwtConfig buildJwtConfig(TomlDocument doc, String jwksUrl) {
        var issuer = doc.getString("app-http", "issuer");
        var audience = doc.getString("app-http", "audience");
        var roleClaim = doc.getString("app-http", "role_claim").or(JwtConfig.DEFAULT_ROLE_CLAIM);
        var cacheTtl = parseLong(doc, "app-http", "jwks_cache_ttl_seconds", JwtConfig.DEFAULT_CACHE_TTL_SECONDS);
        var clockSkew = parseLong(doc, "app-http", "clock_skew_seconds", JwtConfig.DEFAULT_CLOCK_SKEW_SECONDS);

        return JwtConfig.jwtConfig(jwksUrl, issuer, audience, roleClaim, cacheTtl, clockSkew).unwrap();
    }

    private static void populateBackupConfig(TomlDocument doc,
                                             AetherConfig.Builder builder,
                                             Environment environment,
                                             boolean environmentExplicit,
                                             Fn1<String, String> env) {
        effectiveBackup(doc, env, environment, environmentExplicit).onPresent(builder::backup);
    }

    /// The node's effective `[backup]` (TOML merged with the environment variables), refused when its path is unwritable for `environment`.
    static Option<BackupConfig> effectiveBackup(TomlDocument doc,
                                                org.pragmatica.lang.Functions.Fn1<String, String> env,
                                                Environment environment,
                                                boolean environmentExplicit) {
        var backup = backupConfigFrom(doc, env);

        backup.onPresent(value -> checkBackupPath(value, environment, environmentExplicit).getOrThrow(IllegalArgumentException::new,
                                                                                                      "invalid [backup]"));

        return backup;
    }

    /// #1968: the same rule the bootstrap config applies to a source's `node_config` (`BackupPathRule`), applied to the EFFECTIVE path of a
    /// node in a container environment, wherever it came from (TOML, `AETHER_BACKUP_PATH` of a compose file or of a provisioner). Docker:
    /// absolute and under `/data` (named volume); Kubernetes: absolute; LOCAL runs on the host and keeps its relative default.
    static Result<Unit> checkBackupPath(BackupConfig backup, Environment environment, boolean environmentExplicit) {
        if (backup.path().isBlank() || environment == Environment.LOCAL || !environmentExplicit) {
            return Result.unitResult();
        }

        return org.pragmatica.aether.environment.BackupPathRule.refusal(backup.path(),
                                                                        environment == Environment.DOCKER)
                                                               .fold(Result::unitResult,
                                                                     reason -> ConfigError.invalidConfig("[backup] path " + reason).result());
    }

    /// `[backup]` (#1532/#1533), each key overridable by its `AETHER_BACKUP_*` environment variable (#1968): a node minted
    /// without a TOML of its own (a Docker replacement) receives the backup configuration from its provisioner's
    /// environment, and an env-only compose cluster configures it the same way. Environment wins per key, as `AETHER_API_KEYS`
    /// does, and a blank variable counts as unset. A key the environment sets to a DIFFERENT value than the TOML is logged at
    /// startup (key and source only, never the values: a remote can carry a credential). Empty when the merged `enabled` is false.
    static Option<BackupConfig> backupConfigFrom(TomlDocument doc,
                                                 org.pragmatica.lang.Functions.Fn1<String, String> env) {
        return backupConfigFrom(doc, env, notice -> log.warn("{}", notice), summary -> log.info("{}", summary));
    }

    static Option<BackupConfig> backupConfigFrom(TomlDocument doc,
                                                 org.pragmatica.lang.Functions.Fn1<String, String> env,
                                                 java.util.function.Consumer<String> overrideNotice) {
        return backupConfigFrom(doc,
                                env,
                                overrideNotice,
                                _ -> {});
    }

    /// `sourceNotice` receives, once, which source each key came from (`environment`, `TOML` or `default`), never a value.
    static Option<BackupConfig> backupConfigFrom(TomlDocument doc,
                                                 org.pragmatica.lang.Functions.Fn1<String, String> env,
                                                 java.util.function.Consumer<String> overrideNotice,
                                                 java.util.function.Consumer<String> sourceNotice) {
        var sources = new java.util.LinkedHashMap<String, String>();
        var enabled = backupKey(doc, env, ClusterIdentityEnv.BACKUP_ENABLED, "enabled", overrideNotice, sources).map(raw -> strictBoolean(raw).getOrThrow(IllegalArgumentException::new,
                                                                                                                                                          "invalid [backup] enabled"))
                               .or(false);

        if (!enabled) {
            return Option.empty();
        }

        var path = backupKey(doc, env, ClusterIdentityEnv.BACKUP_PATH, "path", overrideNotice, sources).or("");
        var remote = backupKey(doc, env, ClusterIdentityEnv.BACKUP_REMOTE, "remote", overrideNotice, sources).or("");
        var restore = BackupConfig.RestoreMode.restoreMode(backupKey(doc,
                                                                     env,
                                                                     ClusterIdentityEnv.BACKUP_RESTORE,
                                                                     "restore",
                                                                     overrideNotice,
                                                                     sources).or("auto")).getOrThrow(IllegalArgumentException::new,
                                                                                                     "invalid [backup]");

        sourceNotice.accept("[backup] configuration sources: " + sources);

        return Option.some(BackupConfig.backupConfig(true, path, remote, restore));
    }

    /// One `[backup]` key: the environment's value when set, else the TOML's. The notice names the key and BOTH sources, not values.
    private static Option<String> backupKey(TomlDocument doc,
                                            org.pragmatica.lang.Functions.Fn1<String, String> env,
                                            String envName,
                                            String tomlKey,
                                            java.util.function.Consumer<String> overrideNotice,
                                            java.util.Map<String, String> sources) {
        var fromEnv = envValue(env, envName);
        var fromToml = doc.getString("backup", tomlKey);

        fromEnv.flatMap(value -> fromToml.filter(toml -> !toml.strip()
                                                              .equals(value.strip())))
               .onPresent(_ -> overrideNotice.accept("[backup] " + tomlKey
                                                    + ": the environment variable " + envName
                                                    + " overrides the value in the node TOML; remove one of them to end the ambiguity"));
        sources.put(tomlKey,
                    fromEnv.isPresent()
                    ? "environment"
                    : fromToml.isPresent()
                      ? "TOML"
                      : "default");

        return fromEnv.orElse(fromToml);
    }

    private static Option<String> envValue(org.pragmatica.lang.Functions.Fn1<String, String> env, String name) {
        return Option.option(env.apply(name)).filter(value -> !value.isBlank());
    }

    private static void populateDhtReplicationConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var hasDelay = doc.getString("dht.replication", "cooldown_delay").isPresent() || doc.getLong("dht.replication",
                                                                                                     "cooldown_delay_ms")
                                                                                            .isPresent();
        var hasRate = doc.getInt("dht.replication", "cooldown_rate").isPresent();

        if (hasDelay || hasRate) {
            var delay = parseTimeSpanOrMs(doc,
                                          "dht.replication",
                                          "cooldown_delay",
                                          "cooldown_delay_ms",
                                          DhtReplicationConfig.DEFAULT_COOLDOWN_DELAY);
            var rate = doc.getInt("dht.replication", "cooldown_rate").or(DhtReplicationConfig.DEFAULT_COOLDOWN_RATE);

            builder.dhtReplication(DhtReplicationConfig.dhtReplicationConfig(delay, rate));
        }
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static void populateTimeoutsConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var hasTimeoutsSection = doc.sectionNames().stream().anyMatch(s -> s.startsWith("timeouts"));

        if (!hasTimeoutsSection) {
            return;
        }

        builder.timeouts(timeoutsFromDocument(doc));
    }

    private static void populateCloudConfig(TomlDocument doc, AetherConfig.Builder builder, Fn1<String, String> env) {
        doc.getString("cloud", "provider").onPresent(provider -> applyCloudConfig(doc, builder, provider, env));
    }

    private static void applyCloudConfig(TomlDocument doc,
                                         AetherConfig.Builder builder,
                                         String provider,
                                         Fn1<String, String> env) {
        var credentials = doc.getSection("cloud.credentials");
        var compute = doc.getSection("cloud.compute");
        var cc = CloudConfig.cloudConfig(provider, resolveEnvVars(credentials, env), resolveEnvVars(compute, env)).unwrap();
        var lb = doc.getSection("cloud.load_balancer");
        var discovery = doc.getSection("cloud.discovery");
        var secrets = doc.getSection("cloud.secrets");
        var withLb = lb.isEmpty()
                     ? cc
                     : cc.withLoadBalancer(lb);
        var withDiscovery = discovery.isEmpty()
                            ? withLb
                            : withLb.withDiscovery(discovery);
        var withSecrets = secrets.isEmpty()
                          ? withDiscovery
                          : withDiscovery.withSecrets(secrets);

        builder.cloud(withSecrets);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static void populateStorageConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var instances = new HashMap<String, StorageConfig>();

        for (var sectionName : doc.sectionNames()) {
            if (sectionName.startsWith("storage.")) {
                var instanceName = sectionName.substring("storage.".length());
                // #253: `[storage.encryption]` (and its `[storage.encryption.keys]` sub-table) is the
                // global keyring section, not a per-instance `[storage.<name>]` one -- excluded here so
                // it is never misread as a storage instance literally named "encryption". Parsed
                // separately by `populateStorageEncryptionConfig`.
                // #249: a dotted name is a sub-section of an instance (`[storage.<name>.shared]`), not an instance.
                if (!instanceName.isEmpty() && !instanceName.equals("encryption") && !instanceName.contains(".")) {
                    warnIgnoredStreamsKeys(doc, instanceName, sectionName);
                    instances.put(instanceName,
                                  markedIfPathless(doc, instanceName, sectionName, storageFromSection(doc, sectionName)));
                    if (doc.hasKey(sectionName, "disk_path")) {
                        instances.computeIfPresent(instanceName, (_, written) -> written.withDiskPathExplicit());
                    }
                }
            }
        }

        if (!instances.isEmpty()) {
            builder.storage(Map.copyOf(instances));
        }
    }

    /// #249: every `[storage.<name>.shared]` section, parsed into its instance's [StorageConfig#shared]. An instance
    /// that has only the sub-section gets [StorageConfig#sharedOnly], so the node places it as if `[storage.<name>]`
    /// were absent. A malformed section fails the load with a typed [SharedTierConfig.SharedTierConfigError].
    /// `streams` keeps the DHT (anything else is refused) and `encryption` is reserved: both in [SharedTierConfig].
    private static Result<AetherConfig> attachSharedTiers(TomlDocument doc, AetherConfig config) {
        return Result.allOf(sharedSectionInstances(doc).stream().map(name -> sharedTierEntry(doc, name)).toList()).map(entries -> config.withStorage(withSharedTiers(config.storage(),
                                                                                                                                                                     entries)));
    }

    private static Result<Map.Entry<String, SharedTierConfig>> sharedTierEntry(TomlDocument doc, String name) {
        return SharedTierConfig.sharedTierConfig(doc, name).map(shared -> Map.entry(name, shared));
    }

    /// Instances with a `[storage.<name>.shared]` section or anything nested under it (a sub-table, or a dotted key,
    /// which the TOML parser stores as one), so a sub-table alone still reaches the shared-tier refusals.
    private static List<String> sharedSectionInstances(TomlDocument doc) {
        return doc.sectionNames()
                  .stream()
                  .map(section -> section.split("\\."))
                  .filter(ConfigLoader::isUnderSharedSection)
                  .map(parts -> parts[1])
                  .distinct()
                  .sorted()
                  .toList();
    }

    private static boolean isUnderSharedSection(String[] parts) {
        return parts.length >= 3
               && "storage".equals(parts[0])
               && "shared".equals(parts[2]);
    }

    /// #249: a `[storage.<name>.<sub>]` section that is neither the keyring's key table (`[storage.encryption.keys]`)
    /// nor a shared tier (`[storage.<name>.shared...]`) is refused, naming every such section. Before #249 it was read
    /// as an instance literally named `<name>.<sub>`; ignoring it instead would let a typo such as
    /// `[storage.content.shard]` boot without the tier the operator meant to configure, or `[storage.encryption.kyes]`
    /// boot with no keys. A section nested under `.shared` is left to the shared tier's own refusals
    /// ([SharedTierConfig]), which name the offending key.
    private static Result<Unit> refuseUnknownStorageSubSections(TomlDocument doc) {
        var unknown = doc.sectionNames()
                         .stream()
                         .filter(section -> section.startsWith("storage."))
                         .map(section -> section.split("\\."))
                         .filter(ConfigLoader::isUnknownSubSection)
                         .map(parts -> "[" + String.join(".", parts) + "]")
                         .sorted()
                         .toList();

        return unknown.isEmpty()
               ? Result.unitResult()
               : ConfigError.invalidConfig("Unknown storage sub-sections " + String.join(", ", unknown)
                                          + ": only [storage.<name>.shared] and [storage.encryption.keys] sub-sections are accepted").result();
    }

    private static boolean isUnknownSubSection(String[] parts) {
        return parts.length >= 3
               && !"shared".equals(parts[2])
               && !isKeyringKeys(parts);
    }

    /// `[storage.encryption.keys]`, the one keyring sub-section [#populateStorageEncryptionConfig] reads.
    private static boolean isKeyringKeys(String[] parts) {
        return parts.length == 3
               && "encryption".equals(parts[1])
               && "keys".equals(parts[2]);
    }

    /// #249: an explicit blank `disk_path` or `snapshot_path` is refused. It names no directory, and the node places a
    /// written section exactly as written ([StorageConfig#derivesPaths] is set only for a shared-only instance), so a
    /// blank path would resolve against the node's working directory instead of the default the operator most
    /// likely meant by it. Omitting the key is how the default is selected.
    private static Result<Unit> refuseBlankStoragePaths(TomlDocument doc) {
        return Option.from(doc.sectionNames()
                              .stream()
                              .filter(ConfigLoader::isStorageInstanceSection)
                              .sorted()
                              .flatMap(section -> blankPathKeys(doc, section))
                              .findFirst()).fold(Result::unitResult,
                                                 blank -> ConfigError.invalidConfig("[" + blank
                                                                                   + " must not be blank: omit the key to use the default").result());
    }

    private static boolean isStorageInstanceSection(String section) {
        var name = section.startsWith("storage.")
                   ? section.substring("storage.".length())
                   : "";

        return ! name.isEmpty()
               && !name.contains(".")
               && !"encryption".equals(name);
    }

    private static Stream<String> blankPathKeys(TomlDocument doc, String section) {
        return Stream.of("disk_path", "snapshot_path")
                     .filter(key -> doc.getString(section, key)
                                       .filter(String::isBlank)
                                       .isPresent())
                     .map(key -> section + "] " + key);
    }

    private static Map<String, StorageConfig> withSharedTiers(Map<String, StorageConfig> storage,
                                                              List<Map.Entry<String, SharedTierConfig>> shared) {
        var merged = new HashMap<>(storage);

        shared.forEach(entry -> merged.put(entry.getKey(), withShared(storage, entry)));

        return Map.copyOf(merged);
    }

    private static StorageConfig withShared(Map<String, StorageConfig> storage,
                                            Map.Entry<String, SharedTierConfig> entry) {
        return Option.option(storage.get(entry.getKey()))
                     .map(existing -> existing.withShared(entry.getValue()))
                     .or(() -> StorageConfig.sharedOnly(entry.getValue()));
    }

    /// #253: the global `[storage.encryption]` keyring -- `active_key_id` + `streams_encrypted`, plus
    /// the `[storage.encryption.keys]` sub-table (key id -> `${secrets:<path>}` reference, read via
    /// `getSection` the same way `[cloud.credentials]` etc. are, but WITHOUT `resolveEnvVars`: these
    /// values are secret REFERENCES resolved later, at boot, through `SecretsProvider` -- not `${env:...}`
    /// substitutions resolved here). Absent section -> builder is left untouched (`Option.none()` default).
    private static void populateStorageEncryptionConfig(TomlDocument doc, AetherConfig.Builder builder) {
        if (!doc.hasSection("storage.encryption")) {
            return;
        }

        var activeKeyId = doc.getString("storage.encryption", "active_key_id").or("");
        var streamsEncrypted = doc.getBoolean("storage.encryption", "streams_encrypted").or(false);
        var keys = doc.getSection("storage.encryption.keys");

        builder.storageEncryption(StorageEncryptionConfig.storageEncryptionConfig(keys, activeKeyId, streamsEncrypted));
    }

    /// #957: `[alerts]` -- the section that did not exist. Before this, `AlertConfig` was referenced in
    /// `src/main` by `AlertForwarder` alone, was not a component of [AetherConfig], and no `.toml` in the
    /// repo carried an `[alert...]` section, so webhook delivery was unreachable by any configuration and
    /// the hysteresis margin (#969) would have had nowhere to live.
    ///
    /// Absent section -> builder untouched (`Option.none()` default), so a node with no `[alerts]` keeps
    /// the shipped defaults: damping ON at [AlertConfig#DEFAULT_HYSTERESIS_MARGIN], webhooks DISABLED.
    /// **Plumbing this does not enable webhook delivery** -- it makes it opt-in reachable; the default
    /// `WebhookConfig` still carries no URLs and `AlertForwarder.forward` still early-returns.
    ///
    /// Validation is deliberately NOT done here. It runs at node boot via [AlertConfig#check], which is
    /// what makes a misconfigured webhook refuse at boot instead of accepting and dropping (#957's
    /// fail-closed clause). Building here and validating there keeps the loader total.
    private static void populateAlertConfig(TomlDocument doc, AetherConfig.Builder builder) {
        if (!hasAnyAlertSection(doc)) {
            return;
        }

        var enabled = doc.getBoolean("alerts", "enabled").or(true);
        var margin = doc.getDouble("alerts", "hysteresis_margin").or(AlertConfig.DEFAULT_HYSTERESIS_MARGIN);

        builder.alerts(AlertConfig.alertConfig(enabled,
                                               webhookFromToml(doc),
                                               eventsFromToml(doc),
                                               margin,
                                               entityCheckpointLagFromToml(doc)).unwrap());
    }

    /// `alerts.entity_checkpoint_lag_warning` / `_critical` (#1302); each defaults independently, and
    /// the pair is validated at boot by [AlertConfig#check].
    private static AlertConfig.EntityCheckpointLag entityCheckpointLagFromToml(TomlDocument doc) {
        return AlertConfig.EntityCheckpointLag.entityCheckpointLag(doc.getDouble("alerts",
                                                                                 "entity_checkpoint_lag_warning")
                                                                      .or(AlertConfig.EntityCheckpointLag.DEFAULT_WARNING),
                                                                   doc.getDouble("alerts",
                                                                                 "entity_checkpoint_lag_critical")
                                                                      .or(AlertConfig.EntityCheckpointLag.DEFAULT_CRITICAL));
    }

    /// Any alert section at all, INCLUDING a sub-section written without its parent header.
    ///
    /// `TomlDocument.hasSection` is an exact key match, so `[alerts.webhook]` alone does NOT make
    /// `hasSection("alerts")` true. Keying the whole populate on the bare `[alerts]` header therefore
    /// discarded a config file whose only alert content was `[alerts.webhook]` — **silently, and with
    /// the boot gate never running**, so an operator who wrote the natural thing got no webhook
    /// delivery and no error. That is the failure mode this plumbing exists to remove, reintroduced one
    /// level up.
    ///
    /// Accepting the sub-header is strictly more permissive than refusing it: nothing that parsed
    /// before stops parsing, and `AlertConfig.check()` still runs at boot on whatever is built, so a
    /// webhook enabled with no URLs is refused rather than accepted-and-dropped.
    private static boolean hasAnyAlertSection(TomlDocument doc) {
        return doc.hasSection("alerts") || doc.hasSection("alerts.webhook") || doc.hasSection("alerts.events");
    }

    /// `[alerts.webhook]`. Absent sub-section -> the DISABLED default, which is what keeps delivery
    /// opt-in for an operator who writes `[alerts]` only to set a hysteresis margin.
    private static AlertConfig.WebhookConfig webhookFromToml(TomlDocument doc) {
        if (!doc.hasSection("alerts.webhook")) {
            return AlertConfig.WebhookConfig.webhookConfig();
        }

        var enabled = doc.getBoolean("alerts.webhook", "enabled").or(false);
        var urls = doc.getStringList("alerts.webhook", "urls").or(List.of());
        var retryCount = parseInt(doc, "alerts.webhook", "retry_count", 3);
        var timeout = parseTimeSpan(doc, "alerts.webhook", "timeout", timeSpan(5).seconds());

        return AlertConfig.WebhookConfig.webhookConfig(enabled, urls, retryCount, timeout).unwrap();
    }

    /// `[alerts.events]`. Absent sub-section -> the disabled default, matching [AlertConfig].
    private static AlertConfig.EventConfig eventsFromToml(TomlDocument doc) {
        return doc.hasSection("alerts.events")
               ? AlertConfig.EventConfig.eventConfig(doc.getBoolean("alerts.events", "enabled").or(false)).unwrap()
               : AlertConfig.EventConfig.eventConfig();
    }

    /// v1617 (#1725): `[storage.streams]` only carries `wal_path`; any other key there (`disk_path`, `snapshot_path`,
    /// `encrypted`, ...) has no effect. It used to be dropped silently, so an operator setting one believed it applied.
    private static void warnIgnoredStreamsKeys(TomlDocument doc, String instanceName, String sectionName) {
        if (!"streams".equals(instanceName)) {
            return;
        }

        var ignored = doc.keys(sectionName)
                         .stream()
                         .filter(key -> !STREAMS_SECTION_KEYS.contains(key))
                         .sorted()
                         .toList();

        if (!ignored.isEmpty()) {
            log.warn("[storage.streams] carries only wal_path; these keys have no effect and are ignored: {}", ignored);
        }
    }

    /// #249 N3: a section of a name other than `artifacts`/`content` that sets neither `disk_path` nor `snapshot_path`
    /// gives no paths of its own, so the node derives the instance's own directories (`StorageFactory.derivedStorage`).
    /// `artifacts` and `content` keep the default paths their section resolves to, as before.
    private static StorageConfig markedIfPathless(TomlDocument doc,
                                                  String instanceName,
                                                  String sectionName,
                                                  StorageConfig config) {
        return placesItself(doc, instanceName, sectionName)
               ? config
               : config.derivingPaths();
    }

    private static boolean placesItself(TomlDocument doc, String instanceName, String sectionName) {
        return SYNTHESIZED_INSTANCES.contains(instanceName) || doc.hasKey(sectionName, "disk_path") || doc.hasKey(sectionName,
                                                                                                                  "snapshot_path");
    }

    private static StorageConfig storageFromSection(TomlDocument doc, String sectionName) {
        var memoryMaxBytes = parseLong(doc, sectionName, "memory_max_bytes", 256 * 1024 * 1024);
        var diskMaxBytes = parseLong(doc, sectionName, "disk_max_bytes", 10L * 1024 * 1024 * 1024);
        // #912: one source for the default paths (and the root they honour), not a second copy of the literals.
        var defaults = StorageConfig.storageConfig();
        var diskPath = doc.getString(sectionName, "disk_path").or(defaults.diskPath());
        var snapshotPath = doc.getString(sectionName, "snapshot_path").or(defaults.snapshotPath());
        var mutationThreshold = parseInt(doc, sectionName, "snapshot_mutation_threshold", 1000);
        var snapshotInterval = doc.getString(sectionName, "snapshot_max_interval").or("60s");
        var retentionCount = parseInt(doc, sectionName, "snapshot_retention_count", 5);
        // #634-3: the stream WAL's base dir as a first-class storage key (read from the `streams`
        // instance; empty = derive the pre-#634-3 sibling path, so absent keys change nothing).
        var walPath = doc.getString(sectionName, "wal_path").or("");
        // #253: per-instance opt-in into encryption; requires [storage.encryption] to carry a keyring
        // (enforced by StorageEncryptionConfigValidator, not here).
        var encrypted = doc.getBoolean(sectionName, "encrypted").or(false);
        // Kill switch for background tier promotion on this instance (default on). Read into StorageConfig.promotion,
        // which StorageFactory.assembleSetup consults to build the real PromotionManager or the inert one.
        var promotion = doc.getBoolean(sectionName, "promotion").or(true);
        // Cross-node block prefetching: on unless an operator switches it off for the instance.
        var prefetch = doc.getBoolean(sectionName, "prefetch").or(true);
        var prefetchMaxInFlight = prefetchMaxInFlight(doc, sectionName);
        var maxBlockBytes = maxBlockBytes(doc, sectionName);
        var anchorRetention = anchorRetention(doc, sectionName);

        return StorageConfig.storageConfig(memoryMaxBytes,
                                           diskMaxBytes,
                                           diskPath,
                                           snapshotPath,
                                           mutationThreshold,
                                           snapshotInterval,
                                           retentionCount,
                                           walPath,
                                           encrypted,
                                           promotion,
                                           prefetch,
                                           prefetchMaxInFlight,
                                           maxBlockBytes).withAnchorRetention(anchorRetention);
    }

    /// How many versions of each durable anchor the instance keeps below the newest. A value that is not an integer of at
    /// least 2 is refused like any wrong-typed value (#1455), never defaulted: with fewer, a cold boot that finds the
    /// newest version corrupt has no older one for an operator to accept.
    private static int anchorRetention(TomlDocument doc, String sectionName) {
        return doc.getParsed(sectionName,
                             "anchor_retention",
                             "an integer of at least 2",
                             ConfigLoader::anchorRetentionOf)
                  .or(StorageConfig.DEFAULT_ANCHOR_RETENTION);
    }

    private static Option<Integer> anchorRetentionOf(String text) {
        return Number.parseInt(text)
                     .option()
                     .filter(value -> value >= StorageConfig.MIN_ANCHOR_RETENTION);
    }

    /// The largest block the instance accepts. A value that is not a positive integer is refused like any wrong-typed
    /// value (#1455), never defaulted: a 0 or negative limit would refuse every write.
    private static int maxBlockBytes(TomlDocument doc, String sectionName) {
        return doc.getParsed(sectionName, "max_block_bytes", "a positive integer", ConfigLoader::positiveInt)
                  .or(StorageConfig.DEFAULT_MAX_BLOCK_BYTES);
    }

    /// The most prefetches running at once. A value that is not a positive integer is refused like any wrong-typed
    /// value (#1455), never defaulted: 0 would starve prefetching without switching it off, and `prefetch = false`
    /// is the off switch.
    private static int prefetchMaxInFlight(TomlDocument doc, String sectionName) {
        return doc.getParsed(sectionName, "prefetch_max_in_flight", "a positive integer", ConfigLoader::positiveInt)
                  .or(StorageConfig.DEFAULT_PREFETCH_MAX_IN_FLIGHT);
    }

    private static Option<Integer> positiveInt(String text) {
        return Number.parseInt(text)
                     .option()
                     .filter(Verify.Is::positive);
    }

    private static void populateEndpointsConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var endpoints = new HashMap<String, EndpointConfig>();

        for (var sectionName : doc.sectionNames()) {
            if (sectionName.startsWith("endpoints.")) {
                var endpointName = sectionName.substring("endpoints.".length());

                if (!endpointName.isEmpty()) {
                    endpoints.put(endpointName, endpointFromSection(doc, sectionName));
                }
            }
        }

        if (!endpoints.isEmpty()) {
            builder.endpoints(Map.copyOf(endpoints));
        }
    }

    private static EndpointConfig endpointFromSection(TomlDocument doc, String sectionName) {
        var host = doc.getString(sectionName, "host").or("localhost");
        var port = doc.getInt(sectionName, "port").or(5432);
        var username = doc.getString(sectionName, "username").or("");
        var password = doc.getString(sectionName, "password").map(ConfigLoader::resolveEnvVar).or("");

        return EndpointConfig.endpointConfig(host, port, username, password);
    }

    private static Map<String, String> resolveEnvVars(Map<String, String> map, Fn1<String, String> env) {
        var resolved = new HashMap<String, String>();

        map.forEach((k, v) -> resolved.put(k, resolveEnvVar(v, env)));

        return resolved;
    }

    private static String resolveEnvVar(String value) {
        return resolveEnvVar(value, System::getenv);
    }

    /// `${env:NAME}` with the environment supplied; `NAME_FILE` is the file form of the same reference (#828).
    static String resolveEnvVar(String value, Fn1<String, String> env) {
        if (value.startsWith("${env:") && value.endsWith("}")) {
            var envName = value.substring(6, value.length() - 1);
            // #828: `NAME_FILE` is the file form of the same reference, so a credential never has to sit in the environment.
            return ClusterSecretSource.resolve(env, envName)
                                      .fold(_ -> Option.<String> none(),
                                            found -> found)
                                      .or(value);
        }

        return value;
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static TimeoutsConfig timeoutsFromDocument(TomlDocument doc) {
        var defaults = TimeoutsConfig.timeoutsConfig();

        return new TimeoutsConfig(parseInvocationTimeouts(doc, defaults.invocation()),
                                  parseForwardingTimeouts(doc, defaults.forwarding()),
                                  parseDeploymentTimeouts(doc, defaults.deployment()),
                                  parseRollingUpdateTimeouts(doc, defaults.rollingUpdate()),
                                  parseClusterTimeouts(doc, defaults.cluster()),
                                  parseConsensusTimeouts(doc, defaults.consensus()),
                                  parseElectionTimeouts(doc, defaults.election()),
                                  parseSwimTimeouts(doc, defaults.swim()),
                                  parseObservabilityTimeouts(doc, defaults.observability()),
                                  parseDhtTimeouts(doc, defaults.dht()),
                                  parseWorkerTimeouts(doc, defaults.worker()),
                                  parseSecurityTimeouts(doc, defaults.security()),
                                  parseRepositoryTimeouts(doc, defaults.repository()),
                                  parseScalingTimeouts(doc, defaults.scaling()),
                                  parseStorageMaintenanceTimeouts(doc, defaults.storageMaintenance()));
    }

    private static TimeoutsConfig.InvocationTimeouts parseInvocationTimeouts(TomlDocument doc,
                                                                             TimeoutsConfig.InvocationTimeouts d) {
        return new TimeoutsConfig.InvocationTimeouts(parseTimeSpan(doc, "timeouts.invocation", "timeout", d.timeout()),
                                                     parseTimeSpan(doc,
                                                                   "timeouts.invocation",
                                                                   "invoker_timeout",
                                                                   d.invokerTimeout()),
                                                     parseTimeSpan(doc,
                                                                   "timeouts.invocation",
                                                                   "retry_base_delay",
                                                                   d.retryBaseDelay()),
                                                     parseInt(doc, "timeouts.invocation", "max_retries", d.maxRetries()));
    }

    private static TimeoutsConfig.ForwardingTimeouts parseForwardingTimeouts(TomlDocument doc,
                                                                             TimeoutsConfig.ForwardingTimeouts d) {
        return new TimeoutsConfig.ForwardingTimeouts(parseTimeSpan(doc,
                                                                   "timeouts.forwarding",
                                                                   "retry_delay",
                                                                   d.retryDelay()),
                                                     parseInt(doc, "timeouts.forwarding", "max_retries", d.maxRetries()),
                                                     parseTimeSpan(doc,
                                                                   "timeouts.forwarding",
                                                                   "app_timeout",
                                                                   d.appTimeout()),
                                                     parseTimeSpan(doc,
                                                                   "timeouts.forwarding",
                                                                   "management_timeout",
                                                                   d.managementTimeout()),
                                                     parseTimeSpan(doc,
                                                                   "timeouts.forwarding",
                                                                   "request_budget",
                                                                   d.requestBudget()),
                                                     parseTimeSpan(doc,
                                                                   "timeouts.forwarding",
                                                                   "management_request_budget",
                                                                   d.managementRequestBudget()));
    }

    private static TimeoutsConfig.DeploymentTimeouts parseDeploymentTimeouts(TomlDocument doc,
                                                                             TimeoutsConfig.DeploymentTimeouts d) {
        return new TimeoutsConfig.DeploymentTimeouts(parseTimeSpan(doc, "timeouts.deployment", "loading", d.loading()),
                                                     parseTimeSpan(doc,
                                                                   "timeouts.deployment",
                                                                   "activating",
                                                                   d.activating()),
                                                     parseTimeSpan(doc,
                                                                   "timeouts.deployment",
                                                                   "deactivating",
                                                                   d.deactivating()),
                                                     parseTimeSpan(doc,
                                                                   "timeouts.deployment",
                                                                   "unloading",
                                                                   d.unloading()),
                                                     parseTimeSpan(doc,
                                                                   "timeouts.deployment",
                                                                   "activation_chain",
                                                                   d.activationChain()),
                                                     parseTimeSpan(doc,
                                                                   "timeouts.deployment",
                                                                   "transition_retry_delay",
                                                                   d.transitionRetryDelay()),
                                                     parseTimeSpan(doc,
                                                                   "timeouts.deployment",
                                                                   "reconciliation_interval",
                                                                   d.reconciliationInterval()),
                                                     parseInt(doc,
                                                              "timeouts.deployment",
                                                              "max_lifecycle_retries",
                                                              d.maxLifecycleRetries()));
    }

    private static TimeoutsConfig.RollingUpdateTimeouts parseRollingUpdateTimeouts(TomlDocument doc,
                                                                                   TimeoutsConfig.RollingUpdateTimeouts d) {
        return new TimeoutsConfig.RollingUpdateTimeouts(parseTimeSpan(doc,
                                                                      "timeouts.rolling_update",
                                                                      "kv_operation",
                                                                      d.kvOperation()),
                                                        parseTimeSpan(doc,
                                                                      "timeouts.rolling_update",
                                                                      "terminal_retention",
                                                                      d.terminalRetention()),
                                                        parseTimeSpan(doc,
                                                                      "timeouts.rolling_update",
                                                                      "cleanup_grace_period",
                                                                      d.cleanupGracePeriod()));
    }

    private static TimeoutsConfig.ClusterTimeouts parseClusterTimeouts(TomlDocument doc,
                                                                       TimeoutsConfig.ClusterTimeouts d) {
        // Ordering of core_absence vs community_absence (#590) is checked by ConfigValidator, so an
        // inverted pair is reported alongside every other config error instead of aborting the parse.
        return new TimeoutsConfig.ClusterTimeouts(parseTimeSpan(doc, "timeouts.cluster", "hello", d.hello()),
                                                  parseTimeSpan(doc,
                                                                "timeouts.cluster",
                                                                "reconciliation_interval",
                                                                d.reconciliationInterval()),
                                                  parseTimeSpan(doc,
                                                                "timeouts.cluster",
                                                                "ping_interval",
                                                                d.pingInterval()),
                                                  parseTimeSpan(doc,
                                                                "timeouts.cluster",
                                                                "channel_protection",
                                                                d.channelProtection()),
                                                  parseTimeSpan(doc, "timeouts.cluster", "core_absence", d.coreAbsence()),
                                                  parseTimeSpan(doc,
                                                                "timeouts.cluster",
                                                                "community_absence",
                                                                d.communityAbsence()),
                                                  parseTimeSpan(doc,
                                                                "timeouts.cluster",
                                                                "offline_buffer_cap",
                                                                d.offlineBufferCap()));
    }

    private static TimeoutsConfig.ConsensusTimeouts parseConsensusTimeouts(TomlDocument doc,
                                                                           TimeoutsConfig.ConsensusTimeouts d) {
        return new TimeoutsConfig.ConsensusTimeouts(parseTimeSpan(doc,
                                                                  "timeouts.consensus",
                                                                  "sync_retry_interval",
                                                                  d.syncRetryInterval()),
                                                    parseTimeSpan(doc,
                                                                  "timeouts.consensus",
                                                                  "cleanup_interval",
                                                                  d.cleanupInterval()),
                                                    parseTimeSpan(doc,
                                                                  "timeouts.consensus",
                                                                  "proposal_timeout",
                                                                  d.proposalTimeout()),
                                                    parseTimeSpan(doc,
                                                                  "timeouts.consensus",
                                                                  "phase_stall_check",
                                                                  d.phaseStallCheck()),
                                                    parseTimeSpan(doc,
                                                                  "timeouts.consensus",
                                                                  "git_persistence",
                                                                  d.gitPersistence()));
    }

    private static TimeoutsConfig.ElectionTimeouts parseElectionTimeouts(TomlDocument doc,
                                                                         TimeoutsConfig.ElectionTimeouts d) {
        return new TimeoutsConfig.ElectionTimeouts(parseTimeSpan(doc, "timeouts.election", "base_delay", d.baseDelay()),
                                                   parseTimeSpan(doc,
                                                                 "timeouts.election",
                                                                 "per_rank_delay",
                                                                 d.perRankDelay()),
                                                   parseTimeSpan(doc, "timeouts.election", "retry_delay", d.retryDelay()));
    }

    private static TimeoutsConfig.SwimTimeouts parseSwimTimeouts(TomlDocument doc, TimeoutsConfig.SwimTimeouts d) {
        return new TimeoutsConfig.SwimTimeouts(parseTimeSpan(doc, "timeouts.swim", "period", d.period()),
                                               parseTimeSpan(doc, "timeouts.swim", "probe_timeout", d.probeTimeout()),
                                               parseTimeSpan(doc, "timeouts.swim", "suspect_timeout", d.suspectTimeout()));
    }

    private static TimeoutsConfig.ObservabilityTimeouts parseObservabilityTimeouts(TomlDocument doc,
                                                                                   TimeoutsConfig.ObservabilityTimeouts d) {
        return new TimeoutsConfig.ObservabilityTimeouts(parseTimeSpan(doc,
                                                                      "timeouts.observability",
                                                                      "metrics_sliding_window",
                                                                      d.metricsSlidingWindow()),
                                                        parseTimeSpan(doc,
                                                                      "timeouts.observability",
                                                                      "event_loop_probe",
                                                                      d.eventLoopProbe()),
                                                        parseTimeSpan(doc,
                                                                      "timeouts.observability",
                                                                      "sampler_recalculation",
                                                                      d.samplerRecalculation()),
                                                        parseTimeSpan(doc,
                                                                      "timeouts.observability",
                                                                      "invocation_cleanup",
                                                                      d.invocationCleanup()),
                                                        parseInt(doc,
                                                                 "timeouts.observability",
                                                                 "trace_store_capacity",
                                                                 d.traceStoreCapacity()),
                                                        parseInt(doc,
                                                                 "timeouts.observability",
                                                                 "alert_history_size",
                                                                 d.alertHistorySize()));
    }

    private static TimeoutsConfig.DhtTimeouts parseDhtTimeouts(TomlDocument doc, TimeoutsConfig.DhtTimeouts d) {
        return new TimeoutsConfig.DhtTimeouts(parseTimeSpan(doc, "timeouts.dht", "operation", d.operation()),
                                              parseTimeSpan(doc,
                                                            "timeouts.dht",
                                                            "anti_entropy_interval",
                                                            d.antiEntropyInterval()));
    }

    private static TimeoutsConfig.WorkerTimeouts parseWorkerTimeouts(TomlDocument doc,
                                                                     TimeoutsConfig.WorkerTimeouts d) {
        return new TimeoutsConfig.WorkerTimeouts(parseTimeSpan(doc,
                                                               "timeouts.worker",
                                                               "heartbeat_interval",
                                                               d.heartbeatInterval()),
                                                 parseTimeSpan(doc,
                                                               "timeouts.worker",
                                                               "heartbeat_timeout",
                                                               d.heartbeatTimeout()),
                                                 parseTimeSpan(doc,
                                                               "timeouts.worker",
                                                               "metrics_aggregation",
                                                               d.metricsAggregation()));
    }

    private static TimeoutsConfig.SecurityTimeouts parseSecurityTimeouts(TomlDocument doc,
                                                                         TimeoutsConfig.SecurityTimeouts d) {
        return new TimeoutsConfig.SecurityTimeouts(parseTimeSpan(doc,
                                                                 "timeouts.security",
                                                                 "websocket_auth",
                                                                 d.websocketAuth()),
                                                   parseTimeSpan(doc, "timeouts.security", "dns_query", d.dnsQuery()),
                                                   parseTimeSpan(doc,
                                                                 "timeouts.security",
                                                                 "cert_renewal_retry",
                                                                 d.certRenewalRetry()));
    }

    private static TimeoutsConfig.RepositoryTimeouts parseRepositoryTimeouts(TomlDocument doc,
                                                                             TimeoutsConfig.RepositoryTimeouts d) {
        return new TimeoutsConfig.RepositoryTimeouts(parseTimeSpan(doc,
                                                                   "timeouts.repository",
                                                                   "http_timeout",
                                                                   d.httpTimeout()),
                                                     parseTimeSpan(doc,
                                                                   "timeouts.repository",
                                                                   "locate_timeout",
                                                                   d.locateTimeout()));
    }

    private static TimeoutsConfig.ScalingTimeouts parseScalingTimeouts(TomlDocument doc,
                                                                       TimeoutsConfig.ScalingTimeouts d) {
        return new TimeoutsConfig.ScalingTimeouts(parseTimeSpan(doc,
                                                                "timeouts.scaling",
                                                                "evaluation_interval",
                                                                d.evaluationInterval()),
                                                  parseTimeSpan(doc,
                                                                "timeouts.scaling",
                                                                "warmup_period",
                                                                d.warmupPeriod()),
                                                  parseTimeSpan(doc,
                                                                "timeouts.scaling",
                                                                "slice_cooldown",
                                                                d.sliceCooldown()),
                                                  parseTimeSpan(doc,
                                                                "timeouts.scaling",
                                                                "community_cooldown",
                                                                d.communityCooldown()),
                                                  parseTimeSpan(doc,
                                                                "timeouts.scaling",
                                                                "auto_heal_startup_cooldown",
                                                                d.autoHealStartupCooldown()),
                                                  parseTimeSpan(doc,
                                                                "timeouts.scaling",
                                                                "auto_heal_provisioning_timeout",
                                                                d.autoHealProvisioningTimeout()),
                                                  parseTimeSpan(doc,
                                                                "timeouts.scaling",
                                                                "auto_heal_swim_hints_ttl",
                                                                d.autoHealSwimHintsTtl()));
    }

    /// #250: `[timeouts.storage_maintenance] interval` -- the tick driving both `DemotionManager.demote()`
    /// and `StorageGarbageCollector.collectGarbage()` across every storage setup. See
    /// [TimeoutsConfig.StorageMaintenanceTimeouts] for why one shared interval is sufficient.
    private static TimeoutsConfig.StorageMaintenanceTimeouts parseStorageMaintenanceTimeouts(TomlDocument doc,
                                                                                             TimeoutsConfig.StorageMaintenanceTimeouts d) {
        return new TimeoutsConfig.StorageMaintenanceTimeouts(parseTimeSpan(doc,
                                                                           "timeouts.storage_maintenance",
                                                                           "interval",
                                                                           d.interval()));
    }

    private static TimeSpan parseTimeSpanOrMs(TomlDocument doc,
                                              String section,
                                              String stringKey,
                                              String msKey,
                                              TimeSpan defaultValue) {
        var fromString = doc.getParsed(section,
                                       stringKey,
                                       "a duration such as 30s",
                                       v -> org.pragmatica.lang.parse.TimeSpan.timeSpan(v)
                                                                              .option())
                            .map(ts -> TimeSpan.fromDuration(ts.duration()));

        if (fromString.isPresent()) {
            return fromString.unwrap();
        }

        return doc.getLong(section, msKey)
                  .map(ms -> timeSpan(ms).millis())
                  .or(defaultValue);
    }

    private static Map<String, ApiKeyEntry> resolveApiKeys(TomlDocument doc) {
        return resolveApiKeys(doc, System::getenv);
    }

    /// The accepted keys with the host environment supplied: `AETHER_API_KEYS` or the file `AETHER_API_KEYS_FILE` names (#828),
    /// ahead of any TOML. An unreadable, empty or conflicting file yields no env keys here; the node's start-up gate refuses it.
    static Map<String, ApiKeyEntry> resolveApiKeys(TomlDocument doc, Fn1<String, String> env) {
        var envKeys = ClusterSecretSource.resolve(env, "AETHER_API_KEYS").fold(_ -> Option.<String> none(),
                                                                               found -> found);

        if (envKeys.isPresent()) {
            return parseEnvApiKeys(envKeys.unwrap());
        }

        var richKeys = parseRichApiKeys(doc);

        if (!richKeys.isEmpty()) {
            return richKeys;
        }

        return doc.getStringList("app-http", "api_keys")
                  .map(ConfigLoader::wrapSimpleKeyList)
                  .or(Map.of());
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Map<String, ApiKeyEntry> parseEnvApiKeys(String envValue) {
        var result = new HashMap<String, ApiKeyEntry>();

        for (var segment : envValue.split(";")) {
            var parts = segment.trim().split(":", 4);

            if (parts.length >= 1 && !parts[0].isBlank()) {
                var keyValue = parts[0].trim();
                var name = parts.length >= 2
                           ? parts[1].trim()
                           : ApiKeyEntry.defaultEntry(keyValue).name();
                var roles = parts.length >= 3
                            ? Set.of(parts[2].trim().split(","))
                            : Set.of("service");
                var authRole = parts.length >= 4
                               ? parts[3].trim()
                               : ApiKeyEntry.DEFAULT_ROLE;

                result.put(keyValue, ApiKeyEntry.apiKeyEntry(name, roles, authRole));
            }
        }

        return Map.copyOf(result);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Map<String, ApiKeyEntry> parseRichApiKeys(TomlDocument doc) {
        var prefix = "app-http.api-keys.";
        var result = new HashMap<String, ApiKeyEntry>();

        for (var sectionName : doc.sectionNames()) {
            if (sectionName.startsWith(prefix)) {
                var keyValue = sectionName.substring(prefix.length());
                var name = doc.getString(sectionName, "name").or(ApiKeyEntry.defaultEntry(keyValue).name());
                var roles = doc.getStringList(sectionName, "roles").map(Set::copyOf).or(Set.of("service"));
                var authRole = doc.getString(sectionName, "authorization_role").or(ApiKeyEntry.DEFAULT_ROLE);

                result.put(keyValue, ApiKeyEntry.apiKeyEntry(name, roles, authRole));
            }
        }

        return Map.copyOf(result);
    }

    private static Map<String, ApiKeyEntry> wrapSimpleKeyList(List<String> keys) {
        var result = new HashMap<String, ApiKeyEntry>();

        keys.forEach(key -> result.put(key, ApiKeyEntry.defaultEntry(key)));

        return Map.copyOf(result);
    }

    @SuppressWarnings("JBCT-RET-07")
    private static void mergeCliOverrides(Map<String, String> overrides, AetherConfig.Builder builder) {
        if (overrides.containsKey("nodes")) {
            builder.nodes(Number.parseInt(overrides.get("nodes")).getOrThrow(IllegalArgumentException::new,
                                                                             "invalid --nodes"));
        }

        if (overrides.containsKey("heap")) {
            builder.heap(overrides.get("heap"));
        }

        if (overrides.containsKey("tls")) {
            builder.tls(strictBoolean(overrides.get("tls")).getOrThrow(IllegalArgumentException::new, "invalid --tls"));
        }
    }

    /// #1455: `[backup] enabled = "yes"` (or the environment variable set to it) read as a DISABLED backup and the node ran
    /// without one, and `--tls=yes` turned TLS off; a value that is not `true` or `false` refuses the load like the
    /// neighbouring `restore` mode does. The CLI overrides use the same parse as the TOML booleans.
    private static Result<Boolean> strictBoolean(String raw) {
        return switch (raw.toLowerCase()) {
            case "true" -> success(true);
            case "false" -> success(false);
            default -> ConfigError.invalidConfig("'" + raw + "' is not true or false").result();
        };
    }

    /// The one parse of a boolean setting (`"true"`, any case, exact: no trimming), shared with cluster bootstrap's PF-34 so the bootstrap check
    /// and the node agree on whether `[app-http] enabled` is on.
    /// The filesystem cluster bootstrap judges a node config against: every file exists. The certificate, key and CA paths
    /// name files on the node (`/etc/aether/tls/*.pem`), not on the operator's machine, so the operator's filesystem must not
    /// change the verdict either. Path syntax is still validated; the node's own load checks the real files.
    public static final Predicate<Path> PREFLIGHT_FILESYSTEM = _ -> true;
    /// The environment cluster bootstrap judges a node config against: empty. The process environment of the operator's
    /// machine describes the operator, not the node that will boot (its environment arrives with its own user data), so it
    /// must not change the verdict.
    public static final Fn1<String, String> PREFLIGHT_ENVIRONMENT = Map.<String, String> of()::get;

    /// Whether a node would load this composed node document: the node's FULL load path (parse, then
    /// [ConfigValidator#validate] and [StorageEncryptionConfigValidator#validate]), judged against an empty environment
    /// ([#PREFLIGHT_ENVIRONMENT]). Cluster bootstrap runs it per source so a fleet whose config the node would refuse at
    /// boot is refused before it is provisioned (PF-35). It cannot see what the node's own environment will add.
    public static Result<Unit> checkNodeDocument(TomlDocument doc) {
        return checkNodeDocument(doc, PREFLIGHT_ENVIRONMENT, PREFLIGHT_FILESYSTEM);
    }

    static Result<Unit> checkNodeDocument(TomlDocument doc, Fn1<String, String> env, Predicate<Path> fileExists) {
        return loadDocument(doc, Map.of(), env, fileExists).mapToUnit();
    }

    public static boolean toBooleanValue(String s) {
        return "true".equalsIgnoreCase(s);
    }

    public static Duration parseDuration(String value) {
        var normalized = value.trim().toLowerCase();

        return normalized.isEmpty()
               ? Duration.ofSeconds(1)
               : durationFromSuffix(normalized);
    }

    private static Duration durationFromSuffix(String normalized) {
        if (normalized.endsWith("ms")) {
            return parseDurationMs(normalized);
        }

        if (normalized.endsWith("s")) {
            return parseDurationSeconds(normalized);
        }

        if (normalized.endsWith("m")) {
            return parseDurationMinutes(normalized);
        }

        return parseDurationRaw(normalized);
    }

    private static final Duration DEFAULT_DURATION = Duration.ofSeconds(1);

    private static Duration parseDurationMs(String normalized) {
        return Number.parseLong(normalized.substring(0,
                                                     normalized.length() - 2))
                     .map(Duration::ofMillis)
                     .or(DEFAULT_DURATION);
    }

    private static Duration parseDurationSeconds(String normalized) {
        return Number.parseLong(normalized.substring(0,
                                                     normalized.length() - 1))
                     .map(Duration::ofSeconds)
                     .or(DEFAULT_DURATION);
    }

    private static Duration parseDurationMinutes(String normalized) {
        return Number.parseLong(normalized.substring(0,
                                                     normalized.length() - 1))
                     .map(Duration::ofMinutes)
                     .or(DEFAULT_DURATION);
    }

    private static Duration parseDurationRaw(String normalized) {
        return Number.parseLong(normalized)
                     .map(Duration::ofSeconds)
                     .or(DEFAULT_DURATION);
    }

    public sealed interface ConfigError extends Cause {
        record unused() implements ConfigError {
            @Override
            public String message() {
                return "unused";
            }
        }

        record InvalidConfig(String reason) implements ConfigError {
            public static Result<InvalidConfig> invalidConfig(String reason, boolean validated) {
                return success(new InvalidConfig(reason));
            }

            @Override
            public String message() {
                return "Invalid configuration: " + reason;
            }
        }

        static ConfigError invalidConfig(String reason) {
            return InvalidConfig.invalidConfig(reason, true).unwrap();
        }
    }
}
