package org.pragmatica.aether.cli;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.ConfigLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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
 version = "Aether 0.15.0",
 description = "Command-line interface for Aether cluster management",
 subcommands = {AetherCli.StatusCommand.class,
 AetherCli.NodesCommand.class,
 AetherCli.SlicesCommand.class,
 AetherCli.MetricsCommand.class,
 AetherCli.HealthCommand.class,
 AetherCli.DeployCommand.class,
 AetherCli.ScaleCommand.class,
 AetherCli.UndeployCommand.class,
 AetherCli.BlueprintCommand.class,
 AetherCli.ArtifactCommand.class,
 AetherCli.UpdateCommand.class,
 AetherCli.InvocationMetricsCommand.class,
 AetherCli.ControllerCommand.class,
 AetherCli.AlertsCommand.class,
 AetherCli.ThresholdsCommand.class,
 AetherCli.AspectsCommand.class,
 AetherCli.LoggingCommand.class,
 AetherCli.ConfigCommand.class})
public class AetherCli implements Runnable {
    private static final String DEFAULT_ADDRESS = "localhost:8080";

    @Option(names = {"-c", "--connect"},
    description = "Node address to connect to (host:port)")
    private String nodeAddress;

    @Option(names = {"--config"},
    description = "Path to aether.toml config file")
    private Path configPath;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public static void main(String[] args) {
        var cli = new AetherCli();
        var cmd = new CommandLine(cli);
        // Pre-parse to extract connection info
        cli.resolveConnection(args);
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
        for (String arg : args) {
            // Skip known non-subcommand options
            if (arg.startsWith("-c") || arg.startsWith("--connect") ||
            arg.startsWith("--config") || arg.equals("-h") || arg.equals("--help") ||
            arg.equals("-V") || arg.equals("--version")) {
                continue;
            }
            // Skip option values (next arg after option)
            if (!arg.startsWith("-")) {
                // Could be a subcommand or option value
                // Check if previous arg was an option expecting value
                return false;
            }
        }
        return true;
    }

    private void resolveConnection(String[] args) {
        // Parse args manually to get --connect and --config
        String connectArg = null;
        Path configArg = null;
        for (int i = 0; i < args.length; i++) {
            if ((args[i].equals("-c") || args[i].equals("--connect")) && i + 1 < args.length) {
                connectArg = args[i + 1];
            } else if (args[i].startsWith("--connect=")) {
                connectArg = args[i].substring("--connect=".length());
            } else if (args[i].equals("--config") && i + 1 < args.length) {
                configArg = Path.of(args[i + 1]);
            } else if (args[i].startsWith("--config=")) {
                configArg = Path.of(args[i].substring("--config=".length()));
            }
        }
        // Priority: --connect > config file > default
        if (connectArg != null) {
            nodeAddress = connectArg;
        } else if (configArg != null && Files.exists(configArg)) {
            ConfigLoader.load(configArg)
                        .onSuccess(this::setAddressFromConfig)
                        .onFailure(this::handleConfigLoadFailure);
            configPath = configArg;
        } else {
            nodeAddress = DEFAULT_ADDRESS;
        }
    }

    private void setAddressFromConfig(AetherConfig config) {
        var port = config.cluster()
                         .ports()
                         .management();
        nodeAddress = "localhost:" + port;
    }

    private void handleConfigLoadFailure(org.pragmatica.lang.Cause cause) {
        System.err.println("Warning: Failed to load config: " + cause.message());
        nodeAddress = DEFAULT_ADDRESS;
    }

    @Override
    public void run() {
        // When no subcommand is specified, show help
        CommandLine.usage(this, System.out);
    }

    private void runRepl(CommandLine cmd) {
        System.out.println("Aether v0.15.0 - Connected to " + nodeAddress);
        System.out.println("Type 'help' for available commands, 'exit' to quit.");
        System.out.println();
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (true) {
                System.out.print("aether> ");
                System.out.flush();
                line = reader.readLine();
                if (line == null || line.trim()
                                        .equalsIgnoreCase("exit") || line.trim()
                                                                         .equalsIgnoreCase("quit")) {
                    System.out.println("Goodbye!");
                    break;
                }
                if (line.trim()
                        .isEmpty()) {
                    continue;
                }
                // Parse and execute command
                String[] replArgs = parseReplCommand(line.trim());
                if (replArgs.length > 0) {
                    // Prepend --connect option
                    String[] fullArgs = new String[replArgs.length + 2];
                    fullArgs[0] = "--connect";
                    fullArgs[1] = nodeAddress;
                    System.arraycopy(replArgs, 0, fullArgs, 2, replArgs.length);
                    cmd.execute(fullArgs);
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading input: " + e.getMessage());
        }
    }

    private String[] parseReplCommand(String line) {
        // Simple space-based splitting (could be enhanced for quoted strings)
        return line.split("\\s+");
    }

    String fetchFromNode(String path) {
        try{
            var uri = URI.create("http://" + nodeAddress + path);
            var request = HttpRequest.newBuilder()
                                     .uri(uri)
                                     .GET()
                                     .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                return "{\"error\":\"HTTP " + response.statusCode() + ": " + response.body() + "\"}";
            }
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    String postToNode(String path, String body) {
        try{
            var uri = URI.create("http://" + nodeAddress + path);
            var request = HttpRequest.newBuilder()
                                     .uri(uri)
                                     .header("Content-Type", "application/json")
                                     .POST(HttpRequest.BodyPublishers.ofString(body))
                                     .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                return "{\"error\":\"HTTP " + response.statusCode() + ": " + response.body() + "\"}";
            }
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    String putToNode(String path, byte[] content, String contentType) {
        try{
            var uri = URI.create("http://" + nodeAddress + path);
            var request = HttpRequest.newBuilder()
                                     .uri(uri)
                                     .header("Content-Type", contentType)
                                     .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                                     .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return response.body()
                               .isEmpty()
                       ? "{\"status\":\"ok\"}"
                       : response.body();
            } else {
                return "{\"error\":\"HTTP " + response.statusCode() + ": " + response.body() + "\"}";
            }
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    String deleteFromNode(String path) {
        try{
            var uri = URI.create("http://" + nodeAddress + path);
            var request = HttpRequest.newBuilder()
                                     .uri(uri)
                                     .DELETE()
                                     .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                return "{\"error\":\"HTTP " + response.statusCode() + ": " + response.body() + "\"}";
            }
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
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

    @Command(name = "deploy", description = "Deploy a slice to the cluster")
    static class DeployCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Parameters(index = "0", description = "Artifact coordinates (group:artifact:version)")
        private String artifact;

        @Option(names = {"-n", "--instances"}, description = "Number of instances", defaultValue = "1")
        private int instances;

        @Override
        public Integer call() {
            var body = "{\"artifact\":\"" + artifact + "\",\"instances\":" + instances + "}";
            var response = parent.postToNode("/deploy", body);
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

        @Option(names = {"-n", "--instances"}, description = "Target number of instances", required = true)
        private int instances;

        @Override
        public Integer call() {
            var body = "{\"artifact\":\"" + artifact + "\",\"instances\":" + instances + "}";
            var response = parent.postToNode("/scale", body);
            System.out.println(formatJson(response));
            return 0;
        }
    }

    @Command(name = "undeploy", description = "Remove a slice from the cluster")
    static class UndeployCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Parameters(index = "0", description = "Artifact coordinates (group:artifact:version)")
        private String artifact;

        @Override
        public Integer call() {
            var body = "{\"artifact\":\"" + artifact + "\"}";
            var response = parent.postToNode("/undeploy", body);
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

            @Option(names = {"-g", "--group"}, description = "Group ID", required = true)
            private String groupId;

            @Option(names = {"-a", "--artifact"}, description = "Artifact ID", required = true)
            private String artifactId;

            @Option(names = {"-v", "--version"}, description = "Version", required = true)
            private String version;

            @Override
            public Integer call() {
                try{
                    if (!Files.exists(jarPath)) {
                        System.err.println("File not found: " + jarPath);
                        return 1;
                    }
                    byte[] content = Files.readAllBytes(jarPath);
                    var coordinates = groupId + ":" + artifactId + ":" + version;
                    var repoPath = "/repository/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId
                                   + "-" + version + ".jar";
                    var response = artifactParent.parent.putToNode(repoPath, content, "application/java-archive");
                    if (response.startsWith("{\"error\":")) {
                        System.out.println("Failed to deploy: " + response);
                        return 1;
                    }
                    System.out.println("Deployed " + coordinates);
                    System.out.println("  File: " + jarPath);
                    System.out.println("  Size: " + content.length + " bytes");
                    return 0;
                } catch (IOException e) {
                    System.err.println("Error reading file: " + e.getMessage());
                    return 1;
                }
            }
        }

        @Command(name = "push", description = "Push artifact from local Maven repository to cluster")
        static class PushArtifactCommand implements Callable<Integer> {
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
                var groupId = parts[0];
                var artifactId = parts[1];
                var version = parts[2];
                // Resolve from local Maven repository
                var m2Home = System.getProperty("user.home") + "/.m2/repository";
                var localPath = Path.of(m2Home,
                                        groupId.replace('.', '/'),
                                        artifactId,
                                        version,
                                        artifactId + "-" + version + ".jar");
                if (!Files.exists(localPath)) {
                    System.err.println("Artifact not found in local Maven repository: " + localPath);
                    return 1;
                }
                try{
                    byte[] content = Files.readAllBytes(localPath);
                    var repoPath = "/repository/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId
                                   + "-" + version + ".jar";
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
                var groupId = parts[0];
                var artifactId = parts[1];
                var version = parts[2];
                var path = "/repository/info/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version;
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
            public Integer call() {
                var parts = coordinates.split(":");
                if (parts.length != 3) {
                    System.err.println("Invalid coordinates format. Expected: group:artifact:version");
                    return 1;
                }
                var groupId = parts[0];
                var artifactId = parts[1];
                var version = parts[2];
                var path = "/repository/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version;
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

            @Option(names = {"--format"}, description = "Output format (table|json)", defaultValue = "table")
            private String format;

            @Override
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

            private void printBlueprintListTable(String json) {
                // Simple table output - parse blueprints array
                System.out.println("ID                                     SLICES");
                System.out.println("─".repeat(60));
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
                var depth = 0;
                var start = 0;
                for (int i = 0; i < blueprintsArray.length(); i++) {
                    var c = blueprintsArray.charAt(i);
                    if (c == '{') {
                        if (depth == 0) start = i;
                        depth++;
                    } else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            var obj = blueprintsArray.substring(start, i + 1);
                            printBlueprintRow(obj);
                        }
                    }
                }
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

            @Option(names = {"--format"}, description = "Output format (table|json)", defaultValue = "table")
            private String format;

            @Override
            public Integer call() {
                var encodedId = blueprintId.replace(":", "%3A");
                var response = blueprintParent.parent.fetchFromNode("/api/blueprint/" + encodedId);
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
                System.out.println("─".repeat(80));
                System.out.printf("%-50s %10s %12s%n", "ARTIFACT", "INSTANCES", "TYPE");
                System.out.println("─".repeat(80));
                // Parse slices array
                var slicesStart = json.indexOf("\"slices\":");
                if (slicesStart != - 1) {
                    var arrayStart = json.indexOf('[', slicesStart);
                    var arrayEnd = findMatchingBracket(json, arrayStart);
                    if (arrayStart != - 1 && arrayEnd != - 1) {
                        var slicesArray = json.substring(arrayStart + 1, arrayEnd);
                        parseAndPrintSlices(slicesArray);
                    }
                }
            }

            private void parseAndPrintSlices(String slicesArray) {
                var depth = 0;
                var start = 0;
                for (int i = 0; i < slicesArray.length(); i++) {
                    var c = slicesArray.charAt(i);
                    if (c == '{') {
                        if (depth == 0) start = i;
                        depth++;
                    } else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            var obj = slicesArray.substring(start, i + 1);
                            printSliceRow(obj);
                        }
                    }
                }
            }

            private void printSliceRow(String json) {
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

            @Option(names = {"--force", "-f"}, description = "Skip confirmation prompt")
            private boolean force;

            @Override
            public Integer call() {
                if (!force) {
                    System.out.print("Are you sure you want to delete blueprint '" + blueprintId + "'? (y/N) ");
                    try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
                        var line = reader.readLine();
                        if (line == null || !line.trim()
                                                 .equalsIgnoreCase("y")) {
                            System.out.println("Cancelled.");
                            return 0;
                        }
                    } catch (IOException e) {
                        System.err.println("Error reading input: " + e.getMessage());
                        return 1;
                    }
                }
                var encodedId = blueprintId.replace(":", "%3A");
                var response = blueprintParent.parent.deleteFromNode("/api/blueprint/" + encodedId);
                if (response.contains("\"error\":")) {
                    System.out.println("Failed to delete blueprint: " + response);
                    return 1;
                }
                System.out.println("Deleted blueprint: " + blueprintId);
                return 0;
            }
        }

        @Command(name = "status", description = "Show deployment status of a blueprint")
        static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Blueprint ID (group:artifact:version)")
            private String blueprintId;

            @Option(names = {"--format"}, description = "Output format (table|json)", defaultValue = "table")
            private String format;

            @Override
            public Integer call() {
                var encodedId = blueprintId.replace(":", "%3A");
                var response = blueprintParent.parent.fetchFromNode("/api/blueprint/" + encodedId + "/status");
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
                System.out.println("─".repeat(90));
                System.out.printf("%-50s %8s %8s %12s%n", "ARTIFACT", "TARGET", "ACTIVE", "STATUS");
                System.out.println("─".repeat(90));
                // Parse slices array
                var slicesStart = json.indexOf("\"slices\":");
                if (slicesStart != - 1) {
                    var arrayStart = json.indexOf('[', slicesStart);
                    var arrayEnd = findMatchingBracket(json, arrayStart);
                    if (arrayStart != - 1 && arrayEnd != - 1) {
                        var slicesArray = json.substring(arrayStart + 1, arrayEnd);
                        parseAndPrintStatusSlices(slicesArray);
                    }
                }
            }

            private void parseAndPrintStatusSlices(String slicesArray) {
                var depth = 0;
                var start = 0;
                for (int i = 0; i < slicesArray.length(); i++) {
                    var c = slicesArray.charAt(i);
                    if (c == '{') {
                        if (depth == 0) start = i;
                        depth++;
                    } else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            var obj = slicesArray.substring(start, i + 1);
                            printStatusSliceRow(obj);
                        }
                    }
                }
            }

            private void printStatusSliceRow(String json) {
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
            public Integer call() {
                try{
                    if (!Files.exists(blueprintPath)) {
                        System.err.println("Blueprint file not found: " + blueprintPath);
                        return 1;
                    }
                    var content = Files.readString(blueprintPath);
                    var response = blueprintParent.parent.postToNode("/api/blueprint/validate", content);
                    if (response.contains("\"valid\":false")) {
                        System.out.println("Validation FAILED");
                        var errors = extractJsonArray(response, "errors");
                        if (!errors.isEmpty()) {
                            System.out.println("Errors:");
                            for (var error : errors) {
                                System.out.println("  - " + error);
                            }
                        }
                        return 1;
                    }
                    var id = extractJsonString(response, "id");
                    var sliceCount = extractJsonNumber(response, "sliceCount");
                    System.out.println("Validation PASSED");
                    System.out.println("  Blueprint ID: " + id);
                    System.out.println("  Slices: " + sliceCount);
                    return 0;
                } catch (IOException e) {
                    System.err.println("Error reading blueprint file: " + e.getMessage());
                    return 1;
                }
            }

            private java.util.List<String> extractJsonArray(String json, String key) {
                var result = new java.util.ArrayList<String>();
                var keyPattern = "\"" + key + "\":";
                var start = json.indexOf(keyPattern);
                if (start == - 1) return result;
                var arrayStart = json.indexOf('[', start);
                var arrayEnd = findMatchingBracket(json, arrayStart);
                if (arrayStart == - 1 || arrayEnd == - 1) return result;
                var arrayContent = json.substring(arrayStart + 1, arrayEnd);
                // Parse string elements
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
                return result;
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

            @Option(names = {"-n", "--instances"}, description = "Number of new version instances", defaultValue = "1")
            private int instances;

            @Option(names = {"--error-rate"}, description = "Max error rate threshold (0.0-1.0)", defaultValue = "0.01")
            private double errorRate;

            @Option(names = {"--latency"}, description = "Max latency threshold in ms", defaultValue = "500")
            private long latencyMs;

            @Option(names = {"--manual-approval"}, description = "Require manual approval for routing changes")
            private boolean manualApproval;

            @Option(names = {"--cleanup"}, description = "Cleanup policy: IMMEDIATE, GRACE_PERIOD, MANUAL", defaultValue = "GRACE_PERIOD")
            private String cleanupPolicy;

            @Override
            public Integer call() {
                var body = "{\"artifactBase\":\"" + artifactBase + "\"," + "\"version\":\"" + version + "\","
                           + "\"instances\":" + instances + "," + "\"maxErrorRate\":" + errorRate + ","
                           + "\"maxLatencyMs\":" + latencyMs + "," + "\"requireManualApproval\":" + manualApproval + ","
                           + "\"cleanupPolicy\":\"" + cleanupPolicy + "\"}";
                var response = updateParent.parent.postToNode("/rolling-update/start", body);
                System.out.println(formatJson(response));
                return 0;
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

            @Option(names = {"-r", "--ratio"}, description = "Traffic ratio new:old (e.g., 1:3)", required = true)
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
            public Integer call() {
                if (type == null) {
                    // Show current strategy
                    var response = metricsParent.parent.fetchFromNode("/invocation-metrics/strategy");
                    System.out.println(formatJson(response));
                } else {
                    // Set strategy
                    String body;
                    switch (type.toLowerCase()) {
                        case "fixed" -> {
                            var thresholdMs = param1 != null
                                              ? param1
                                              : 100;
                            body = "{\"type\":\"fixed\",\"thresholdMs\":" + thresholdMs + "}";
                        }
                        case "adaptive" -> {
                            var minMs = param1 != null
                                        ? param1
                                        : 10;
                            var maxMs = param2 != null
                                        ? param2
                                        : 1000;
                            body = "{\"type\":\"adaptive\",\"minMs\":" + minMs + ",\"maxMs\":" + maxMs + "}";
                        }
                        default -> {
                            System.err.println("Unknown strategy type: " + type);
                            System.err.println("Supported: fixed, adaptive");
                            return 1;
                        }
                    }
                    var response = metricsParent.parent.postToNode("/invocation-metrics/strategy", body);
                    System.out.println(formatJson(response));
                }
                return 0;
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

            @Option(names = {"--cpu-up"}, description = "CPU scale-up threshold")
            private Double cpuScaleUp;

            @Option(names = {"--cpu-down"}, description = "CPU scale-down threshold")
            private Double cpuScaleDown;

            @Option(names = {"--call-rate"}, description = "Call rate scale-up threshold")
            private Double callRate;

            @Option(names = {"--interval"}, description = "Evaluation interval in ms")
            private Long intervalMs;

            @Override
            public Integer call() {
                if (cpuScaleUp != null || cpuScaleDown != null || callRate != null || intervalMs != null) {
                    // Update configuration
                    var sb = new StringBuilder("{");
                    boolean first = true;
                    if (cpuScaleUp != null) {
                        sb.append("\"cpuScaleUpThreshold\":")
                          .append(cpuScaleUp);
                        first = false;
                    }
                    if (cpuScaleDown != null) {
                        if (!first) sb.append(",");
                        sb.append("\"cpuScaleDownThreshold\":")
                          .append(cpuScaleDown);
                        first = false;
                    }
                    if (callRate != null) {
                        if (!first) sb.append(",");
                        sb.append("\"callRateScaleUpThreshold\":")
                          .append(callRate);
                        first = false;
                    }
                    if (intervalMs != null) {
                        if (!first) sb.append(",");
                        sb.append("\"evaluationIntervalMs\":")
                          .append(intervalMs);
                    }
                    sb.append("}");
                    var response = controllerParent.parent.postToNode("/controller/config", sb.toString());
                    System.out.println(formatJson(response));
                } else {
                    // Show current config
                    var response = controllerParent.parent.fetchFromNode("/controller/config");
                    System.out.println(formatJson(response));
                }
                return 0;
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

            @Option(names = {"-w", "--warning"}, description = "Warning threshold", required = true)
            private double warning;

            @Option(names = {"-c", "--critical"}, description = "Critical threshold", required = true)
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
                var body = "{\"artifact\":\"" + artifact + "\",\"method\":\"" + method + "\",\"mode\":\"" + normalizedMode
                           + "\"}";
                var response = aspectsParent.parent.postToNode("/api/aspects", body);
                System.out.println(formatJson(response));
                return 0;
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

        private static void printAspectsTable(String json) {
            // Parse JSON map: {"key::mode", ...}
            if (json == null || json.trim()
                                    .equals("{}") || json.contains("\"error\":")) {
                if (json != null && json.contains("\"error\":")) {
                    System.out.println(formatJson(json));
                } else {
                    System.out.println("No active aspects");
                }
                return;
            }
            System.out.printf("%-40s %-20s %s%n", "ARTIFACT", "METHOD", "MODE");
            System.out.println("\u2500".repeat(75));
            // Simple JSON map parsing: {"artifactBase/method":"MODE", ...}
            var content = json.trim();
            if (content.startsWith("{")) content = content.substring(1);
            if (content.endsWith("}")) content = content.substring(0, content.length() - 1);
            if (content.trim()
                       .isEmpty()) {
                System.out.println("No active aspects");
                return;
            }
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
                            // key = "artifactBase/method", token = mode
                            var slashIndex = key.indexOf('/');
                            var artifact = slashIndex != - 1
                                           ? key.substring(0, slashIndex)
                                           : key;
                            var method = slashIndex != - 1
                                         ? key.substring(slashIndex + 1)
                                         : "";
                            System.out.printf("%-40s %-20s %s%n", artifact, method, token);
                            expectValue = false;
                        }
                    }
                }
            }
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

            @Option(names = {"--node"}, description = "Target node ID (omit for cluster-wide)")
            private String nodeId;

            @Override
            public Integer call() {
                var body = new StringBuilder("{\"key\":\"").append(key)
                                                           .append("\",\"value\":\"")
                                                           .append(value)
                                                           .append("\"");
                if (nodeId != null) {
                    body.append(",\"nodeId\":\"")
                        .append(nodeId)
                        .append("\"");
                }
                body.append("}");
                var response = configParent.parent.postToNode("/api/config", body.toString());
                System.out.println(formatJson(response));
                return 0;
            }
        }

        @Command(name = "remove", description = "Remove a configuration override")
        static class RemoveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ConfigCommand configParent;

            @Parameters(index = "0", description = "Configuration key (dot.notation)")
            private String key;

            @Option(names = {"--node"}, description = "Target node ID (omit for cluster-wide)")
            private String nodeId;

            @Override
            public Integer call() {
                String response;
                if (nodeId != null) {
                    response = configParent.parent.deleteFromNode("/api/config/node/" + nodeId + "/" + key);
                } else {
                    response = configParent.parent.deleteFromNode("/api/config/" + key);
                }
                System.out.println(formatJson(response));
                return 0;
            }
        }
    }

    // Simple JSON formatter for readability
    private static String formatJson(String json) {
        if (json == null || json.isEmpty()) {
            return json;
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
                switch (c) {
                    case '{', '[' -> {
                        sb.append(c);
                        sb.append('\n');
                        indent++;
                        sb.append("  ".repeat(indent));
                    }
                    case '}', ']' -> {
                        sb.append('\n');
                        indent--;
                        sb.append("  ".repeat(indent));
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
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
