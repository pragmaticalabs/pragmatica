package org.pragmatica.aether.setup;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.config.Environment;
import org.pragmatica.aether.setup.generators.DockerGenerator;
import org.pragmatica.aether.setup.generators.Generator;
import org.pragmatica.aether.setup.generators.KubernetesGenerator;
import org.pragmatica.aether.setup.generators.LocalGenerator;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Aether cluster setup tool.
///
///
/// Usage:
/// ```
/// aether-up [OPTIONS]
///
/// Configuration:
///   -c, --config FILE     Config file (default: ./aether.toml)
///   -e, --env ENV         Environment: local, docker, kubernetes
///
/// Overrides:
///   --nodes N             Override node count
///   --heap SIZE           Override heap size
///   --tls                 Enable TLS
///   --no-tls              Disable TLS
///
/// Output:
///   -o, --output DIR      Output directory (default: ./aether-cluster)
///   --dry-run             Show what would be generated
/// ```
public final class AetherUp {
    private static final String VERSION = "0.7.2";
    private static final List<Generator> GENERATORS = List.of(new LocalGenerator(),
                                                              new DockerGenerator(),
                                                              new KubernetesGenerator());

    @SuppressWarnings("JBCT-RET-01")
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
        var configPath = Path.of(options.getOrDefault("config", "aether.toml"));
        var outputDir = Path.of(options.getOrDefault("output", "aether-cluster"));
        var config = configFor(options, configPath);
        var generator = findGenerator(config);
        if (options.containsKey("dry-run")) {
            printDryRun(config, outputDir);
            return;
        }
        runGeneration(generator, config, outputDir);
    }

    private static AetherConfig configFor(Map<String, String> options, Path configPath) {
        return configPath.toFile()
                         .exists()
               ? configFromFile(options, configPath)
               : configFromDefaults(options);
    }

    private static AetherConfig configFromFile(Map<String, String> options, Path configPath) {
        var overrides = extractOverrides(options);
        var result = ConfigLoader.loadWithOverrides(configPath, overrides);
        result.onFailure(cause -> printErrorAndExit("Error loading configuration:", cause.message()));
        return result.unwrap();
    }

    private static AetherConfig configFromDefaults(Map<String, String> options) {
        var envStr = options.getOrDefault("env", "docker");
        var env = Environment.environment(envStr)
                             .onFailure(cause -> printErrorAndExit("Error:",
                                                                   cause.message()))
                             .unwrap();
        var builder = AetherConfig.builder()
                                  .withEnvironment(env);
        withOverrides(builder, options);
        return builder.build();
    }

    private static void withOverrides(AetherConfig.Builder builder, Map<String, String> options) {
        if (options.containsKey("nodes")) {
            builder.nodes(Number.parseInt(options.get("nodes"))
                                .unwrap());
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
    }

    private static Generator findGenerator(AetherConfig config) {
        return GENERATORS.stream()
                         .filter(g -> g.supports(config))
                         .findFirst()
                         .orElseThrow(() -> noGeneratorError(config));
    }

    private static IllegalStateException noGeneratorError(AetherConfig config) {
        return new IllegalStateException("No generator found for environment: " + config.environment());
    }

    private static void printDryRun(AetherConfig config, Path outputDir) {
        System.out.println("Dry run - would generate artifacts for:");
        System.out.println("  Environment: " + config.environment()
                                                    .displayName());
        System.out.println("  Nodes: " + config.cluster()
                                              .nodes());
        System.out.println("  Heap: " + config.node()
                                             .heap());
        System.out.println("  TLS: " + config.tlsEnabled());
        System.out.println("  Output: " + outputDir);
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static void runGeneration(Generator generator, AetherConfig config, Path outputDir) {
        var result = generator.generate(config, outputDir);
        result.onSuccess(output -> System.out.println(output.instructions()))
              .onFailure(cause -> printErrorAndExit("Error generating artifacts:",
                                                    cause.message()));
    }

    private static void printErrorAndExit(String header, String detail) {
        System.err.println(header);
        System.err.println("  " + detail);
        System.exit(1);
    }

    private static Map<String, String> parseArgs(String[] args) {
        var options = new HashMap<String, String>();
        Arrays.stream(args)
              .reduce("",
                      (prev, arg) -> parseArg(prev, arg, options));
        return options;
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static String parseArg(String prev, String arg, Map<String, String> options) {
        return switch (arg) {
            case "-h", "--help" -> {
                options.put("help", "true");
                yield "";
            }
            case "-v", "--version" -> {
                options.put("version", "true");
                yield "";
            }
            case "-c", "--config" -> "config";
            case "-e", "--env" -> "env";
            case "-o", "--output" -> "output";
            case "--nodes" -> "nodes";
            case "--heap" -> "heap";
            case "--tls" -> {
                options.put("tls", "true");
                yield "";
            }
            case "--no-tls" -> {
                options.put("no-tls", "true");
                yield "";
            }
            case "--dry-run" -> {
                options.put("dry-run", "true");
                yield "";
            }
            default -> {
                if (Verify.Is.notEmpty(prev)) {
                    options.put(prev, arg);
                }
                yield "";
            }
        };
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
