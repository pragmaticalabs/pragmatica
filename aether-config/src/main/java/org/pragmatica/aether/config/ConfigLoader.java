package org.pragmatica.aether.config;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Cause;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Loads Aether configuration from TOML files with environment-aware defaults.
 *
 * <p>Configuration resolution order (highest priority first):
 * <ol>
 *   <li>Explicit overrides via Builder</li>
 *   <li>Values from TOML file</li>
 *   <li>Environment-specific defaults</li>
 * </ol>
 */
public final class ConfigLoader {
    private ConfigLoader() {}

    /**
     * Load configuration from file path.
     */
    public static Result<AetherConfig> load(Path path) {
        return TomlParser.parseFile(path)
                         .flatMap(ConfigLoader::fromDocument)
                         .flatMap(ConfigValidator::validate);
    }

    /**
     * Load configuration from TOML string content.
     */
    public static Result<AetherConfig> loadFromString(String content) {
        return TomlParser.parse(content)
                         .flatMap(ConfigLoader::fromDocument)
                         .flatMap(ConfigValidator::validate);
    }

    /**
     * Load configuration with CLI overrides.
     */
    public static Result<AetherConfig> loadWithOverrides(Path path,
                                                         Map<String, String> overrides) {
        return TomlParser.parseFile(path)
                         .flatMap(doc -> fromDocumentWithOverrides(doc, overrides))
                         .flatMap(ConfigValidator::validate);
    }

    /**
     * Create configuration from environment defaults only.
     */
    public static AetherConfig fromEnvironment(Environment env) {
        return AetherConfig.forEnvironment(env);
    }

    private static Result<AetherConfig> fromDocument(TomlDocument doc) {
        return fromDocumentWithOverrides(doc, Map.of());
    }

    private static Result<AetherConfig> fromDocumentWithOverrides(TomlDocument doc,
                                                                  Map<String, String> overrides) {
        try{
            // Determine environment
            var envStr = overrides.getOrDefault("environment",
                                                doc.getString("cluster", "environment")
                                                   .or("docker"));
            var environment = Environment.fromString(envStr);
            // Start with environment defaults
            var builder = AetherConfig.builder()
                                      .environment(environment);
            // Apply TOML values
            doc.getInt("cluster", "nodes")
               .onPresent(builder::nodes);
            doc.getString("cluster", "tls")
               .map(s -> "true".equalsIgnoreCase(s))
               .onPresent(builder::tls);
            // Ports
            var mgmtPort = doc.getInt("cluster.ports", "management")
                              .or(PortsConfig.DEFAULT_MANAGEMENT_PORT);
            var clusterPort = doc.getInt("cluster.ports", "cluster")
                                 .or(PortsConfig.DEFAULT_CLUSTER_PORT);
            builder.ports(new PortsConfig(mgmtPort, clusterPort));
            // Node config
            doc.getString("node", "heap")
               .onPresent(builder::heap);
            doc.getString("node", "gc")
               .onPresent(builder::gc);
            // TLS config
            var tlsEnabled = doc.getString("cluster", "tls")
                                .map(s -> "true".equalsIgnoreCase(s))
                                .or(environment.defaultTls());
            if (tlsEnabled) {
                var autoGen = doc.getString("tls", "auto_generate")
                                 .map(s -> "true".equalsIgnoreCase(s))
                                 .or(true);
                var certPath = doc.getString("tls", "cert_path")
                                  .or("");
                var keyPath = doc.getString("tls", "key_path")
                                 .or("");
                var caPath = doc.getString("tls", "ca_path")
                                .or("");
                builder.tlsConfig(new TlsConfig(autoGen, certPath, keyPath, caPath));
            }
            // Docker config
            if (environment == Environment.DOCKER) {
                var network = doc.getString("docker", "network")
                                 .or(DockerConfig.DEFAULT_NETWORK);
                var image = doc.getString("docker", "image")
                               .or(DockerConfig.DEFAULT_IMAGE);
                builder.dockerConfig(new DockerConfig(network, image));
            }
            // Kubernetes config
            if (environment == Environment.KUBERNETES) {
                var namespace = doc.getString("kubernetes", "namespace")
                                   .or(KubernetesConfig.DEFAULT_NAMESPACE);
                var serviceType = doc.getString("kubernetes", "service_type")
                                     .or(KubernetesConfig.DEFAULT_SERVICE_TYPE);
                var storageClass = doc.getString("kubernetes", "storage_class")
                                      .or("");
                builder.kubernetesConfig(new KubernetesConfig(namespace, serviceType, storageClass));
            }
            // Apply CLI overrides (highest priority)
            if (overrides.containsKey("nodes")) {
                builder.nodes(Integer.parseInt(overrides.get("nodes")));
            }
            if (overrides.containsKey("heap")) {
                builder.heap(overrides.get("heap"));
            }
            if (overrides.containsKey("tls")) {
                builder.tls(Boolean.parseBoolean(overrides.get("tls")));
            }
            return Result.success(builder.build());
        } catch (IllegalArgumentException e) {
            return ConfigError.invalidConfig(e.getMessage())
                              .result();
        }
    }

    /**
     * Parse duration from string (e.g., "1s", "500ms", "5m").
     */
    public static Duration parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ofSeconds(1);
        }
        value = value.trim()
                     .toLowerCase();
        if (value.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
        }
        if (value.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        if (value.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
        }
        // Default: assume seconds
        return Duration.ofSeconds(Long.parseLong(value));
    }

    /**
     * Configuration loading errors.
     */
    public sealed interface ConfigError extends Cause {
        record InvalidConfig(String reason) implements ConfigError {
            @Override
            public String message() {
                return "Invalid configuration: " + reason;
            }
        }

        static ConfigError invalidConfig(String reason) {
            return new InvalidConfig(reason);
        }
    }
}
