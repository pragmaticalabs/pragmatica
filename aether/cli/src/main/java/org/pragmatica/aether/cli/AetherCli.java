package org.pragmatica.aether.cli;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import static org.pragmatica.lang.Option.empty;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;

/// Aether cluster management CLI.
///
///
/// Supports two modes:
///
///   - Batch mode: Execute commands directly from command line
///   - REPL mode: Interactive shell when no command specified
///
///
///
/// Usage examples:
/// ```
/// # Batch mode
/// aether-cli --connect node1:8080 status
/// aether-cli --connect node1:8080 nodes
/// aether-cli --connect node1:8080 metrics
///
/// # REPL mode
/// aether-cli --connect node1:8080
/// aether> status
/// aether> nodes
/// aether> exit
/// ```
@Command(name = "aether",
mixinStandardHelpOptions = true,
version = "Aether 0.18.0",
description = "Command-line interface for Aether cluster management",
subcommands = {AetherCli.StatusCommand.class,
AetherCli.NodesCommand.class,
AetherCli.SlicesCommand.class,
AetherCli.MetricsCommand.class,
AetherCli.HealthCommand.class,
AetherCli.ScaleCommand.class,
AetherCli.BlueprintCommand.class,
AetherCli.ArtifactCommand.class,
AetherCli.UpdateCommand.class,
AetherCli.InvocationMetricsCommand.class,
AetherCli.ControllerCommand.class,
AetherCli.AlertsCommand.class,
AetherCli.ThresholdsCommand.class,
AetherCli.AspectsCommand.class,
AetherCli.LoggingCommand.class,
AetherCli.ConfigCommand.class,
AetherCli.ScheduledTasksCommand.class,
AetherCli.EventsCommand.class})
@SuppressWarnings("JBCT-RET-01")
public class AetherCli implements Runnable {
    private static final String DEFAULT_ADDRESS = "localhost:8080";

    @CommandLine.Option(names = {"-c", "--connect"},
    description = "Node address to connect to (host:port)")
    private String nodeAddress;

    @CommandLine.Option(names = {"--config"},
    description = "Path to aether.toml config file")
    private Path configPath;

    @CommandLine.Option(names = {"--api-key", "-k"},
    description = "API key for authenticated access")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @SuppressWarnings("JBCT-RET-01")
    public static void main(String[] args) {
        var cli = new AetherCli();
        var cmd = new CommandLine(cli);
        // Pre-parse to extract connection info
        cli.lookupConnection(args);
        // Check if this is REPL mode (no subcommand)
        if (isReplMode(args)) {
            cli.runRepl(cmd);
        } else {
            // Batch mode
            int exitCode = cmd.execute(args);
            System.exit(exitCode);
        }
    }

    private static boolean isReplMode(String[] args) {
        // REPL if no args, or only connection-related options
        if (args.length == 0) {
            return true;
        }
        return Arrays.stream(args)
                     .allMatch(AetherCli::isConnectionOption);
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static boolean isConnectionOption(String arg) {
        return arg.startsWith("-c") || arg.startsWith("--connect") || arg.startsWith("--config") || arg.startsWith("-k") || arg.startsWith("--api-key") || arg.equals("-h") || arg.equals("--help") || arg.equals("-V") || arg.equals("--version");
    }

    @SuppressWarnings("JBCT-UTIL-02")
    private void lookupConnection(String[] args) {
        // Parse args manually to get --connect and --config
        var connectArg = extractConnectArg(args);
        var configArg = extractConfigArg(args);
        // Priority: --connect > config file > default
        connectArg.onPresent(address -> nodeAddress = address)
                  .onEmpty(() -> setAddressFromConfigOrDefault(configArg));
    }

    private void setAddressFromConfigOrDefault(Option<Path> configArg) {
        configArg.filter(Files::exists)
                 .onPresent(this::readConfigFromPath)
                 .onEmpty(() -> nodeAddress = DEFAULT_ADDRESS);
    }

    private void readConfigFromPath(Path path) {
        ConfigLoader.load(path)
                    .onSuccess(this::setAddressFromConfig)
                    .onFailure(this::onConfigLoadFailure);
        configPath = path;
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01"})
    private static Option<String> extractConnectArg(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ((args[i].equals("-c") || args[i].equals("--connect")) && i + 1 < args.length) {
                return some(args[i + 1]);
            } else if (args[i].startsWith("--connect=")) {
                return some(args[i].substring("--connect=".length()));
            }
        }
        return empty();
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01"})
    private static Option<Path> extractConfigArg(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--config") && i + 1 < args.length) {
                return some(Path.of(args[i + 1]));
            } else if (args[i].startsWith("--config=")) {
                return some(Path.of(args[i].substring("--config=".length())));
            }
        }
        return empty();
    }

    private void setAddressFromConfig(AetherConfig config) {
        var port = config.cluster()
                         .ports()
                         .management();
        nodeAddress = "localhost:" + port;
    }

    private void onConfigLoadFailure(Cause cause) {
        System.err.println("Warning: Failed to load config: " + cause.message());
        nodeAddress = DEFAULT_ADDRESS;
    }

    @Override
    public void run() {
        // When no subcommand is specified, show help
        CommandLine.usage(this, System.out);
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01", "JBCT-UTIL-02"})
    private void runRepl(CommandLine cmd) {
        System.out.println("Aether v0.18.0 - Connected to " + nodeAddress);
        System.out.println("Type 'help' for available commands, 'exit' to quit.");
        System.out.println();
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (true) {
                System.out.print("aether> ");
                System.out.flush();
                line = reader.readLine();
                if (isExitCommand(line)) {
                    System.out.println("Goodbye!");
                    break;
                }
                if (line.trim()
                        .isEmpty()) {
                    continue;
                }
                // Parse and execute command
                sendReplCommand(cmd, line.trim());
            }
        } catch (IOException e) {
            System.err.println("Error reading input: " + e.getMessage());
        }
    }

    private static boolean isExitCommand(String line) {
        return option(line).map(String::trim)
                     .filter(trimmed -> !isExitKeyword(trimmed))
                     .isEmpty();
    }

    private static boolean isExitKeyword(String trimmed) {
        return trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit");
    }

    @SuppressWarnings("JBCT-UTIL-02")
    private void sendReplCommand(CommandLine cmd, String input) {
        String[] replArgs = input.split("\\s+");
        if (replArgs.length > 0) {
            // Prepend --connect option
            var fullArgs = buildReplArgs(replArgs);
            cmd.execute(fullArgs);
        }
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private String[] buildReplArgs(String[] replArgs) {
        var args = new ArrayList<String>();
        args.add("--connect");
        args.add(nodeAddress);
        resolveApiKey().onPresent(key -> {
            args.add("--api-key");
            args.add(key);
        });
        for (var arg : replArgs) {
            args.add(arg);
        }
        return args.toArray(String[]::new);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"})
    String fetchFromNode(String path) {
        try{
            var uri = URI.create("http://" + nodeAddress + path);
            var request = buildGetRequest(uri);
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return extractResponseBody(response);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"})
    String postToNode(String path, String body) {
        try{
            var uri = URI.create("http://" + nodeAddress + path);
            var request = buildPostRequest(uri, body);
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return extractResponseBody(response);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01", "JBCT-UTIL-02"})
    String putToNode(String path, byte[] content, String contentType) {
        try{
            var uri = URI.create("http://" + nodeAddress + path);
            var request = buildPutRequest(uri, content, contentType);
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return extractPutResponseBody(response);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    @SuppressWarnings("JBCT-UTIL-01")
    String deleteFromNode(String path) {
        try{
            var uri = URI.create("http://" + nodeAddress + path);
            var request = buildDeleteRequest(uri);
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return extractResponseBody(response);
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private Option<String> resolveApiKey() {
        return option(apiKey).filter(k -> !k.isBlank())
                             .orElse(() -> option(System.getenv("AETHER_API_KEY"))
                                               .filter(k -> !k.isBlank()));
    }

    private void attachApiKey(HttpRequest.Builder builder) {
        resolveApiKey().onPresent(key -> builder.header("X-API-Key", key));
    }

    private HttpRequest buildGetRequest(URI uri) {
        var builder = HttpRequest.newBuilder()
                                 .uri(uri)
                                 .GET();
        attachApiKey(builder);
        return builder.build();
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private HttpRequest buildPostRequest(URI uri, String body) {
        var builder = HttpRequest.newBuilder()
                                 .uri(uri)
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body));
        attachApiKey(builder);
        return builder.build();
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private HttpRequest buildPutRequest(URI uri, byte[] content, String contentType) {
        var builder = HttpRequest.newBuilder()
                                 .uri(uri)
                                 .header("Content-Type", contentType)
                                 .PUT(HttpRequest.BodyPublishers.ofByteArray(content));
        attachApiKey(builder);
        return builder.build();
    }

    private HttpRequest buildDeleteRequest(URI uri) {
        var builder = HttpRequest.newBuilder()
                                 .uri(uri)
                                 .DELETE();
        attachApiKey(builder);
        return builder.build();
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static String extractResponseBody(HttpResponse<String> response) {
        if (response.statusCode() == 200) {
            return response.body();
        }
        if (response.statusCode() == 401) {
            return "{\"error\":\"Authentication required. Use --api-key or set AETHER_API_KEY environment variable.\"}";
        }
        if (response.statusCode() == 403) {
            return "{\"error\":\"Access denied. The provided API key does not have sufficient permissions.\"}";
        }
        return formatErrorResponse(response);
    }

    @SuppressWarnings("JBCT-UTIL-02")
    private static String extractPutResponseBody(HttpResponse<String> response) {
        if (response.statusCode() == 200 || response.statusCode() == 201) {
            return response.body()
                           .isEmpty()
                   ? "{\"status\":\"ok\"}"
                   : response.body();
        }
        return formatErrorResponse(response);
    }

    private static String formatErrorResponse(HttpResponse<String> response) {
        return "{\"error\":\"HTTP " + response.statusCode() + ": " + response.body() + "\"}";
    }

    // ===== Subcommands =====
    @Command(name = "status", description = "Show cluster status")
    static class StatusCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public Integer call() {
            var response = parent.fetchFromNode("/status");
            System.out.println(formatJson(response));
            return 0;
        }
    }

    @Command(name = "nodes", description = "List active nodes")
    static class NodesCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public Integer call() {
            var response = parent.fetchFromNode("/nodes");
            System.out.println(formatJson(response));
            return 0;
        }
    }

    @Command(name = "slices", description = "List deployed slices")
    static class SlicesCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public Integer call() {
            var response = parent.fetchFromNode("/slices");
            System.out.println(formatJson(response));
            return 0;
        }
    }

    @Command(name = "metrics", description = "Show cluster metrics")
    static class MetricsCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public Integer call() {
            var response = parent.fetchFromNode("/metrics");
            System.out.println(formatJson(response));
            return 0;
        }
    }

    @Command(name = "health", description = "Check node health")
    static class HealthCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public Integer call() {
            var response = parent.fetchFromNode("/health");
            System.out.println(formatJson(response));
            return 0;
        }
    }

    @Command(name = "scale", description = "Scale a deployed slice")
    static class ScaleCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Parameters(index = "0", description = "Artifact coordinates (group:artifact:version)")
        private String artifact;

        @CommandLine.Option(names = {"-n", "--instances"}, description = "Target number of instances", required = true)
        private int instances;

        @Override
        public Integer call() {
            var body = "{\"artifact\":\"" + artifact + "\",\"instances\":" + instances + "}";
            var response = parent.postToNode("/scale", body);
            System.out.println(formatJson(response));
            return 0;
        }
    }

    @Command(name = "artifact",
    description = "Artifact repository management",
    subcommands = {ArtifactCommand.DeployArtifactCommand.class,
    ArtifactCommand.PushArtifactCommand.class,
    ArtifactCommand.ListArtifactsCommand.class,
    ArtifactCommand.VersionsCommand.class,
    ArtifactCommand.InfoCommand.class,
    ArtifactCommand.DeleteCommand.class,
    ArtifactCommand.MetricsCommand.class})
    static class ArtifactCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "deploy", description = "Deploy a JAR file to the artifact repository")
        static class DeployArtifactCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ArtifactCommand artifactParent;

            @Parameters(index = "0", description = "Path to the JAR file")
            private Path jarPath;

            @CommandLine.Option(names = {"-g", "--group"}, description = "Group ID", required = true)
            private String groupId;

            @CommandLine.Option(names = {"-a", "--artifact"}, description = "Artifact ID", required = true)
            private String artifactId;

            @CommandLine.Option(names = {"-v", "--version"}, description = "Version", required = true)
            private String version;

            @Override
            @SuppressWarnings("JBCT-SEQ-01")
            public Integer call() {
                try{
                    if (!Files.exists(jarPath)) {
                        System.err.println("File not found: " + jarPath);
                        return 1;
                    }
                    byte[] content = Files.readAllBytes(jarPath);
                    var coordinates = groupId + ":" + artifactId + ":" + version;
                    var repoPath = buildArtifactPath(groupId, artifactId, version);
                    var response = artifactParent.parent.putToNode(repoPath, content, "application/java-archive");
                    return reportDeployResult(response, coordinates, content.length);
                } catch (IOException e) {
                    System.err.println("Error reading file: " + e.getMessage());
                    return 1;
                }
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private Integer reportDeployResult(String response, String coordinates, int size) {
                if (response.startsWith("{\"error\":")) {
                    System.out.println("Failed to deploy: " + response);
                    return 1;
                }
                System.out.println("Deployed " + coordinates);
                System.out.println("  File: " + jarPath);
                System.out.println("  Size: " + size + " bytes");
                return 0;
            }
        }

        @Command(name = "push", description = "Push artifact from local Maven repository to cluster")
        static class PushArtifactCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ArtifactCommand artifactParent;

            @Parameters(index = "0", description = "Artifact coordinates (group:artifact:version)")
            private String coordinates;

            @Override
            @SuppressWarnings("JBCT-SEQ-01")
            public Integer call() {
                var parts = coordinates.split(":");
                if (parts.length != 3) {
                    System.err.println("Invalid coordinates format. Expected: group:artifact:version");
                    return 1;
                }
                var groupId = parts[0];
                var artifactId = parts[1];
                var version = parts[2];
                var localPath = findLocalMavenArtifact(groupId, artifactId, version);
                if (!Files.exists(localPath)) {
                    System.err.println("Artifact not found in local Maven repository: " + localPath);
                    return 1;
                }
                return pushArtifactToCluster(groupId, artifactId, version, localPath);
            }

            private static Path findLocalMavenArtifact(String groupId, String artifactId, String version) {
                var m2Home = System.getProperty("user.home") + "/.m2/repository";
                return Path.of(m2Home,
                               groupId.replace('.', '/'),
                               artifactId,
                               version,
                               artifactId + "-" + version + ".jar");
            }

            @SuppressWarnings({"JBCT-UTIL-02", "JBCT-SEQ-01"})
            private Integer pushArtifactToCluster(String groupId, String artifactId, String version, Path localPath) {
                try{
                    byte[] content = Files.readAllBytes(localPath);
                    var repoPath = buildArtifactPath(groupId, artifactId, version);
                    var response = artifactParent.parent.putToNode(repoPath, content, "application/java-archive");
                    if (response.startsWith("{\"error\":")) {
                        System.out.println("Failed to push: " + response);
                        return 1;
                    }
                    System.out.println("Pushed " + coordinates);
                    System.out.println("  From: " + localPath);
                    System.out.println("  Size: " + content.length + " bytes");
                    return 0;
                } catch (IOException e) {
                    System.err.println("Error reading file: " + e.getMessage());
                    return 1;
                }
            }
        }

        @Command(name = "list", description = "List artifacts in the repository")
        static class ListArtifactsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ArtifactCommand artifactParent;

            @Override
            public Integer call() {
                var response = artifactParent.parent.fetchFromNode("/repository/artifacts");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "versions", description = "List versions of an artifact")
        static class VersionsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ArtifactCommand artifactParent;

            @Parameters(index = "0", description = "Artifact (group:artifact)")
            private String artifact;

            @Override
            public Integer call() {
                var parts = artifact.split(":");
                if (parts.length != 2) {
                    System.err.println("Invalid artifact format. Expected: group:artifact");
                    return 1;
                }
                var path = "/repository/" + parts[0].replace('.', '/') + "/" + parts[1] + "/maven-metadata.xml";
                var response = artifactParent.parent.fetchFromNode(path);
                System.out.println(response);
                return 0;
            }
        }

        @Command(name = "info", description = "Show artifact metadata")
        static class InfoCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ArtifactCommand artifactParent;

            @Parameters(index = "0", description = "Artifact coordinates (group:artifact:version)")
            private String coordinates;

            @Override
            public Integer call() {
                var parts = coordinates.split(":");
                if (parts.length != 3) {
                    System.err.println("Invalid coordinates format. Expected: group:artifact:version");
                    return 1;
                }
                var path = "/repository/info/" + parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2];
                var response = artifactParent.parent.fetchFromNode(path);
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "delete", description = "Delete an artifact from the repository")
        static class DeleteCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ArtifactCommand artifactParent;

            @Parameters(index = "0", description = "Artifact coordinates (group:artifact:version)")
            private String coordinates;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var parts = coordinates.split(":");
                if (parts.length != 3) {
                    System.err.println("Invalid coordinates format. Expected: group:artifact:version");
                    return 1;
                }
                var path = "/repository/" + parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2];
                var response = artifactParent.parent.deleteFromNode(path);
                if (response.startsWith("{\"error\":")) {
                    System.out.println("Failed to delete: " + response);
                    return 1;
                }
                System.out.println("Deleted " + coordinates);
                return 0;
            }
        }

        @Command(name = "metrics", description = "Show artifact storage metrics")
        static class MetricsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ArtifactCommand artifactParent;

            @Override
            public Integer call() {
                var response = artifactParent.parent.fetchFromNode("/artifact-metrics");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        private static String buildArtifactPath(String groupId, String artifactId, String version) {
            return "/repository/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId
                   + "-" + version + ".jar";
        }
    }

    @Command(name = "blueprint",
    description = "Blueprint management",
    subcommands = {BlueprintCommand.ApplyCommand.class,
    BlueprintCommand.ListCommand.class,
    BlueprintCommand.GetCommand.class,
    BlueprintCommand.DeleteCommand.class,
    BlueprintCommand.StatusCommand.class,
    BlueprintCommand.ValidateCommand.class})
    static class BlueprintCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "apply", description = "Apply a blueprint file to the cluster")
        static class ApplyCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Path to the blueprint file (.toml)")
            private Path blueprintPath;

            @Override
            @SuppressWarnings({"JBCT-UTIL-02", "JBCT-SEQ-01"})
            public Integer call() {
                try{
                    if (!Files.exists(blueprintPath)) {
                        System.err.println("Blueprint file not found: " + blueprintPath);
                        return 1;
                    }
                    var content = Files.readString(blueprintPath);
                    var response = blueprintParent.parent.postToNode("/api/blueprint", content);
                    if (response.contains("\"error\":")) {
                        System.out.println("Failed to apply blueprint: " + response);
                        return 1;
                    }
                    System.out.println(formatJson(response));
                    return 0;
                } catch (IOException e) {
                    System.err.println("Error reading blueprint file: " + e.getMessage());
                    return 1;
                }
            }
        }

        @Command(name = "list", description = "List all deployed blueprints")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BlueprintCommand blueprintParent;

            @CommandLine.Option(names = {"--format"}, description = "Output format (table|json)", defaultValue = "table")
            private String format;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var response = blueprintParent.parent.fetchFromNode("/api/blueprints");
                if (response.contains("\"error\":")) {
                    System.out.println("Failed to list blueprints: " + response);
                    return 1;
                }
                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(formatJson(response));
                } else {
                    printBlueprintListTable(response);
                }
                return 0;
            }

            @SuppressWarnings({"JBCT-PAT-01", "JBCT-UTIL-02"})
            private void printBlueprintListTable(String json) {
                // Simple table output - parse blueprints array
                System.out.println("ID                                     SLICES");
                System.out.println("\u2500".repeat(60));
                // Extract blueprints from JSON (simple parsing)
                var blueprintsStart = json.indexOf("\"blueprints\":");
                if (blueprintsStart == - 1) {
                    System.out.println("No blueprints found");
                    return;
                }
                var arrayStart = json.indexOf('[', blueprintsStart);
                var arrayEnd = json.lastIndexOf(']');
                if (arrayStart == - 1 || arrayEnd == - 1 || arrayEnd <= arrayStart) {
                    System.out.println("No blueprints found");
                    return;
                }
                var blueprintsArray = json.substring(arrayStart + 1, arrayEnd);
                if (blueprintsArray.trim()
                                   .isEmpty()) {
                    System.out.println("No blueprints found");
                    return;
                }
                // Parse each blueprint object
                extractJsonObjects(blueprintsArray).forEach(this::printBlueprintRow);
            }

            private void printBlueprintRow(String json) {
                var id = extractJsonString(json, "id");
                var sliceCount = extractJsonNumber(json, "sliceCount");
                System.out.printf("%-40s %s%n", id, sliceCount);
            }
        }

        @Command(name = "get", description = "Get blueprint details")
        static class GetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Blueprint ID (group:artifact:version)")
            private String blueprintId;

            @CommandLine.Option(names = {"--format"}, description = "Output format (table|json)", defaultValue = "table")
            private String format;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var response = blueprintParent.parent.fetchFromNode("/api/blueprint/" + blueprintId);
                if (response.contains("\"error\":")) {
                    System.out.println("Failed to get blueprint: " + response);
                    return 1;
                }
                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(formatJson(response));
                } else {
                    printBlueprintDetailTable(response);
                }
                return 0;
            }

            private void printBlueprintDetailTable(String json) {
                var id = extractJsonString(json, "id");
                System.out.println("Blueprint: " + id);
                System.out.println();
                System.out.println("SLICES:");
                System.out.println("\u2500".repeat(80));
                System.out.printf("%-50s %10s %12s%n", "ARTIFACT", "INSTANCES", "TYPE");
                System.out.println("\u2500".repeat(80));
                // Parse slices array
                printSlicesFromJson(json);
            }

            private static void printSlicesFromJson(String json) {
                var slicesStart = json.indexOf("\"slices\":");
                if (slicesStart != - 1) {
                    var arrayStart = json.indexOf('[', slicesStart);
                    var arrayEnd = findMatchingBracket(json, arrayStart);
                    if (arrayStart != - 1 && arrayEnd != - 1) {
                        var slicesArray = json.substring(arrayStart + 1, arrayEnd);
                        extractJsonObjects(slicesArray).forEach(GetCommand::printSliceRow);
                    }
                }
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private static void printSliceRow(String json) {
                var artifact = extractJsonString(json, "artifact");
                var instances = extractJsonNumber(json, "instances");
                var isDep = json.contains("\"isDependency\":true");
                var type = isDep
                           ? "dependency"
                           : "primary";
                System.out.printf("%-50s %10s %12s%n", artifact, instances, type);
            }
        }

        @Command(name = "delete", description = "Delete a blueprint from the cluster")
        static class DeleteCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Blueprint ID (group:artifact:version)")
            private String blueprintId;

            @CommandLine.Option(names = {"--force", "-f"}, description = "Skip confirmation prompt")
            private boolean force;

            @Override
            @SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
            public Integer call() {
                if (!force) {
                    var confirmed = confirmDeletion(blueprintId);
                    if (!confirmed) {
                        return 0;
                    }
                }
                var response = blueprintParent.parent.deleteFromNode("/api/blueprint/" + blueprintId);
                if (response.contains("\"error\":")) {
                    System.out.println("Failed to delete blueprint: " + response);
                    return 1;
                }
                System.out.println("Deleted blueprint: " + blueprintId);
                return 0;
            }

            @SuppressWarnings("JBCT-SEQ-01")
            private static boolean confirmDeletion(String id) {
                System.out.print("Are you sure you want to delete blueprint '" + id + "'? (y/N) ");
                try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
                    return option(reader.readLine()).map(String::trim)
                                 .filter(s -> s.equalsIgnoreCase("y"))
                                 .isPresent();
                } catch (IOException e) {
                    System.err.println("Error reading input: " + e.getMessage());
                    return false;
                }
            }
        }

        @Command(name = "status", description = "Show deployment status of a blueprint")
        static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Blueprint ID (group:artifact:version)")
            private String blueprintId;

            @CommandLine.Option(names = {"--format"}, description = "Output format (table|json)", defaultValue = "table")
            private String format;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var response = blueprintParent.parent.fetchFromNode("/api/blueprint/" + blueprintId + "/status");
                if (response.contains("\"error\":")) {
                    System.out.println("Failed to get blueprint status: " + response);
                    return 1;
                }
                if ("json".equalsIgnoreCase(format)) {
                    System.out.println(formatJson(response));
                } else {
                    printBlueprintStatusTable(response);
                }
                return 0;
            }

            private void printBlueprintStatusTable(String json) {
                var id = extractJsonString(json, "id");
                var overallStatus = extractJsonString(json, "overallStatus");
                System.out.println("Blueprint: " + id);
                System.out.println("Status: " + overallStatus);
                System.out.println();
                System.out.println("SLICE STATUS:");
                System.out.println("\u2500".repeat(90));
                System.out.printf("%-50s %8s %8s %12s%n", "ARTIFACT", "TARGET", "ACTIVE", "STATUS");
                System.out.println("\u2500".repeat(90));
                printStatusSlicesFromJson(json);
            }

            private static void printStatusSlicesFromJson(String json) {
                var slicesStart = json.indexOf("\"slices\":");
                if (slicesStart != - 1) {
                    var arrayStart = json.indexOf('[', slicesStart);
                    var arrayEnd = findMatchingBracket(json, arrayStart);
                    if (arrayStart != - 1 && arrayEnd != - 1) {
                        var slicesArray = json.substring(arrayStart + 1, arrayEnd);
                        extractJsonObjects(slicesArray).forEach(StatusCommand::printStatusSliceRow);
                    }
                }
            }

            private static void printStatusSliceRow(String json) {
                var artifact = extractJsonString(json, "artifact");
                var target = extractJsonNumber(json, "targetInstances");
                var active = extractJsonNumber(json, "activeInstances");
                var status = extractJsonString(json, "status");
                System.out.printf("%-50s %8s %8s %12s%n", artifact, target, active, status);
            }
        }

        @Command(name = "validate", description = "Validate a blueprint file without deploying")
        static class ValidateCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Path to the blueprint file (.toml)")
            private Path blueprintPath;

            @Override
            @SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
            public Integer call() {
                try{
                    if (!Files.exists(blueprintPath)) {
                        System.err.println("Blueprint file not found: " + blueprintPath);
                        return 1;
                    }
                    var content = Files.readString(blueprintPath);
                    var response = blueprintParent.parent.postToNode("/api/blueprint/validate", content);
                    if (response.contains("\"valid\":false")) {
                        return reportValidationFailure(response);
                    }
                    return reportValidationSuccess(response);
                } catch (IOException e) {
                    System.err.println("Error reading blueprint file: " + e.getMessage());
                    return 1;
                }
            }

            @SuppressWarnings({"JBCT-UTIL-02", "JBCT-SEQ-01"})
            private static Integer reportValidationFailure(String response) {
                System.out.println("Validation FAILED");
                var errors = extractJsonStringArray(response, "errors");
                if (!errors.isEmpty()) {
                    System.out.println("Errors:");
                    errors.forEach(error -> System.out.println("  - " + error));
                }
                return 1;
            }

            private static Integer reportValidationSuccess(String response) {
                var id = extractJsonString(response, "id");
                var sliceCount = extractJsonNumber(response, "sliceCount");
                System.out.println("Validation PASSED");
                System.out.println("  Blueprint ID: " + id);
                System.out.println("  Slices: " + sliceCount);
                return 0;
            }

            @SuppressWarnings("JBCT-PAT-01")
            private static List<String> extractJsonStringArray(String json, String key) {
                var result = new ArrayList<String>();
                var keyPattern = "\"" + key + "\":";
                var start = json.indexOf(keyPattern);
                if (start == - 1) return result;
                var arrayStart = json.indexOf('[', start);
                var arrayEnd = findMatchingBracket(json, arrayStart);
                if (arrayStart == - 1 || arrayEnd == - 1) return result;
                var arrayContent = json.substring(arrayStart + 1, arrayEnd);
                parseStringElements(arrayContent, result);
                return result;
            }

            @SuppressWarnings("JBCT-PAT-01")
            private static void parseStringElements(String arrayContent, List<String> result) {
                var inString = false;
                var stringStart = - 1;
                for (int i = 0; i < arrayContent.length(); i++) {
                    var c = arrayContent.charAt(i);
                    if (c == '"' && (i == 0 || arrayContent.charAt(i - 1) != '\\')) {
                        if (!inString) {
                            inString = true;
                            stringStart = i + 1;
                        } else {
                            result.add(arrayContent.substring(stringStart, i));
                            inString = false;
                        }
                    }
                }
            }
        }

        // Helper methods for JSON parsing
        private static String extractJsonString(String json, String key) {
            var pattern = "\"" + key + "\":\"";
            var start = json.indexOf(pattern);
            if (start == - 1) return "";
            start += pattern.length();
            var end = json.indexOf("\"", start);
            if (end == - 1) return "";
            return json.substring(start, end);
        }

        @SuppressWarnings("JBCT-PAT-01")
        private static String extractJsonNumber(String json, String key) {
            var pattern = "\"" + key + "\":";
            var start = json.indexOf(pattern);
            if (start == - 1) return "0";
            start += pattern.length();
            var end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
                end++;
            }
            if (end == start) return "0";
            return json.substring(start, end);
        }

        @SuppressWarnings("JBCT-PAT-01")
        private static int findMatchingBracket(String json, int openIndex) {
            if (openIndex == - 1 || openIndex >= json.length()) return - 1;
            var openChar = json.charAt(openIndex);
            var closeChar = openChar == '['
                            ? ']'
                            : '}';
            var depth = 1;
            var inString = false;
            for (int i = openIndex + 1; i < json.length(); i++) {
                var c = json.charAt(i);
                if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                    inString = !inString;
                } else if (!inString) {
                    if (c == openChar) depth++;else if (c == closeChar) {
                        depth--;
                        if (depth == 0) return i;
                    }
                }
            }
            return - 1;
        }

        @SuppressWarnings("JBCT-PAT-01")
        private static List<String> extractJsonObjects(String arrayContent) {
            var objects = new ArrayList<String>();
            var depth = 0;
            var start = 0;
            for (int i = 0; i < arrayContent.length(); i++) {
                var c = arrayContent.charAt(i);
                if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        objects.add(arrayContent.substring(start, i + 1));
                    }
                }
            }
            return objects;
        }
    }

    @Command(name = "update",
    description = "Rolling update management",
    subcommands = {UpdateCommand.StartCommand.class,
    UpdateCommand.StatusCommand.class,
    UpdateCommand.ListCommand.class,
    UpdateCommand.RoutingCommand.class,
    UpdateCommand.ApproveCommand.class,
    UpdateCommand.CompleteCommand.class,
    UpdateCommand.RollbackCommand.class,
    UpdateCommand.HealthCommand.class})
    static class UpdateCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "start", description = "Start a rolling update")
        static class StartCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private UpdateCommand updateParent;

            @Parameters(index = "0", description = "Artifact base (group:artifact)")
            private String artifactBase;

            @Parameters(index = "1", description = "New version to deploy")
            private String version;

            @CommandLine.Option(names = {"-n", "--instances"}, description = "Number of new version instances", defaultValue = "1")
            private int instances;

            @CommandLine.Option(names = {"--error-rate"}, description = "Max error rate threshold (0.0-1.0)", defaultValue = "0.01")
            private double errorRate;

            @CommandLine.Option(names = {"--latency"}, description = "Max latency threshold in ms", defaultValue = "500")
            private long latencyMs;

            @CommandLine.Option(names = {"--manual-approval"}, description = "Require manual approval for routing changes")
            private boolean manualApproval;

            @CommandLine.Option(names = {"--cleanup"}, description = "Cleanup policy: IMMEDIATE, GRACE_PERIOD, MANUAL", defaultValue = "GRACE_PERIOD")
            private String cleanupPolicy;

            @Override
            public Integer call() {
                var body = buildUpdateStartBody();
                var response = updateParent.parent.postToNode("/rolling-update/start", body);
                System.out.println(formatJson(response));
                return 0;
            }

            private String buildUpdateStartBody() {
                return "{\"artifactBase\":\"" + artifactBase + "\"," + "\"version\":\"" + version + "\","
                       + "\"instances\":" + instances + "," + "\"maxErrorRate\":" + errorRate + ","
                       + "\"maxLatencyMs\":" + latencyMs + "," + "\"requireManualApproval\":" + manualApproval + ","
                       + "\"cleanupPolicy\":\"" + cleanupPolicy + "\"}";
            }
        }

        @Command(name = "status", description = "Get rolling update status")
        static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private UpdateCommand updateParent;

            @Parameters(index = "0", description = "Update ID")
            private String updateId;

            @Override
            public Integer call() {
                var response = updateParent.parent.fetchFromNode("/rolling-update/" + updateId);
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "list", description = "List active rolling updates")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private UpdateCommand updateParent;

            @Override
            public Integer call() {
                var response = updateParent.parent.fetchFromNode("/rolling-updates");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "routing", description = "Adjust traffic routing between versions")
        static class RoutingCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private UpdateCommand updateParent;

            @Parameters(index = "0", description = "Update ID")
            private String updateId;

            @CommandLine.Option(names = {"-r", "--ratio"}, description = "Traffic ratio new:old (e.g., 1:3)", required = true)
            private String ratio;

            @Override
            public Integer call() {
                var body = "{\"routing\":\"" + ratio + "\"}";
                var response = updateParent.parent.postToNode("/rolling-update/" + updateId + "/routing", body);
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "approve", description = "Manually approve current routing configuration")
        static class ApproveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private UpdateCommand updateParent;

            @Parameters(index = "0", description = "Update ID")
            private String updateId;

            @Override
            public Integer call() {
                var response = updateParent.parent.postToNode("/rolling-update/" + updateId + "/approve", "{}");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "complete", description = "Complete rolling update (all traffic to new version)")
        static class CompleteCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private UpdateCommand updateParent;

            @Parameters(index = "0", description = "Update ID")
            private String updateId;

            @Override
            public Integer call() {
                var response = updateParent.parent.postToNode("/rolling-update/" + updateId + "/complete", "{}");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "rollback", description = "Rollback to old version")
        static class RollbackCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private UpdateCommand updateParent;

            @Parameters(index = "0", description = "Update ID")
            private String updateId;

            @Override
            public Integer call() {
                var response = updateParent.parent.postToNode("/rolling-update/" + updateId + "/rollback", "{}");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "health", description = "Show version health metrics")
        static class HealthCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private UpdateCommand updateParent;

            @Parameters(index = "0", description = "Update ID")
            private String updateId;

            @Override
            public Integer call() {
                var response = updateParent.parent.fetchFromNode("/rolling-update/" + updateId + "/health");
                System.out.println(formatJson(response));
                return 0;
            }
        }
    }

    // ===== Invocation Metrics Commands =====
    @Command(name = "invocation-metrics",
    description = "Invocation metrics management",
    subcommands = {InvocationMetricsCommand.ListCommand.class,
    InvocationMetricsCommand.SlowCommand.class,
    InvocationMetricsCommand.StrategyCommand.class})
    static class InvocationMetricsCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public void run() {
            // Default: show all metrics
            var response = parent.fetchFromNode("/invocation-metrics");
            System.out.println(formatJson(response));
        }

        @Command(name = "list", description = "List all invocation metrics")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private InvocationMetricsCommand metricsParent;

            @Override
            public Integer call() {
                var response = metricsParent.parent.fetchFromNode("/invocation-metrics");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "slow", description = "Show slow invocations")
        static class SlowCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private InvocationMetricsCommand metricsParent;

            @Override
            public Integer call() {
                var response = metricsParent.parent.fetchFromNode("/invocation-metrics/slow");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "strategy", description = "Show or set threshold strategy")
        static class StrategyCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private InvocationMetricsCommand metricsParent;

            @Parameters(index = "0", description = "Strategy type: fixed, adaptive", arity = "0..1")
            private String type;

            @Parameters(index = "1", description = "First parameter (thresholdMs for fixed, minMs for adaptive)", arity = "0..1")
            private Long param1;

            @Parameters(index = "2", description = "Second parameter (maxMs for adaptive)", arity = "0..1")
            private Long param2;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                option(type).onPresent(this::setStrategy)
                      .onEmpty(this::showCurrentStrategy);
                return 0;
            }

            private void showCurrentStrategy() {
                var response = metricsParent.parent.fetchFromNode("/invocation-metrics/strategy");
                System.out.println(formatJson(response));
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private void setStrategy(String strategyType) {
                String body;
                switch (strategyType.toLowerCase()) {
                    case "fixed" -> body = buildFixedStrategyBody();
                    case "adaptive" -> body = buildAdaptiveStrategyBody();
                    default -> {
                        System.err.println("Unknown strategy type: " + strategyType);
                        System.err.println("Supported: fixed, adaptive");
                        return;
                    }
                }
                var response = metricsParent.parent.postToNode("/invocation-metrics/strategy", body);
                System.out.println(formatJson(response));
            }

            private String buildFixedStrategyBody() {
                var thresholdMs = option(param1).or(100L);
                return "{\"type\":\"fixed\",\"thresholdMs\":" + thresholdMs + "}";
            }

            private String buildAdaptiveStrategyBody() {
                var minMs = option(param1).or(10L);
                var maxMs = option(param2).or(1000L);
                return "{\"type\":\"adaptive\",\"minMs\":" + minMs + ",\"maxMs\":" + maxMs + "}";
            }
        }
    }

    // ===== Controller Commands =====
    @Command(name = "controller",
    description = "Controller configuration and status",
    subcommands = {ControllerCommand.ConfigCommand.class,
    ControllerCommand.StatusCommand.class,
    ControllerCommand.EvaluateCommand.class})
    static class ControllerCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "config", description = "Show or update controller configuration")
        static class ConfigCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ControllerCommand controllerParent;

            @CommandLine.Option(names = {"--cpu-up"}, description = "CPU scale-up threshold")
            private Double cpuScaleUp;

            @CommandLine.Option(names = {"--cpu-down"}, description = "CPU scale-down threshold")
            private Double cpuScaleDown;

            @CommandLine.Option(names = {"--call-rate"}, description = "Call rate scale-up threshold")
            private Double callRate;

            @CommandLine.Option(names = {"--interval"}, description = "Evaluation interval in ms")
            private Long intervalMs;

            @Override
            @SuppressWarnings({"JBCT-UTIL-02", "JBCT-SEQ-01"})
            public Integer call() {
                if (hasConfigUpdate()) {
                    var body = buildConfigUpdateBody();
                    var response = controllerParent.parent.postToNode("/controller/config", body);
                    System.out.println(formatJson(response));
                } else {
                    // Show current config
                    var response = controllerParent.parent.fetchFromNode("/controller/config");
                    System.out.println(formatJson(response));
                }
                return 0;
            }

            private boolean hasConfigUpdate() {
                return option(cpuScaleUp).isPresent() || option(cpuScaleDown).isPresent() || option(callRate).isPresent() || option(intervalMs).isPresent();
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private String buildConfigUpdateBody() {
                var fields = new ArrayList<String>();
                option(cpuScaleUp).onPresent(v -> fields.add("\"cpuScaleUpThreshold\":" + v));
                option(cpuScaleDown).onPresent(v -> fields.add("\"cpuScaleDownThreshold\":" + v));
                option(callRate).onPresent(v -> fields.add("\"callRateScaleUpThreshold\":" + v));
                option(intervalMs).onPresent(v -> fields.add("\"evaluationIntervalMs\":" + v));
                return "{" + String.join(",", fields) + "}";
            }
        }

        @Command(name = "status", description = "Show controller status")
        static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ControllerCommand controllerParent;

            @Override
            public Integer call() {
                var response = controllerParent.parent.fetchFromNode("/controller/status");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "evaluate", description = "Force controller evaluation")
        static class EvaluateCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ControllerCommand controllerParent;

            @Override
            public Integer call() {
                var response = controllerParent.parent.postToNode("/controller/evaluate", "{}");
                System.out.println(formatJson(response));
                return 0;
            }
        }
    }

    // ===== Alerts Commands =====
    @Command(name = "alerts",
    description = "Alert management",
    subcommands = {AlertsCommand.ListCommand.class,
    AlertsCommand.ActiveCommand.class,
    AlertsCommand.HistoryCommand.class,
    AlertsCommand.ClearCommand.class})
    static class AlertsCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public void run() {
            // Default: show all alerts
            var response = parent.fetchFromNode("/alerts");
            System.out.println(formatJson(response));
        }

        @Command(name = "list", description = "List all alerts")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AlertsCommand alertsParent;

            @Override
            public Integer call() {
                var response = alertsParent.parent.fetchFromNode("/alerts");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "active", description = "Show active alerts only")
        static class ActiveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AlertsCommand alertsParent;

            @Override
            public Integer call() {
                var response = alertsParent.parent.fetchFromNode("/alerts/active");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "history", description = "Show alert history")
        static class HistoryCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AlertsCommand alertsParent;

            @Override
            public Integer call() {
                var response = alertsParent.parent.fetchFromNode("/alerts/history");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "clear", description = "Clear all active alerts")
        static class ClearCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AlertsCommand alertsParent;

            @Override
            public Integer call() {
                var response = alertsParent.parent.postToNode("/alerts/clear", "{}");
                System.out.println(formatJson(response));
                return 0;
            }
        }
    }

    // ===== Thresholds Commands =====
    @Command(name = "thresholds",
    description = "Alert threshold management",
    subcommands = {ThresholdsCommand.ListCommand.class,
    ThresholdsCommand.SetCommand.class,
    ThresholdsCommand.RemoveCommand.class})
    static class ThresholdsCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public void run() {
            // Default: show all thresholds
            var response = parent.fetchFromNode("/thresholds");
            System.out.println(formatJson(response));
        }

        @Command(name = "list", description = "List all thresholds")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ThresholdsCommand thresholdsParent;

            @Override
            public Integer call() {
                var response = thresholdsParent.parent.fetchFromNode("/thresholds");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "set", description = "Set a threshold")
        static class SetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ThresholdsCommand thresholdsParent;

            @Parameters(index = "0", description = "Metric name")
            private String metric;

            @CommandLine.Option(names = {"-w", "--warning"}, description = "Warning threshold", required = true)
            private double warning;

            @CommandLine.Option(names = {"-c", "--critical"}, description = "Critical threshold", required = true)
            private double critical;

            @Override
            public Integer call() {
                var body = "{\"metric\":\"" + metric + "\",\"warning\":" + warning + ",\"critical\":" + critical + "}";
                var response = thresholdsParent.parent.postToNode("/thresholds", body);
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "remove", description = "Remove a threshold")
        static class RemoveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ThresholdsCommand thresholdsParent;

            @Parameters(index = "0", description = "Metric name")
            private String metric;

            @Override
            public Integer call() {
                var response = thresholdsParent.parent.deleteFromNode("/thresholds/" + metric);
                System.out.println(formatJson(response));
                return 0;
            }
        }
    }

    // ===== Aspects Commands =====
    @Command(name = "aspects",
    description = "Dynamic aspect management",
    subcommands = {AspectsCommand.ListCommand.class,
    AspectsCommand.SetCommand.class,
    AspectsCommand.RemoveCommand.class})
    static class AspectsCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public void run() {
            // Default: show all aspects as table
            var response = parent.fetchFromNode("/api/aspects");
            printAspectsTable(response);
        }

        @Command(name = "list", description = "List all configured aspects")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AspectsCommand aspectsParent;

            @Override
            public Integer call() {
                var response = aspectsParent.parent.fetchFromNode("/api/aspects");
                printAspectsTable(response);
                return 0;
            }
        }

        @Command(name = "set", description = "Set aspect mode on a method")
        static class SetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AspectsCommand aspectsParent;

            @Parameters(index = "0", description = "Target (artifact#method)")
            private String target;

            @Parameters(index = "1", description = "Mode (NONE, LOG, METRICS, LOG_AND_METRICS)")
            private String mode;

            @Override
            public Integer call() {
                var hashIndex = target.indexOf('#');
                if (hashIndex == - 1) {
                    System.err.println("Invalid target format. Expected: artifact#method");
                    return 1;
                }
                var artifact = target.substring(0, hashIndex);
                var method = target.substring(hashIndex + 1);
                var normalizedMode = mode.toUpperCase();
                var body = buildAspectSetBody(artifact, method, normalizedMode);
                var response = aspectsParent.parent.postToNode("/api/aspects", body);
                System.out.println(formatJson(response));
                return 0;
            }

            private static String buildAspectSetBody(String artifact, String method, String normalizedMode) {
                return "{\"artifact\":\"" + artifact + "\",\"method\":\"" + method + "\",\"mode\":\"" + normalizedMode
                       + "\"}";
            }
        }

        @Command(name = "remove", description = "Remove aspect configuration")
        static class RemoveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AspectsCommand aspectsParent;

            @Parameters(index = "0", description = "Target (artifact#method)")
            private String target;

            @Override
            public Integer call() {
                var hashIndex = target.indexOf('#');
                if (hashIndex == - 1) {
                    System.err.println("Invalid target format. Expected: artifact#method");
                    return 1;
                }
                var artifact = target.substring(0, hashIndex);
                var method = target.substring(hashIndex + 1);
                var response = aspectsParent.parent.deleteFromNode("/api/aspects/" + artifact + "/" + method);
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @SuppressWarnings({"JBCT-UTIL-02", "JBCT-SEQ-01"})
        private static void printAspectsTable(String json) {
            // Parse JSON map: {"key::mode", ...}
            var jsonOpt = option(json).map(String::trim)
                                .filter(s -> !s.equals("{}"))
                                .filter(s -> !s.contains("\"error\":"));
            if (jsonOpt.isEmpty()) {
                printAspectsError(json);
                return;
            }
            System.out.printf("%-40s %-20s %s%n", "ARTIFACT", "METHOD", "MODE");
            System.out.println("\u2500".repeat(75));
            // Simple JSON map parsing: {"artifactBase/method":"MODE", ...}
            var content = extractMapContent(jsonOpt.or(""));
            if (content.trim()
                       .isEmpty()) {
                System.out.println("No active aspects");
                return;
            }
            parseAndPrintAspectEntries(content);
        }

        @SuppressWarnings("JBCT-UTIL-02")
        private static void printAspectsError(String json) {
            option(json).filter(s -> s.contains("\"error\":"))
                  .onPresent(s -> System.out.println(formatJson(s)))
                  .onEmpty(() -> System.out.println("No active aspects"));
        }

        private static String extractMapContent(String json) {
            var content = json.trim();
            if (content.startsWith("{")) content = content.substring(1);
            if (content.endsWith("}")) content = content.substring(0, content.length() - 1);
            return content;
        }

        @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01"})
        private static void parseAndPrintAspectEntries(String content) {
            // Parse key-value pairs
            var inString = false;
            var tokenStart = - 1;
            var key = "";
            var expectValue = false;
            for (int i = 0; i < content.length(); i++) {
                var c = content.charAt(i);
                if (c == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                    if (!inString) {
                        inString = true;
                        tokenStart = i + 1;
                    } else {
                        inString = false;
                        var token = content.substring(tokenStart, i);
                        if (!expectValue) {
                            key = token;
                            expectValue = true;
                        } else {
                            printAspectEntry(key, token);
                            expectValue = false;
                        }
                    }
                }
            }
        }

        private static void printAspectEntry(String key, String mode) {
            var slashIndex = key.indexOf('/');
            var artifact = slashIndex != - 1
                           ? key.substring(0, slashIndex)
                           : key;
            var method = slashIndex != - 1
                         ? key.substring(slashIndex + 1)
                         : "";
            System.out.printf("%-40s %-20s %s%n", artifact, method, mode);
        }
    }

    // ===== Logging Commands =====
    @Command(name = "logging",
    description = "Runtime log level management",
    subcommands = {LoggingCommand.ListCommand.class,
    LoggingCommand.SetCommand.class,
    LoggingCommand.ResetCommand.class})
    static class LoggingCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public void run() {
            var response = parent.fetchFromNode("/api/logging/levels");
            System.out.println(formatJson(response));
        }

        @Command(name = "list", description = "List runtime-configured log levels")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private LoggingCommand loggingParent;

            @Override
            public Integer call() {
                var response = loggingParent.parent.fetchFromNode("/api/logging/levels");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "set", description = "Set log level for a logger")
        static class SetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private LoggingCommand loggingParent;

            @Parameters(index = "0", description = "Logger name (e.g., org.pragmatica.aether)")
            private String logger;

            @Parameters(index = "1", description = "Log level (TRACE, DEBUG, INFO, WARN, ERROR)")
            private String level;

            @Override
            public Integer call() {
                var body = "{\"logger\":\"" + logger + "\",\"level\":\"" + level.toUpperCase() + "\"}";
                var response = loggingParent.parent.postToNode("/api/logging/levels", body);
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "reset", description = "Reset logger to config default")
        static class ResetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private LoggingCommand loggingParent;

            @Parameters(index = "0", description = "Logger name")
            private String logger;

            @Override
            public Integer call() {
                var response = loggingParent.parent.deleteFromNode("/api/logging/levels/" + logger);
                System.out.println(formatJson(response));
                return 0;
            }
        }
    }

    // ===== Config Commands =====
    @Command(name = "config",
    description = "Dynamic configuration management",
    subcommands = {ConfigCommand.ListCommand.class,
    ConfigCommand.OverridesCommand.class,
    ConfigCommand.SetCommand.class,
    ConfigCommand.RemoveCommand.class})
    static class ConfigCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public void run() {
            // Default: show all config
            var response = parent.fetchFromNode("/api/config");
            System.out.println(formatJson(response));
        }

        @Command(name = "list", description = "Show all configuration (base + overrides)")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ConfigCommand configParent;

            @Override
            public Integer call() {
                var response = configParent.parent.fetchFromNode("/api/config");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "overrides", description = "Show only dynamic overrides from KV store")
        static class OverridesCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ConfigCommand configParent;

            @Override
            public Integer call() {
                var response = configParent.parent.fetchFromNode("/api/config/overrides");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "set", description = "Set a configuration override")
        static class SetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ConfigCommand configParent;

            @Parameters(index = "0", description = "Configuration key (dot.notation)")
            private String key;

            @Parameters(index = "1", description = "Configuration value")
            private String value;

            @CommandLine.Option(names = {"--node"}, description = "Target node ID (omit for cluster-wide)")
            private String nodeId;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var body = buildConfigSetBody();
                var response = configParent.parent.postToNode("/api/config", body);
                System.out.println(formatJson(response));
                return 0;
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private String buildConfigSetBody() {
                var sb = new StringBuilder("{\"key\":\"").append(key)
                                                         .append("\",\"value\":\"")
                                                         .append(value)
                                                         .append("\"");
                option(nodeId).onPresent(id -> sb.append(",\"nodeId\":\"")
                                                 .append(id)
                                                 .append("\""));
                sb.append("}");
                return sb.toString();
            }
        }

        @Command(name = "remove", description = "Remove a configuration override")
        static class RemoveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ConfigCommand configParent;

            @Parameters(index = "0", description = "Configuration key (dot.notation)")
            private String key;

            @CommandLine.Option(names = {"--node"}, description = "Target node ID (omit for cluster-wide)")
            private String nodeId;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var response = fetchRemoveResponse();
                System.out.println(formatJson(response));
                return 0;
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private String fetchRemoveResponse() {
                var path = option(nodeId).map(id -> "/api/config/node/" + id + "/" + key)
                                 .or("/api/config/" + key);
                return configParent.parent.deleteFromNode(path);
            }
        }
    }

    // ===== Scheduled Tasks Commands =====
    @Command(name = "scheduled-tasks",
    description = "Scheduled task management",
    subcommands = {ScheduledTasksCommand.ListCommand.class,
    ScheduledTasksCommand.GetCommand.class})
    static class ScheduledTasksCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public void run() {
            // Default: list all scheduled tasks
            var response = parent.fetchFromNode("/api/scheduled-tasks");
            System.out.println(formatJson(response));
        }

        @Command(name = "list", description = "List all scheduled tasks")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ScheduledTasksCommand tasksParent;

            @Override
            public Integer call() {
                var response = tasksParent.parent.fetchFromNode("/api/scheduled-tasks");
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "get", description = "Get scheduled tasks by config section")
        static class GetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ScheduledTasksCommand tasksParent;

            @Parameters(index = "0", description = "Config section name")
            private String configSection;

            @Override
            public Integer call() {
                var response = tasksParent.parent.fetchFromNode("/api/scheduled-tasks/" + configSection);
                System.out.println(formatJson(response));
                return 0;
            }
        }
    }

    // ===== Events Command =====
    @Command(name = "events", description = "Show cluster events")
    static class EventsCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @CommandLine.Option(names = {"--since"}, description = "Show events since ISO-8601 timestamp (e.g. 2024-01-15T10:30:00Z)")
        private String since;

        @Override
        public Integer call() {
            var path = buildEventsPath();
            var response = parent.fetchFromNode(path);
            System.out.println(formatJson(response));
            return 0;
        }

        private String buildEventsPath() {
            return option(since).map(s -> "/api/events?since=" + s)
                         .or("/api/events");
        }
    }

    // Simple JSON formatter for readability
    @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01", "JBCT-UTIL-02"})
    private static String formatJson(String json) {
        if (option(json).filter(s -> !s.isEmpty())
                  .isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        int indent = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
                sb.append(c);
            } else if (!inString) {
                appendFormattedChar(sb, c, indent);
                indent = updateIndent(c, indent);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static void appendFormattedChar(StringBuilder sb, char c, int indent) {
        switch (c) {
            case '{', '[' -> {
                sb.append(c);
                sb.append('\n');
                sb.append("  ".repeat(indent + 1));
            }
            case '}', ']' -> {
                sb.append('\n');
                sb.append("  ".repeat(indent - 1));
                sb.append(c);
            }
            case ',' -> {
                sb.append(c);
                sb.append('\n');
                sb.append("  ".repeat(indent));
            }
            case ':' -> sb.append(": ");
            default -> {
                if (!Character.isWhitespace(c)) {
                    sb.append(c);
                }
            }
        }
    }

    private static int updateIndent(char c, int indent) {
        return switch (c) {
            case '{', '[' -> indent + 1;
            case '}', ']' -> indent - 1;
            default -> indent;
        };
    }
}
