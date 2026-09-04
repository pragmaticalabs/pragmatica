// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.DataSize;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public final class ConfigLoader {
    private ConfigLoader() {}

    public static Result<AetherConfig> load(Path path) {
        return TomlParser.parseFile(path)
                         .flatMap(ConfigLoader::fromDocument)
                         .flatMap(ConfigValidator::validate)
                         .flatMap(StorageEncryptionConfigValidator::validate);
    }

    public static Result<AetherConfig> loadFromString(String content) {
        return TomlParser.parse(content)
                         .flatMap(ConfigLoader::fromDocument)
                         .flatMap(ConfigValidator::validate)
                         .flatMap(StorageEncryptionConfigValidator::validate);
    }

    public static Result<AetherConfig> loadWithOverrides(Path path, Map<String, String> overrides) {
        return TomlParser.parseFile(path)
                         .flatMap(doc -> fromDocumentWithOverrides(doc, overrides))
                         .flatMap(ConfigValidator::validate)
                         .flatMap(StorageEncryptionConfigValidator::validate);
    }

    public static AetherConfig aetherConfig(Environment env) {
        return AetherConfig.aetherConfig(env);
    }

    private static Result<AetherConfig> fromDocument(TomlDocument doc) {
        return fromDocumentWithOverrides(doc, Map.of());
    }

    private static Result<AetherConfig> fromDocumentWithOverrides(TomlDocument doc, Map<String, String> overrides) {
        var envStr = overrides.getOrDefault("environment",
                                            doc.getString("cluster", "environment").or("docker"));

        return Environment.environment(envStr).flatMap(environment -> assembleConfig(doc, overrides, environment));
    }

    @SuppressWarnings("JBCT-UTIL-01")
    private static Result<AetherConfig> assembleConfig(TomlDocument doc,
                                                       Map<String, String> overrides,
                                                       Environment environment) {
        try {
            var builder = populateBuilder(doc, environment);

            mergeCliOverrides(overrides, builder);

            return parseReadLinearization(doc).map(mode -> applyReadLinearization(builder.build(), mode));
        } catch (IllegalArgumentException e) {
            return ConfigError.invalidConfig(e.getMessage()).result();
        }
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

    private static AetherConfig.Builder populateBuilder(TomlDocument doc, Environment environment) {
        var builder = AetherConfig.builder().withEnvironment(environment);

        populateClusterConfig(doc, builder);
        populateNodeConfig(doc, builder);
        populateTlsConfig(doc, builder, environment);
        populateDockerConfig(doc, builder, environment);
        populateKubernetesConfig(doc, builder, environment);
        populateTtmConfig(doc, builder);
        populateSliceConfig(doc, builder);
        populateAppHttpConfig(doc, builder);
        populateBackupConfig(doc, builder);
        populateDhtReplicationConfig(doc, builder);
        populateTimeoutsConfig(doc, builder);
        populateStorageConfig(doc, builder);
        populateStorageEncryptionConfig(doc, builder);
        populateCloudConfig(doc, builder);
        populateEndpointsConfig(doc, builder);
        populateStreamingConfig(doc, builder);
        populateMembershipConfig(doc, builder);

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

        builder.streaming(StreamingConfig.streamingConfig(publishTimeout,
                                                          readTimeout,
                                                          maxBytes,
                                                          defaults.readLinearization(),
                                                          reshuffleConcurrency,
                                                          caughtUpMaxLagOffsets));
    }

    private static long parseDataSize(TomlDocument doc, String section, String key, long defaultValue) {
        var stringVal = doc.getString(section, key).flatMap(v -> DataSize.dataSize(v).option()).map(DataSize::bytes);

        if (stringVal.isPresent()) {
            return stringVal.or(defaultValue);
        }

        return doc.getLong(section, key)
                  .or(defaultValue);
    }

    private static TimeSpan parseTimeSpan(TomlDocument doc, String section, String key, TimeSpan defaultValue) {
        return doc.getString(section, key)
                  .flatMap(v -> org.pragmatica.lang.parse.TimeSpan.timeSpan(v)
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
        return doc.getString(section, key)
                  .flatMap(v -> DataSize.dataSize(v).option())
                  .map(DataSize::bytesAsInt)
                  .or(defaultValue);
    }

    @SuppressWarnings("JBCT-RET-07")
    private static void populateClusterConfig(TomlDocument doc, AetherConfig.Builder builder) {
        doc.getInt("cluster", "nodes").onPresent(builder::nodes);
        doc.getString("cluster", "tls").map(ConfigLoader::toBooleanValue).onPresent(builder::tls);
        doc.getInt("cluster", "core_max").onPresent(builder::coreMax);
        doc.getInt("cluster", "max_nodes").onPresent(builder::maxNodes);
        builder.ports(portsFromDocument(doc));
    }

    private static PortsConfig portsFromDocument(TomlDocument doc) {
        var mgmtPort = doc.getInt("cluster.ports", "management").or(PortsConfig.DEFAULT_MANAGEMENT_PORT);
        var clusterPort = doc.getInt("cluster.ports", "cluster").or(PortsConfig.DEFAULT_CLUSTER_PORT);
        var mgmtProtocol = doc.getString("cluster.ports", "management_protocol")
                              .flatMap(HttpProtocol::httpProtocol)
                              .or(HttpProtocol.H1);

        return PortsConfig.portsConfig(mgmtPort, clusterPort, mgmtProtocol).unwrap();
    }

    @SuppressWarnings("JBCT-RET-07")
    private static void populateNodeConfig(TomlDocument doc, AetherConfig.Builder builder) {
        doc.getString("node", "heap").onPresent(builder::heap);
        doc.getString("node", "gc").onPresent(builder::gc);
    }

    private static void populateTlsConfig(TomlDocument doc, AetherConfig.Builder builder, Environment environment) {
        var tlsEnabled = isTlsEnabled(doc, environment);

        if (tlsEnabled) {
            builder.tlsConfig(tlsFromDocument(doc));
        }
    }

    private static boolean isTlsEnabled(TomlDocument doc, Environment environment) {
        return doc.getString("cluster", "tls")
                  .map(ConfigLoader::toBooleanValue)
                  .or(environment.defaultTls());
    }

    private static TlsConfig tlsFromDocument(TomlDocument doc) {
        var autoGen = doc.getString("tls", "auto_generate").map(ConfigLoader::toBooleanValue).or(true);
        var certPath = doc.getString("tls", "cert_path").or("");
        var keyPath = doc.getString("tls", "key_path").or("");
        var caPath = doc.getString("tls", "ca_path").or("");
        var clusterSecret = doc.getString("tls", "cluster_secret")
                               .orElse(Option.option(System.getenv("AETHER_CLUSTER_SECRET")))
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
        var ttmEnabled = doc.getString("ttm", "enabled").map(ConfigLoader::toBooleanValue).or(false);

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
        doc.getStringList("slice", "repositories")
           .map(repos -> SliceConfig.sliceConfigFromNames(repos))
           .flatMap(Result::option)
           .onPresent(builder::sliceConfig);
    }

    @SuppressWarnings("JBCT-STY-05")
    private static void populateAppHttpConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var enabled = doc.getString("app-http", "enabled").map(ConfigLoader::toBooleanValue).or(false);
        var port = doc.getInt("app-http", "port").or(AppHttpConfig.DEFAULT_APP_HTTP_PORT);
        var maxRequestSize = parseDataSize(doc, "app-http", "max_request_size", AppHttpConfig.DEFAULT_MAX_REQUEST_SIZE);
        var explicitMode = doc.getString("app-http", "security_mode").flatMap(SecurityMode::securityMode);
        var apiKeys = resolveApiKeys(doc);
        // #290: secure by default. When `security_mode` is not configured the effective mode is
        // API_KEY (was: NONE unless api-keys present), so a default-config node's management plane and
        // dashboard require authentication rather than serving the control plane wide open. The
        // cluster-wide bootstrap admin key (BootstrapAdminKeyRegistrar) supplies a credential when
        // none was provisioned. An explicit `security_mode` (including "none") always wins.
        var securityMode = explicitMode.or(SecurityMode.API_KEY);
        var jwtConfig = parseJwtConfig(doc);
        var httpProtocol = doc.getString("app-http", "protocol")
                              .flatMap(HttpProtocol::httpProtocol)
                              .or(HttpProtocol.H1);
        // #198 §7: cluster-level API-version detection mode + header name (per-slice override is a
        // documented follow-up). Defaults keep path mode (byte-identical to pre-#198-C3b behavior).
        var apiVersioningDetection = doc.getString("app-http", "api_versioning_detection")
                                        .flatMap(ApiVersioningDetection::apiVersioningDetection)
                                        .or(ApiVersioningDetection.PATH);
        var apiVersionHeaderName = doc.getString("app-http", "api_version_header")
                                      .or(AppHttpConfig.DEFAULT_API_VERSION_HEADER);

        builder.appHttp(AppHttpConfig.appHttpConfig(enabled,
                                                    port,
                                                    apiKeys,
                                                    maxRequestSize,
                                                    securityMode,
                                                    jwtConfig,
                                                    httpProtocol,
                                                    apiVersioningDetection,
                                                    apiVersionHeaderName).unwrap());
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

    private static void populateBackupConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var enabled = doc.getString("backup", "enabled").map(ConfigLoader::toBooleanValue).or(false);

        if (enabled) {
            var interval = doc.getString("backup", "interval").or("5m");
            var path = doc.getString("backup", "path").or("");
            var remote = doc.getString("backup", "remote").or("");

            builder.backup(BackupConfig.backupConfig(true, interval, path, remote));
        }
    }

    private static void populateDhtReplicationConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var hasDelay = doc.getString("dht.replication", "cooldown_delay").isPresent() || doc.getLong("dht.replication",
                                                                                                     "cooldown_delay_ms")
                                                                                            .isPresent();
        var hasRate = doc.getInt("dht.replication", "cooldown_rate").isPresent();
        var hasRf = doc.getInt("dht.replication", "target_rf").isPresent();

        if (hasDelay || hasRate || hasRf) {
            var delay = parseTimeSpanOrMs(doc,
                                          "dht.replication",
                                          "cooldown_delay",
                                          "cooldown_delay_ms",
                                          DhtReplicationConfig.DEFAULT_COOLDOWN_DELAY);
            var rate = doc.getInt("dht.replication", "cooldown_rate").or(DhtReplicationConfig.DEFAULT_COOLDOWN_RATE);
            var rf = doc.getInt("dht.replication", "target_rf").or(DhtReplicationConfig.DEFAULT_TARGET_RF);

            builder.dhtReplication(DhtReplicationConfig.dhtReplicationConfig(delay, rate, rf));
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

    private static void populateCloudConfig(TomlDocument doc, AetherConfig.Builder builder) {
        doc.getString("cloud", "provider").onPresent(provider -> applyCloudConfig(doc, builder, provider));
    }

    private static void applyCloudConfig(TomlDocument doc, AetherConfig.Builder builder, String provider) {
        var credentials = doc.getSection("cloud.credentials");
        var compute = doc.getSection("cloud.compute");
        var cc = CloudConfig.cloudConfig(provider, resolveEnvVars(credentials), resolveEnvVars(compute)).unwrap();
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
                if (!instanceName.isEmpty() && !instanceName.equals("encryption") && !instanceName.startsWith("encryption.")) {
                    instances.put(instanceName, storageFromSection(doc, sectionName));
                }
            }
        }

        if (!instances.isEmpty()) {
            builder.storage(Map.copyOf(instances));
        }
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

    private static StorageConfig storageFromSection(TomlDocument doc, String sectionName) {
        var memoryMaxBytes = parseLong(doc, sectionName, "memory_max_bytes", 256 * 1024 * 1024);
        var diskMaxBytes = parseLong(doc, sectionName, "disk_max_bytes", 10L * 1024 * 1024 * 1024);
        var diskPath = doc.getString(sectionName, "disk_path").or("/data/aether/storage");
        var snapshotPath = doc.getString(sectionName, "snapshot_path").or("/data/aether/metadata-snapshots");
        var mutationThreshold = parseInt(doc, sectionName, "snapshot_mutation_threshold", 1000);
        var snapshotInterval = doc.getString(sectionName, "snapshot_max_interval").or("60s");
        var retentionCount = parseInt(doc, sectionName, "snapshot_retention_count", 5);
        // #634-3: the stream WAL's base dir as a first-class storage key (read from the `streams`
        // instance; empty = derive the pre-#634-3 sibling path, so absent keys change nothing).
        var walPath = doc.getString(sectionName, "wal_path").or("");
        // #253: per-instance opt-in into encryption; requires [storage.encryption] to carry a keyring
        // (enforced by StorageEncryptionConfigValidator, not here).
        var encrypted = doc.getBoolean(sectionName, "encrypted").or(false);

        return StorageConfig.storageConfig(memoryMaxBytes,
                                           diskMaxBytes,
                                           diskPath,
                                           snapshotPath,
                                           mutationThreshold,
                                           snapshotInterval,
                                           retentionCount,
                                           walPath,
                                           encrypted);
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

    private static Map<String, String> resolveEnvVars(Map<String, String> map) {
        var resolved = new HashMap<String, String>();

        map.forEach((k, v) -> resolved.put(k, resolveEnvVar(v)));

        return resolved;
    }

    private static String resolveEnvVar(String value) {
        if (value.startsWith("${env:") && value.endsWith("}")) {
            var envName = value.substring(6, value.length() - 1);

            return Option.option(System.getenv(envName)).or(value);
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
                                                                      d.cleanupGracePeriod()),
                                                        parseTimeSpan(doc,
                                                                      "timeouts.rolling_update",
                                                                      "rollback_cooldown",
                                                                      d.rollbackCooldown()));
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
                                                                d.communityAbsence()));
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
                                                                      "dashboard_broadcast",
                                                                      d.dashboardBroadcast()),
                                                        parseTimeSpan(doc,
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
                                                                "auto_heal_retry",
                                                                d.autoHealRetry()),
                                                  parseTimeSpan(doc,
                                                                "timeouts.scaling",
                                                                "auto_heal_startup_cooldown",
                                                                d.autoHealStartupCooldown()));
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
        var fromString = doc.getString(section, stringKey)
                            .flatMap(v -> org.pragmatica.lang.parse.TimeSpan.timeSpan(v)
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
        var envKeys = System.getenv("AETHER_API_KEYS");

        if (envKeys != null && !envKeys.isBlank()) {
            return parseEnvApiKeys(envKeys);
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
            Number.parseInt(overrides.get("nodes")).onSuccess(builder::nodes);
        }

        if (overrides.containsKey("heap")) {
            builder.heap(overrides.get("heap"));
        }

        if (overrides.containsKey("tls")) {
            builder.tls(Boolean.parseBoolean(overrides.get("tls")));
        }
    }

    private static boolean toBooleanValue(String s) {
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
