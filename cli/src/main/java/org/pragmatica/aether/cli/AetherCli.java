package org.pragmatica.aether.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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

/**
 * Aether cluster management CLI.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li>Batch mode: Execute commands directly from command line</li>
 *   <li>REPL mode: Interactive shell when no command specified</li>
 * </ul>
 *
 * <p>Usage examples:
 * <pre>
 * # Batch mode
 * aether-cli --connect node1:8080 status
 * aether-cli --connect node1:8080 nodes
 * aether-cli --connect node1:8080 metrics
 *
 * # REPL mode
 * aether-cli --connect node1:8080
 * aether> status
 * aether> nodes
 * aether> exit
 * </pre>
 */
@Command(name = "aether",
         mixinStandardHelpOptions = true,
         version = "Aether 0.6.2",
         description = "Command-line interface for Aether cluster management",
         subcommands = {
                 AetherCli.StatusCommand.class,
                 AetherCli.NodesCommand.class,
                 AetherCli.SlicesCommand.class,
                 AetherCli.MetricsCommand.class,
                 AetherCli.HealthCommand.class,
                 AetherCli.DeployCommand.class,
                 AetherCli.ScaleCommand.class,
                 AetherCli.UndeployCommand.class,
                 AetherCli.BlueprintCommand.class,
                 AetherCli.ArtifactCommand.class
         })
public class AetherCli implements Runnable {

    @Option(names = {"-c", "--connect"},
            description = "Node address to connect to (host:port)",
            defaultValue = "localhost:8080")
    private String nodeAddress;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public static void main(String[] args) {
        var cli = new AetherCli();
        var cmd = new CommandLine(cli);

        if (args.length == 0 || (args.length == 2 && (args[0].equals("-c") || args[0].equals("--connect")))) {
            // REPL mode when no command specified (only --connect option)
            if (args.length == 2) {
                cli.nodeAddress = args[1];
            }
            cli.runRepl(cmd);
        } else {
            // Batch mode
            int exitCode = cmd.execute(args);
            System.exit(exitCode);
        }
    }

    @Override
    public void run() {
        // When no subcommand is specified, show help
        CommandLine.usage(this, System.out);
    }

    private void runRepl(CommandLine cmd) {
        System.out.println("Aether v0.6.2 - Connected to " + nodeAddress);
        System.out.println("Type 'help' for available commands, 'exit' to quit.");
        System.out.println();

        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (true) {
                System.out.print("aether> ");
                System.out.flush();
                line = reader.readLine();

                if (line == null || line.trim().equalsIgnoreCase("exit") || line.trim().equalsIgnoreCase("quit")) {
                    System.out.println("Goodbye!");
                    break;
                }

                if (line.trim().isEmpty()) {
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
        try {
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
        try {
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
        try {
            var uri = URI.create("http://" + nodeAddress + path);
            var request = HttpRequest.newBuilder()
                                     .uri(uri)
                                     .header("Content-Type", contentType)
                                     .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                                     .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return response.body().isEmpty() ? "{\"status\":\"ok\"}" : response.body();
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
             subcommands = {
                     ArtifactCommand.DeployArtifactCommand.class,
                     ArtifactCommand.ListArtifactsCommand.class,
                     ArtifactCommand.VersionsCommand.class
             })
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
                try {
                    if (!Files.exists(jarPath)) {
                        System.err.println("File not found: " + jarPath);
                        return 1;
                    }

                    byte[] content = Files.readAllBytes(jarPath);
                    var coordinates = groupId + ":" + artifactId + ":" + version;
                    var repoPath = "/repository/" +
                                   groupId.replace('.', '/') + "/" +
                                   artifactId + "/" +
                                   version + "/" +
                                   artifactId + "-" + version + ".jar";

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
    }

    @Command(name = "blueprint",
             description = "Blueprint management",
             subcommands = {
                     BlueprintCommand.ApplyCommand.class
             })
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
                try {
                    if (!Files.exists(blueprintPath)) {
                        System.err.println("Blueprint file not found: " + blueprintPath);
                        return 1;
                    }

                    var content = Files.readString(blueprintPath);
                    var response = blueprintParent.parent.postToNode("/blueprint", content);

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
