package org.pragmatica.aether.config;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.pragmatica.lang.Result.success;

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
        return builder;
    }

    private static void populateClusterConfig(TomlDocument doc, AetherConfig.Builder builder) {
        doc.getInt("cluster", "nodes")
           .onPresent(builder::nodes);
        doc.getString("cluster", "tls")
           .map(ConfigLoader::toBooleanValue)
           .onPresent(builder::tls);
        builder.ports(portsFromDocument(doc));
    }

    private static PortsConfig portsFromDocument(TomlDocument doc) {
        var mgmtPort = doc.getInt("cluster.ports", "management")
                          .or(PortsConfig.DEFAULT_MANAGEMENT_PORT);
        var clusterPort = doc.getInt("cluster.ports", "cluster")
                             .or(PortsConfig.DEFAULT_CLUSTER_PORT);
        return PortsConfig.portsConfig(mgmtPort, clusterPort)
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
        return TlsConfig.tlsConfig(autoGen, certPath, keyPath, caPath)
                        .unwrap();
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
        var evalInterval = doc.getLong("ttm", "evaluation_interval_ms")
                              .or(60_000L);
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
