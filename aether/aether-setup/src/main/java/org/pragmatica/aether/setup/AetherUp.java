package org.pragmatica.aether.setup;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.config.Environment;
import org.pragmatica.aether.setup.generators.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aether cluster setup tool.
 *
 * <p>Usage:
 * <pre>
 * aether-up [OPTIONS]
 *
 * Configuration:
 *   -c, --config FILE     Config file (default: ./aether.toml)
 *   -e, --env ENV         Environment: local, docker, kubernetes
 *
 * Overrides:
 *   --nodes N             Override node count
 *   --heap SIZE           Override heap size
 *   --tls                 Enable TLS
 *   --no-tls              Disable TLS
 *
 * Output:
 *   -o, --output DIR      Output directory (default: ./aether-cluster)
 *   --dry-run             Show what would be generated
 * </pre>
 */
public final class AetherUp {
    private static final String VERSION = "0.7.2";
    private static final List<Generator> GENERATORS = List.of(new LocalGenerator(),
                                                              new DockerGenerator(),
                                                              new KubernetesGenerator());

    public static void main(String[] args) {
        var options = parseArgs(args);
        if (options.containsKey("help")) {
            printHelp();
            return;
        }
        if (options.containsKey("version")) {
            System.out.println("aether-up " + VERSION);
            return;
        }
        // Load configuration
        var configPath = Path.of(options.getOrDefault("config", "aether.toml"));
        var outputDir = Path.of(options.getOrDefault("output", "aether-cluster"));
        AetherConfig config;
        if (configPath.toFile()
                      .exists()) {
            var overrides = extractOverrides(options);
            var result = ConfigLoader.loadWithOverrides(configPath, overrides);
            if (result.isFailure()) {
                System.err.println("Error loading configuration:");
                result.onFailure(cause -> System.err.println("  " + cause.message()));
                System.exit(1);
                return;
            }
            config = result.unwrap();
        } else {
            // No config file - use environment defaults
            var envStr = options.getOrDefault("env", "docker");
            var envResult = Environment.environment(envStr)
                                       .onFailure(cause -> System.err.println("Error: " + cause.message()))
                                       .onFailure(_ -> System.exit(1));
            var env = envResult.unwrap();
            var builder = AetherConfig.builder()
                                      .environment(env);
            // Apply overrides
            if (options.containsKey("nodes")) {
                builder.nodes(Integer.parseInt(options.get("nodes")));
            }
            if (options.containsKey("heap")) {
                builder.heap(options.get("heap"));
            }
            if (options.containsKey("tls")) {
                builder.tls(true);
            }
            if (options.containsKey("no-tls")) {
                builder.tls(false);
            }
            config = builder.build();
        }
        // Find appropriate generator
        var generator = GENERATORS.stream()
                                  .filter(g -> g.supports(config))
                                  .findFirst()
                                  .orElseThrow(() -> new IllegalStateException("No generator found for environment: " + config.environment()));
        if (options.containsKey("dry-run")) {
            System.out.println("Dry run - would generate artifacts for:");
            System.out.println("  Environment: " + config.environment()
                                                        .displayName());
            System.out.println("  Nodes: " + config.cluster()
                                                  .nodes());
            System.out.println("  Heap: " + config.node()
                                                 .heap());
            System.out.println("  TLS: " + config.tlsEnabled());
            System.out.println("  Output: " + outputDir);
            return;
        }
        // Generate artifacts
        var result = generator.generate(config, outputDir);
        result.onSuccess(output -> System.out.println(output.instructions()))
              .onFailure(cause -> {
                             System.err.println("Error generating artifacts:");
                             System.err.println("  " + cause.message());
                             System.exit(1);
                         });
    }

    private static Map<String, String> parseArgs(String[] args) {
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            switch (arg) {
                case "-h", "--help" -> options.put("help", "true");
                case "-v", "--version" -> options.put("version", "true");
                case "-c", "--config" -> {
                    if (i + 1 < args.length) options.put("config", args[++ i]);
                }
                case "-e", "--env" -> {
                    if (i + 1 < args.length) options.put("env", args[++ i]);
                }
                case "-o", "--output" -> {
                    if (i + 1 < args.length) options.put("output", args[++ i]);
                }
                case "--nodes" -> {
                    if (i + 1 < args.length) options.put("nodes", args[++ i]);
                }
                case "--heap" -> {
                    if (i + 1 < args.length) options.put("heap", args[++ i]);
                }
                case "--tls" -> options.put("tls", "true");
                case "--no-tls" -> options.put("no-tls", "true");
                case "--dry-run" -> options.put("dry-run", "true");
            }
        }
        return options;
    }

    private static Map<String, String> extractOverrides(Map<String, String> options) {
        var overrides = new HashMap<String, String>();
        if (options.containsKey("env")) {
            overrides.put("environment", options.get("env"));
        }
        if (options.containsKey("nodes")) {
            overrides.put("nodes", options.get("nodes"));
        }
        if (options.containsKey("heap")) {
            overrides.put("heap", options.get("heap"));
        }
        if (options.containsKey("tls")) {
            overrides.put("tls", "true");
        }
        if (options.containsKey("no-tls")) {
            overrides.put("tls", "false");
        }
        return overrides;
    }

    private static void printHelp() {
        System.out.println("""
            aether-up - Aether cluster setup tool

            Usage: aether-up [OPTIONS]

            Configuration:
              -c, --config FILE     Config file (default: ./aether.toml)
              -e, --env ENV         Environment: local, docker, kubernetes
                                    (default: docker)

            Overrides:
              --nodes N             Override node count (3, 5, or 7)
              --heap SIZE           Override heap size (e.g., 512m, 1g)
              --tls                 Enable TLS (auto-generate certs)
              --no-tls              Disable TLS

            Output:
              -o, --output DIR      Output directory (default: ./aether-cluster)
              --dry-run             Show what would be generated

            Other:
              -h, --help            Show this help
              -v, --version         Show version

            Examples:
              # Generate Docker cluster with defaults
              aether-up

              # Generate 7-node Kubernetes cluster
              aether-up -e kubernetes --nodes 7

              # Generate from config file
              aether-up -c myconfig.toml -o /opt/aether

              # Preview without generating
              aether-up --dry-run
            """);
    }
}
