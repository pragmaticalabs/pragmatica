package org.pragmatica.aether.config;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.DataSize;
import org.pragmatica.lang.parse.Number;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Loads Aether configuration from TOML files with environment-aware defaults.
///
///
/// Configuration resolution order (highest priority first):
/// <ol>
///   - Explicit overrides via Builder
///   - Values from TOML file
///   - Environment-specific defaults
/// </ol>
public final class ConfigLoader {
    private ConfigLoader() {}

    /// Load configuration from file path.
    public static Result<AetherConfig> load(Path path) {
        return TomlParser.parseFile(path)
                         .flatMap(ConfigLoader::fromDocument)
                         .flatMap(ConfigValidator::validate);
    }

    /// Load configuration from TOML string content.
    public static Result<AetherConfig> loadFromString(String content) {
        return TomlParser.parse(content)
                         .flatMap(ConfigLoader::fromDocument)
                         .flatMap(ConfigValidator::validate);
    }

    /// Load configuration with CLI overrides.
    public static Result<AetherConfig> loadWithOverrides(Path path,
                                                         Map<String, String> overrides) {
        return TomlParser.parseFile(path)
                         .flatMap(doc -> fromDocumentWithOverrides(doc, overrides))
                         .flatMap(ConfigValidator::validate);
    }

    /// Create configuration from environment defaults only.
    public static AetherConfig aetherConfig(Environment env) {
        return AetherConfig.aetherConfig(env);
    }

    private static Result<AetherConfig> fromDocument(TomlDocument doc) {
        return fromDocumentWithOverrides(doc, Map.of());
    }

    private static Result<AetherConfig> fromDocumentWithOverrides(TomlDocument doc,
                                                                  Map<String, String> overrides) {
        var envStr = overrides.getOrDefault("environment",
                                            doc.getString("cluster", "environment")
                                               .or("docker"));
        return Environment.environment(envStr)
                          .flatMap(environment -> assembleConfig(doc, overrides, environment));
    }

    @SuppressWarnings("JBCT-UTIL-01")
    private static Result<AetherConfig> assembleConfig(TomlDocument doc,
                                                       Map<String, String> overrides,
                                                       Environment environment) {
        try{
            var builder = populateBuilder(doc, environment);
            mergeCliOverrides(overrides, builder);
            return success(builder.build());
        } catch (IllegalArgumentException e) {
            return ConfigError.invalidConfig(e.getMessage())
                              .result();
        }
    }

    private static AetherConfig.Builder populateBuilder(TomlDocument doc, Environment environment) {
        var builder = AetherConfig.builder()
                                  .withEnvironment(environment);
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
        populateCloudConfig(doc, builder);
        populateEndpointsConfig(doc, builder);
        return builder;
    }

    // --- Parse helpers ---
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
                  .flatMap(v -> DataSize.dataSize(v)
                                        .option())
                  .map(DataSize::bytesAsInt)
                  .or(defaultValue);
    }

    // --- Section populators ---
    private static void populateClusterConfig(TomlDocument doc, AetherConfig.Builder builder) {
        doc.getInt("cluster", "nodes")
           .onPresent(builder::nodes);
        doc.getString("cluster", "tls")
           .map(ConfigLoader::toBooleanValue)
           .onPresent(builder::tls);
        doc.getInt("cluster", "core_max")
           .onPresent(builder::coreMax);
        builder.ports(portsFromDocument(doc));
    }

    private static PortsConfig portsFromDocument(TomlDocument doc) {
        var mgmtPort = doc.getInt("cluster.ports", "management")
                          .or(PortsConfig.DEFAULT_MANAGEMENT_PORT);
        var clusterPort = doc.getInt("cluster.ports", "cluster")
                             .or(PortsConfig.DEFAULT_CLUSTER_PORT);
        var mgmtProtocol = doc.getString("cluster.ports", "management_protocol")
                              .flatMap(HttpProtocol::httpProtocol)
                              .or(HttpProtocol.H1);
        return PortsConfig.portsConfig(mgmtPort, clusterPort, mgmtProtocol)
                          .unwrap();
    }

    private static void populateNodeConfig(TomlDocument doc, AetherConfig.Builder builder) {
        doc.getString("node", "heap")
           .onPresent(builder::heap);
        doc.getString("node", "gc")
           .onPresent(builder::gc);
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
        var autoGen = doc.getString("tls", "auto_generate")
                         .map(ConfigLoader::toBooleanValue)
                         .or(true);
        var certPath = doc.getString("tls", "cert_path")
                          .or("");
        var keyPath = doc.getString("tls", "key_path")
                         .or("");
        var caPath = doc.getString("tls", "ca_path")
                        .or("");
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
        var network = doc.getString("docker", "network")
                         .or(DockerConfig.DEFAULT_NETWORK);
        var image = doc.getString("docker", "image")
                       .or(DockerConfig.DEFAULT_IMAGE);
        return DockerConfig.dockerConfig(network, image)
                           .unwrap();
    }

    private static void populateKubernetesConfig(TomlDocument doc,
                                                 AetherConfig.Builder builder,
                                                 Environment environment) {
        if (environment == Environment.KUBERNETES) {
            builder.kubernetesConfig(kubernetesFromDocument(doc));
        }
    }

    private static KubernetesConfig kubernetesFromDocument(TomlDocument doc) {
        var namespace = doc.getString("kubernetes", "namespace")
                           .or(KubernetesConfig.DEFAULT_NAMESPACE);
        var serviceType = doc.getString("kubernetes", "service_type")
                             .or(KubernetesConfig.DEFAULT_SERVICE_TYPE);
        var storageClass = doc.getString("kubernetes", "storage_class")
                              .or("");
        return KubernetesConfig.kubernetesConfig(namespace, serviceType, storageClass)
                               .unwrap();
    }

    private static void populateTtmConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var ttmEnabled = doc.getString("ttm", "enabled")
                            .map(ConfigLoader::toBooleanValue)
                            .or(false);
        if (ttmEnabled) {
            builder.ttm(ttmFromDocument(doc));
        }
    }

    private static TtmConfig ttmFromDocument(TomlDocument doc) {
        var modelPath = doc.getString("ttm", "model_path")
                           .or("models/ttm-aether.onnx");
        var inputWindow = doc.getInt("ttm", "input_window_minutes")
                             .or(60);
        var predictionHorizon = doc.getInt("ttm", "prediction_horizon")
                                   .or(1);
        // Try new string key first, fall back to old _ms long key
        var evalInterval = parseTimeSpanOrMs(doc,
                                             "ttm",
                                             "evaluation_interval",
                                             "evaluation_interval_ms",
                                             timeSpan(60).seconds());
        var confidence = doc.getDouble("ttm", "confidence_threshold")
                            .or(0.7);
        return TtmConfig.ttmConfig(modelPath, inputWindow, predictionHorizon, evalInterval, confidence, true)
                        .or(TtmConfig.ttmConfig());
    }

    @SuppressWarnings("JBCT-STY-05")
    private static void populateSliceConfig(TomlDocument doc, AetherConfig.Builder builder) {
        doc.getStringList("slice", "repositories")
           .map(repos -> SliceConfig.sliceConfigFromNames(repos))
           .flatMap(Result::option)
           .onPresent(builder::sliceConfig);
    }

    @SuppressWarnings("JBCT-STY-05")
    private static void populateAppHttpConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var enabled = doc.getString("app-http", "enabled")
                         .map(ConfigLoader::toBooleanValue)
                         .or(false);
        var port = doc.getInt("app-http", "port")
                      .or(AppHttpConfig.DEFAULT_APP_HTTP_PORT);
        // Try new string key first, fall back to old _ms long key
        var forwardTimeout = parseTimeSpanOrMs(doc,
                                               "app-http",
                                               "forward_timeout",
                                               "forward_timeout_ms",
                                               AppHttpConfig.DEFAULT_FORWARD_TIMEOUT);
        var maxRequestSize = parseDataSize(doc, "app-http", "max_request_size", AppHttpConfig.DEFAULT_MAX_REQUEST_SIZE);
        var explicitMode = doc.getString("app-http", "security_mode")
                              .flatMap(SecurityMode::securityMode);
        var apiKeys = resolveApiKeys(doc);
        // Auto-upgrade: if no explicit security_mode but apiKeys are present, infer API_KEY (backward compat)
        var securityMode = explicitMode.or(apiKeys.isEmpty()
                                           ? SecurityMode.NONE
                                           : SecurityMode.API_KEY);
        var jwtConfig = parseJwtConfig(doc);
        var httpProtocol = doc.getString("app-http", "protocol")
                              .flatMap(HttpProtocol::httpProtocol)
                              .or(HttpProtocol.H1);
        if (enabled || !apiKeys.isEmpty()) {
            builder.appHttp(AppHttpConfig.appHttpConfig(enabled,
                                                        port,
                                                        apiKeys,
                                                        forwardTimeout,
                                                        maxRequestSize,
                                                        securityMode,
                                                        jwtConfig,
                                                        httpProtocol)
                                         .unwrap());
        }
    }

    private static Option<JwtConfig> parseJwtConfig(TomlDocument doc) {
        return doc.getString("app-http", "jwks_url")
                  .map(jwksUrl -> buildJwtConfig(doc, jwksUrl));
    }

    private static JwtConfig buildJwtConfig(TomlDocument doc, String jwksUrl) {
        var issuer = doc.getString("app-http", "issuer");
        var audience = doc.getString("app-http", "audience");
        var roleClaim = doc.getString("app-http", "role_claim")
                           .or(JwtConfig.DEFAULT_ROLE_CLAIM);
        var cacheTtl = parseLong(doc, "app-http", "jwks_cache_ttl_seconds", JwtConfig.DEFAULT_CACHE_TTL_SECONDS);
        var clockSkew = parseLong(doc, "app-http", "clock_skew_seconds", JwtConfig.DEFAULT_CLOCK_SKEW_SECONDS);
        return JwtConfig.jwtConfig(jwksUrl, issuer, audience, roleClaim, cacheTtl, clockSkew)
                        .unwrap();
    }

    private static void populateBackupConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var enabled = doc.getString("backup", "enabled")
                         .map(ConfigLoader::toBooleanValue)
                         .or(false);
        if (enabled) {
            var interval = doc.getString("backup", "interval")
                              .or("5m");
            var path = doc.getString("backup", "path")
                          .or("");
            var remote = doc.getString("backup", "remote")
                            .or("");
            builder.backup(BackupConfig.backupConfig(true, interval, path, remote));
        }
    }

    private static void populateDhtReplicationConfig(TomlDocument doc, AetherConfig.Builder builder) {
        var hasDelay = doc.getString("dht.replication", "cooldown_delay")
                          .isPresent() || doc.getLong("dht.replication", "cooldown_delay_ms")
                                             .isPresent();
        var hasRate = doc.getInt("dht.replication", "cooldown_rate")
                         .isPresent();
        var hasRf = doc.getInt("dht.replication", "target_rf")
                       .isPresent();
        if (hasDelay || hasRate || hasRf) {
            var delay = parseTimeSpanOrMs(doc,
                                          "dht.replication",
                                          "cooldown_delay",
                                          "cooldown_delay_ms",
                                          DhtReplicationConfig.DEFAULT_COOLDOWN_DELAY);
            var rate = doc.getInt("dht.replication", "cooldown_rate")
                          .or(DhtReplicationConfig.DEFAULT_COOLDOWN_RATE);
            var rf = doc.getInt("dht.replication", "target_rf")
                        .or(DhtReplicationConfig.DEFAULT_TARGET_RF);
            builder.dhtReplication(DhtReplicationConfig.dhtReplicationConfig(delay, rate, rf));
        }
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static void populateTimeoutsConfig(TomlDocument doc, AetherConfig.Builder builder) {
        // Only populate if any timeouts sections exist
        var hasTimeoutsSection = doc.sectionNames()
                                    .stream()
                                    .anyMatch(s -> s.startsWith("timeouts"));
        if (!hasTimeoutsSection) {
            return;
        }
        builder.timeouts(timeoutsFromDocument(doc));
    }

    private static void populateCloudConfig(TomlDocument doc, AetherConfig.Builder builder) {
        doc.getString("cloud", "provider")
           .onPresent(provider -> applyCloudConfig(doc, builder, provider));
    }

    private static void applyCloudConfig(TomlDocument doc, AetherConfig.Builder builder, String provider) {
        var credentials = doc.getSection("cloud.credentials");
        var compute = doc.getSection("cloud.compute");
        var cc = CloudConfig.cloudConfig(provider,
                                         resolveEnvVars(credentials),
                                         resolveEnvVars(compute))
                            .unwrap();
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
                if (!instanceName.isEmpty()) {
                    instances.put(instanceName, storageFromSection(doc, sectionName));
                }
            }
        }
        if (!instances.isEmpty()) {
            builder.storage(Map.copyOf(instances));
        }
    }

    private static StorageConfig storageFromSection(TomlDocument doc, String sectionName) {
        var memoryMaxBytes = parseLong(doc, sectionName, "memory_max_bytes", 256 * 1024 * 1024);
        var diskMaxBytes = parseLong(doc, sectionName, "disk_max_bytes", 10L * 1024 * 1024 * 1024);
        var diskPath = doc.getString(sectionName, "disk_path")
                          .or("/data/aether/storage");
        var snapshotPath = doc.getString(sectionName, "snapshot_path")
                              .or("/data/aether/metadata-snapshots");
        var mutationThreshold = parseInt(doc, sectionName, "snapshot_mutation_threshold", 1000);
        var snapshotInterval = doc.getString(sectionName, "snapshot_max_interval")
                                  .or("60s");
        var retentionCount = parseInt(doc, sectionName, "snapshot_retention_count", 5);
        return StorageConfig.storageConfig(memoryMaxBytes, diskMaxBytes, diskPath, snapshotPath,
                                           mutationThreshold, snapshotInterval, retentionCount);
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
        var host = doc.getString(sectionName, "host")
                      .or("localhost");
        var port = doc.getInt(sectionName, "port")
                      .or(5432);
        var username = doc.getString(sectionName, "username")
                          .or("");
        var password = doc.getString(sectionName, "password")
                          .map(ConfigLoader::resolveEnvVar)
                          .or("");
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
            return Option.option(System.getenv(envName))
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
                                  parseScalingTimeouts(doc, defaults.scaling()));
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
                                                     parseInt(doc, "timeouts.forwarding", "max_retries", d.maxRetries()));
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
                                                                d.channelProtection()));
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

    private static TimeoutsConfig.SwimTimeouts parseSwimTimeouts(TomlDocument doc,
                                                                 TimeoutsConfig.SwimTimeouts d) {
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

    private static TimeoutsConfig.DhtTimeouts parseDhtTimeouts(TomlDocument doc,
                                                               TimeoutsConfig.DhtTimeouts d) {
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

    /// Parse a TimeSpan from either a new string key or a legacy _ms long key.
    /// Tries the string key first (e.g., "forward_timeout" = "5s"),
    /// then falls back to the long key (e.g., "forward_timeout_ms" = 5000).
    private static TimeSpan parseTimeSpanOrMs(TomlDocument doc,
                                              String section,
                                              String stringKey,
                                              String msKey,
                                              TimeSpan defaultValue) {
        // Try new string-based key first
        var fromString = doc.getString(section, stringKey)
                            .flatMap(v -> org.pragmatica.lang.parse.TimeSpan.timeSpan(v)
                                             .option())
                            .map(ts -> TimeSpan.fromDuration(ts.duration()));
        if (fromString.isPresent()) {
            return fromString.unwrap();
        }
        // Fall back to legacy _ms long key
        return doc.getLong(section, msKey)
                  .map(ms -> timeSpan(ms).millis())
                  .or(defaultValue);
    }

    // --- API keys ---
    private static Map<String, ApiKeyEntry> resolveApiKeys(TomlDocument doc) {
        // 1. Environment variable has highest priority
        var envKeys = System.getenv("AETHER_API_KEYS");
        if (envKeys != null && !envKeys.isBlank()) {
            return parseEnvApiKeys(envKeys);
        }
        // 2. Rich TOML sections: [app-http.api-keys.<keyvalue>]
        var richKeys = parseRichApiKeys(doc);
        if (!richKeys.isEmpty()) {
            return richKeys;
        }
        // 3. Simple string list: app-http.api_keys = ["key1", "key2"]
        return doc.getStringList("app-http", "api_keys")
                  .map(ConfigLoader::wrapSimpleKeyList)
                  .or(Map.of());
    }

    /// Parse env format: "key1:name1:role1,role2:authRole;key2:name2:role3"
    @SuppressWarnings("JBCT-PAT-01")
    private static Map<String, ApiKeyEntry> parseEnvApiKeys(String envValue) {
        var result = new HashMap<String, ApiKeyEntry>();
        for (var segment : envValue.split(";")) {
            var parts = segment.trim()
                               .split(":", 4);
            if (parts.length >= 1 && !parts[0].isBlank()) {
                var keyValue = parts[0].trim();
                var name = parts.length >= 2
                           ? parts[1].trim()
                           : ApiKeyEntry.defaultEntry(keyValue)
                                        .name();
                var roles = parts.length >= 3
                            ? Set.of(parts[2].trim()
                                          .split(","))
                            : Set.of("service");
                var authRole = parts.length >= 4
                               ? parts[3].trim()
                               : "ADMIN";
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
                var name = doc.getString(sectionName, "name")
                              .or(ApiKeyEntry.defaultEntry(keyValue)
                                             .name());
                var roles = doc.getStringList(sectionName, "roles")
                               .map(Set::copyOf)
                               .or(Set.of("service"));
                var authRole = doc.getString(sectionName, "authorization_role")
                                  .or("ADMIN");
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

    private static void mergeCliOverrides(Map<String, String> overrides, AetherConfig.Builder builder) {
        if (overrides.containsKey("nodes")) {
            Number.parseInt(overrides.get("nodes"))
                  .onSuccess(builder::nodes);
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

    /// Parse duration from string (e.g., "1s", "500ms", "5m").
    /// Blank input returns default of 1 second.
    ///
    /// @param value duration string, must not be null
    public static Duration parseDuration(String value) {
        var normalized = value.trim()
                              .toLowerCase();
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

    private static Duration parseDurationMs(String normalized) {
        return Number.parseLong(normalized.substring(0,
                                                     normalized.length() - 2))
                     .map(Duration::ofMillis)
                     .unwrap();
    }

    private static Duration parseDurationSeconds(String normalized) {
        return Number.parseLong(normalized.substring(0,
                                                     normalized.length() - 1))
                     .map(Duration::ofSeconds)
                     .unwrap();
    }

    private static Duration parseDurationMinutes(String normalized) {
        return Number.parseLong(normalized.substring(0,
                                                     normalized.length() - 1))
                     .map(Duration::ofMinutes)
                     .unwrap();
    }

    private static Duration parseDurationRaw(String normalized) {
        return Number.parseLong(normalized)
                     .map(Duration::ofSeconds)
                     .unwrap();
    }

    /// Configuration loading errors.
    public sealed interface ConfigError extends Cause {
        record unused() implements ConfigError {
            @Override
            public String message() {
                return "unused";
            }
        }

        record InvalidConfig(String reason) implements ConfigError {
            /// Factory method following JBCT naming convention.
            public static Result<InvalidConfig> invalidConfig(String reason, boolean validated) {
                return success(new InvalidConfig(reason));
            }

            @Override
            public String message() {
                return "Invalid configuration: " + reason;
            }
        }

        static ConfigError invalidConfig(String reason) {
            return InvalidConfig.invalidConfig(reason, true)
                                .unwrap();
        }
    }
}
