package org.pragmatica.aether.cli;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.jar.JarFile;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import picocli.AutoComplete.GenerateCompletion;
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
version = "Aether 1.0.0-alpha",
description = "Command-line interface for Aether cluster management",
subcommands = {AetherCli.StatusCommand.class, AetherCli.NodesCommand.class, AetherCli.SlicesCommand.class, AetherCli.MetricsCommand.class, AetherCli.HealthCommand.class, AetherCli.ScaleCommand.class, AetherCli.BlueprintCommand.class, AetherCli.ArtifactCommand.class, AetherCli.InvocationMetricsCommand.class, AetherCli.ControllerCommand.class, AetherCli.AlertsCommand.class, AetherCli.ThresholdsCommand.class, AetherCli.TracesCommand.class, AetherCli.ObservabilityCommand.class, AetherCli.LoggingCommand.class, AetherCli.ConfigCommand.class, AetherCli.ScheduledTasksCommand.class, AetherCli.EventsCommand.class, AetherCli.NodeCommand.class, AetherCli.TopologyStatusCommand.class, AetherCli.WorkersCommand.class, AetherCli.BackupCommand.class, AetherCli.SchemaCommand.class, AetherCli.AbTestCommand.class, AetherCli.StreamCommand.class, AetherCli.CertCommand.class, AetherCli.NodeSlicesCommand.class, AetherCli.RoutesCommand.class, AetherCli.NodeRoutesCommand.class, org.pragmatica.aether.cli.deploy.DeployCommand.class, org.pragmatica.aether.cli.cluster.ClusterCommand.class, org.pragmatica.aether.cli.storage.StorageCommand.class, GenerateCompletion.class})
@Contract public class AetherCli implements Runnable {
    private static final String DEFAULT_ADDRESS = "localhost:8080";

    @CommandLine.Option(names = {"-c", "--connect", "--endpoint"},
    description = "Node address to connect to (host:port)")
    private String nodeAddress;

    @CommandLine.Option(names = {"--config"},
    description = "Path to aether.toml config file")
    private Path configPath;

    @CommandLine.Option(names = "--api-key",
    description = "API key for authenticated access (prefer AETHER_API_KEY env var to avoid process list exposure)")
    private String apiKey;

    @CommandLine.Option(names = {"-k", "--tls-skip-verify"},
    description = "Skip TLS certificate verification (insecure)")
    private boolean tlsSkipVerify;

    @CommandLine.Mixin private OutputOptions outputOptions = new OutputOptions();

    private HttpOperations httpOps;

    public OutputOptions outputOptions() {
        return outputOptions;
    }

    @Contract public static void main(String[] args) {
        var cli = new AetherCli();
        var cmd = new CommandLine(cli);
        // Pre-parse to extract connection info and TLS flag
        cli.lookupConnection(args);
        cli.tlsSkipVerify = containsTlsSkipVerify(args);
        cli.httpOps = cli.buildHttpOperations();
        // Check if this is REPL mode (no subcommand)
        if ( isReplMode(args)) {
        cli.runRepl(cmd);} else
        {
            // Batch mode
            int exitCode = cmd.execute(args);
            System.exit(exitCode);
        }
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static boolean isReplMode(String[] args) {
        // REPL if no args, or only connection-related options and their values
        if ( args.length == 0) {
        return true;}
        var skipNext = false;
        for ( var arg : args) {
            if ( skipNext) {
                skipNext = false;
                continue;
            }
            if ( isBooleanConnectionFlag(arg)) {} else if ( isConnectionValueFlag(arg)) {
            if ( !arg.contains("=")) {
            skipNext = true;}} else {
            return false;}
        }
        return true;
    }

    private static boolean isConnectionFlag(String arg) {
        return isConnectionValueFlag(arg) || isBooleanConnectionFlag(arg);
    }

    private static boolean isConnectionValueFlag(String arg) {
        return arg.startsWith("-c") || arg.startsWith("--connect") || arg.startsWith("--endpoint") ||
        arg.startsWith("--config") || arg.startsWith("--api-key");
    }

    private static boolean isBooleanConnectionFlag(String arg) {
        return arg.equals("-k") || arg.equals("--tls-skip-verify");
    }

    private static boolean containsTlsSkipVerify(String[] args) {
        for ( var arg : args) {
        if ( arg.equals("-k") || arg.equals("--tls-skip-verify")) {
        return true;}}
        return false;
    }

    @SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01"})
    private HttpOperations buildHttpOperations() {
        if ( !tlsSkipVerify) {
        return JdkHttpOperations.jdkHttpOperations();}
        return buildTrustAllHttpOperations();
    }

    @SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01", "JBCT-EX-01"})
    private static HttpOperations buildTrustAllHttpOperations() {
        try {
            var sslContext = createTrustAllSslContext();
            var client = HttpClient.newBuilder().sslContext(sslContext)
                                              .build();
            return JdkHttpOperations.jdkHttpOperations(client);
        }


























        catch (NoSuchAlgorithmException | KeyManagementException e) {
            System.err.println("Warning: Failed to create trust-all SSL context: " + e.getMessage());
            return JdkHttpOperations.jdkHttpOperations();
        }
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"})
    private static SSLContext createTrustAllSslContext() throws NoSuchAlgorithmException, KeyManagementException {
        var trustAll = new TrustManager[]{new TrustAllManager()};
        var sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAll, new SecureRandom());
        return sslContext;
    }

    @SuppressWarnings("JBCT-UTIL-02")
    private void lookupConnection(String[] args) {
        // Parse args manually to get --connect and --config
        var connectArg = extractConnectArg(args);
        var configArg = extractConfigArg(args);
        // Priority: --connect > config file > default
        connectArg.onPresent(address -> nodeAddress = address).onEmpty(() -> setAddressFromConfigOrDefault(configArg));
    }

    private void setAddressFromConfigOrDefault(Option<Path> configArg) {
        configArg.filter(Files::exists).onPresent(this::readConfigFromPath)
                        .onEmpty(() -> nodeAddress = DEFAULT_ADDRESS);
    }

    private void readConfigFromPath(Path path) {
        ConfigLoader.load(path).onSuccess(this::setAddressFromConfig)
                         .onFailure(this::onConfigLoadFailure);
        configPath = path;
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01"})
    private static Option<String> extractConnectArg(String[] args) {
        for ( int i = 0; i < args.length; i++) {
        if ( (args[i].equals("-c") || args[i].equals("--connect") || args[i].equals("--endpoint")) && i + 1 < args.length) {
        return some(args[i + 1]);} else
        if ( args[i].startsWith("--connect=")) {
        return some(args[i].substring("--connect=".length()));} else if ( args[i].startsWith("--endpoint=")) {
        return some(args[i].substring("--endpoint=".length()));}}
        return empty();
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01"})
    private static Option<Path> extractConfigArg(String[] args) {
        for ( int i = 0; i < args.length; i++) {
        if ( args[i].equals("--config") && i + 1 < args.length) {
        return some(Path.of(args[i + 1]));} else
        if ( args[i].startsWith("--config=")) {
        return some(Path.of(args[i].substring("--config=".length())));}}
        return empty();
    }

    private void setAddressFromConfig(AetherConfig config) {
        var port = config.cluster().ports()
                                 .management();
        nodeAddress = "localhost:" + port;
    }

    private void onConfigLoadFailure(Cause cause) {
        System.err.println("Warning: Failed to load config: " + cause.message());
        nodeAddress = DEFAULT_ADDRESS;
    }

    @Contract @Override public void run() {
        // When no subcommand is specified, show help
        CommandLine.usage(this, System.out);
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01", "JBCT-UTIL-02"})
    private void runRepl(CommandLine cmd) {
        System.out.println("Aether v1.0.0-alpha - Connected to " + nodeAddress);
        System.out.println("Type 'help' for available commands, 'exit' to quit.");
        System.out.println();
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ( true) {
                System.out.print("aether> ");
                System.out.flush();
                line = reader.readLine();
                if ( isExitCommand(line)) {
                    System.out.println("Goodbye!");
                    break;
                }
                if ( line.trim().isEmpty()) {
                continue;}
                // Parse and execute command
                sendReplCommand(cmd, line.trim());
            }
        }


























        catch (IOException e) {
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
        if ( replArgs.length > 0) {
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
        if ( tlsSkipVerify) {
        args.add("--tls-skip-verify");}
        for ( var arg : replArgs) {
        args.add(arg);}
        return args.toArray(String[]::new);
    }

    private URI resolveNodeUri(String path) {
        return hasScheme(nodeAddress)
               ? URI.create(nodeAddress + path)
               : URI.create(resolveScheme() + nodeAddress + path);
    }

    private String resolveScheme() {
        return tlsSkipVerify
               ? "https://"
               : "http://";
    }

    private static boolean hasScheme(String address) {
        return address.startsWith("http://") || address.startsWith("https://");
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) public String fetchFromNode(String path) {
        var uri = resolveNodeUri(path);
        var request = buildGetRequest(uri);
        return httpOps.sendString(request).await()
                                 .fold(cause -> "{\"error\":\"" + cause.message() + "\"}",
                                       AetherCli::extractResponseBody);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) public String postToNode(String path, String body) {
        var uri = resolveNodeUri(path);
        var request = buildPostRequest(uri, body);
        return httpOps.sendString(request).await()
                                 .fold(cause -> "{\"error\":\"" + cause.message() + "\"}",
                                       AetherCli::extractResponseBody);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01", "JBCT-UTIL-02"}) String putToNode(String path,
                                                                                        byte[] content,
                                                                                        String contentType) {
        var uri = resolveNodeUri(path);
        var request = buildPutRequest(uri, content, contentType);
        return httpOps.sendString(request).await()
                                 .fold(cause -> "{\"error\":\"" + cause.message() + "\"}",
                                       AetherCli::extractPutResponseBody);
    }

    @SuppressWarnings("JBCT-UTIL-01") String deleteFromNode(String path) {
        var uri = resolveNodeUri(path);
        var request = buildDeleteRequest(uri);
        return httpOps.sendString(request).await()
                                 .fold(cause -> "{\"error\":\"" + cause.message() + "\"}",
                                       AetherCli::extractResponseBody);
    }

    private Option<String> resolveApiKey() {
        return option(apiKey).filter(k -> !k.isBlank())
                     .orElse(() -> option(System.getenv("AETHER_API_KEY")).filter(k -> !k.isBlank()));
    }

    private void attachApiKey(HttpRequest.Builder builder) {
        resolveApiKey().onPresent(key -> builder.header("X-API-Key", key));
    }

    private HttpRequest buildGetRequest(URI uri) {
        var builder = HttpRequest.newBuilder().uri(uri)
                                            .GET();
        attachApiKey(builder);
        return builder.build();
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private HttpRequest buildPostRequest(URI uri, String body) {
        var builder = HttpRequest.newBuilder().uri(uri)
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(body));
        attachApiKey(builder);
        return builder.build();
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private HttpRequest buildPutRequest(URI uri, byte[] content, String contentType) {
        var builder = HttpRequest.newBuilder().uri(uri)
                                            .header("Content-Type", contentType)
                                            .PUT(HttpRequest.BodyPublishers.ofByteArray(content));
        attachApiKey(builder);
        return builder.build();
    }

    private HttpRequest buildDeleteRequest(URI uri) {
        var builder = HttpRequest.newBuilder().uri(uri)
                                            .DELETE();
        attachApiKey(builder);
        return builder.build();
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static String extractResponseBody(HttpResult<String> response) {
        if ( response.statusCode() == 200) {
        return response.body();}
        if ( response.statusCode() == 401) {
        return "{\"error\":\"Authentication required. Use --api-key or set AETHER_API_KEY environment variable.\"}";}
        if ( response.statusCode() == 403) {
        return "{\"error\":\"Access denied. The provided API key does not have sufficient permissions.\"}";}
        return formatErrorResponse(response);
    }

    @SuppressWarnings("JBCT-UTIL-02")
    private static String extractPutResponseBody(HttpResult<String> response) {
        if ( response.statusCode() == 200 || response.statusCode() == 201) {
        return response.body().isEmpty()
               ? "{\"status\":\"ok\"}"
               : response.body();}
        return formatErrorResponse(response);
    }

    private static String formatErrorResponse(HttpResult<String> response) {
        var body = response.body();
        if ( body != null && body.startsWith("{")) {
        return body;}
        var escaped = body == null
                      ? ""
                      : body.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"error\":\"HTTP " + response.statusCode() + ": " + escaped + "\"}";
    }

    private static String escapeJsonValue(String s) {
        if ( s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t");
    }

    // ===== Subcommands =====
    @Command(name = "status", description = "Show cluster status")
    static class StatusCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private AetherCli parent;

        @Override public Integer call() {
            var response = parent.fetchFromNode("/api/status");
            return OutputFormatter.printQuery(response, parent.outputOptions());
        }
    }

    @Command(name = "nodes", description = "List active nodes")
    static class NodesCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private AetherCli parent;

        @Override public Integer call() {
            var response = parent.fetchFromNode("/api/nodes");
            return OutputFormatter.printQuery(response, parent.outputOptions());
        }
    }

    @Command(name = "slices", description = "Show all slices across the cluster")
    static class SlicesCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private AetherCli parent;

        @Override public Integer call() {
            var response = parent.fetchFromNode("/api/slices");
            return OutputFormatter.printQuery(response, parent.outputOptions());
        }
    }

    @Command(name = "node-slices", description = "Show slices loaded on the connected node")
    static class NodeSlicesCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private AetherCli parent;

        @Override public Integer call() {
            var response = parent.fetchFromNode("/api/node/slices");
            return OutputFormatter.printQuery(response, parent.outputOptions());
        }
    }

    @Command(name = "node-routes", description = "Show HTTP routes on the connected node")
    static class NodeRoutesCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private AetherCli parent;

        @Override public Integer call() {
            var response = parent.fetchFromNode("/api/node/routes");
            return OutputFormatter.printQuery(response, parent.outputOptions());
        }
    }

    @Command(name = "routes", description = "Show HTTP routes across the cluster")
    static class RoutesCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private AetherCli parent;

        @Override public Integer call() {
            var response = parent.fetchFromNode("/api/routes");
            return OutputFormatter.printQuery(response, parent.outputOptions());
        }
    }

    @Command(name = "metrics", description = "Show cluster metrics")
    static class MetricsCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private AetherCli parent;

        @Override public Integer call() {
            var response = parent.fetchFromNode("/api/metrics");
            return OutputFormatter.printQuery(response, parent.outputOptions());
        }
    }

    @Command(name = "health", description = "Check node health")
    static class HealthCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private AetherCli parent;

        @Override public Integer call() {
            var response = parent.fetchFromNode("/api/health");
            return OutputFormatter.printQuery(response, parent.outputOptions());
        }
    }

    @Command(name = "scale", description = "Scale a deployed slice")
    @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01"})
    static class ScaleCommand implements Callable<Integer> {
        private static final int POLL_INTERVAL_MS = 2000;

        @CommandLine.ParentCommand private AetherCli parent;

        @Parameters(index = "0", description = "Artifact coordinates (group:artifact:version)")
        private String artifact;

        @CommandLine.Option(names = {"-n", "--instances"}, description = "Target number of instances", required = true)
        private int instances;

        @CommandLine.Option(names = {"-p", "--placement"}, description = "Placement strategy: CORE_ONLY, WORKER_PREFERRED, WORKER_ONLY")
        private String placement;

        @CommandLine.Option(names = "--wait", description = "Wait for scaling to complete")
        private boolean waitForCompletion;

        @CommandLine.Option(names = "--timeout", description = "Timeout in seconds when waiting")
        private int timeoutSeconds = 0;

        @Override public Integer call() {
            var body = buildScaleBody();
            var response = parent.postToNode("/api/scale", body);
            var result = OutputFormatter.printAction(response,
                                                     parent.outputOptions(),
                                                     "Scaled " + artifact + " to " + instances + " instances");
            if ( result != ExitCode.SUCCESS || !waitForCompletion) {
            return result;}
            return validateTimeoutAndPoll();
        }

        private int validateTimeoutAndPoll() {
            if ( timeoutSeconds <= 0) {
                System.err.println("--timeout is required when using --wait");
                return ExitCode.ERROR;
            }
            return pollUntilScaled();
        }

        @SuppressWarnings("JBCT-EX-01")
        private int pollUntilScaled() {
            System.out.printf("Waiting for %s to reach %d instances (timeout: %ds)...%n",
                              artifact,
                              instances,
                              timeoutSeconds);
            var deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
            while ( System.currentTimeMillis() < deadline) {
                var currentInstances = queryCurrentInstances();
                if ( currentInstances >= instances) {
                    System.out.printf("Scaling complete: %s now has %d instances.%n", artifact, currentInstances);
                    return ExitCode.SUCCESS;
                }
                System.out.printf("  Current instances: %d / %d%n", currentInstances, instances);
                sleepQuietly();
            }
            System.err.printf("Timeout: %s did not reach %d instances within %ds.%n",
                              artifact,
                              instances,
                              timeoutSeconds);
            return ExitCode.TIMEOUT;
        }

        private int queryCurrentInstances() {
            var response = parent.fetchFromNode("/api/slices");
            return parseInstanceCount(response);
        }

        private int parseInstanceCount(String response) {
            if ( response.contains("\"error\"")) {
            return - 1;}
            return countMatchingInstances(response);
        }

        private int countMatchingInstances(String response) {
            // Count occurrences of the artifact in running slices
            var index = 0;
            var count = 0;
            while ( (index = response.indexOf(artifact, index)) >= 0) {
                count++;
                index += artifact.length();
            }
            return count;
        }

        @SuppressWarnings("JBCT-EX-01")
        private static void sleepQuietly() {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            }


























            catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        private String buildScaleBody() {
            var sb = new StringBuilder("{\"artifact\":\"").append(escapeJsonValue(artifact))
                                                          .append("\",\"instances\":")
                                                          .append(instances);
            if ( placement != null) {
            sb.append(",\"placement\":\"").append(escapeJsonValue(placement))
                     .append("\"");}
            return sb.append("}").toString();
        }
    }

    @Command(name = "artifact",
    description = "Artifact repository management",
    subcommands = {ArtifactCommand.DeployArtifactCommand.class, ArtifactCommand.PushArtifactCommand.class, ArtifactCommand.ListArtifactsCommand.class, ArtifactCommand.VersionsCommand.class, ArtifactCommand.InfoCommand.class, ArtifactCommand.DeleteCommand.class, ArtifactCommand.MetricsCommand.class})
    static class ArtifactCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "deploy", description = "Deploy a JAR file to the artifact repository")
        static class DeployArtifactCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ArtifactCommand artifactParent;

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
                try {
                    if ( !Files.exists(jarPath)) {
                        System.err.println("File not found: " + jarPath);
                        return ExitCode.ERROR;
                    }
                    byte[] content = Files.readAllBytes(jarPath);
                    var coordinates = groupId + ":" + artifactId + ":" + version;
                    var repoPath = buildArtifactPath(groupId, artifactId, version);
                    var response = artifactParent.parent.putToNode(repoPath, content, "application/java-archive");
                    return reportDeployResult(response, coordinates, content.length);
                }


























                catch (IOException e) {
                    System.err.println("Error reading file: " + e.getMessage());
                    return ExitCode.ERROR;
                }
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private Integer reportDeployResult(String response, String coordinates, int size) {
                var errorCode = OutputFormatter.checkResponseError(response,
                                                                   artifactParent.parent.outputOptions(),
                                                                   "Failed to deploy");
                if ( errorCode >= 0) {
                return errorCode;}
                var message = "Deployed " + coordinates + "\n  File: " + jarPath + "\n  Size: " + size + " bytes";
                return OutputFormatter.printAction(response, artifactParent.parent.outputOptions(), message);
            }
        }

        @Command(name = "push", description = "Push blueprint and its slices from local Maven repository to cluster")
        static class PushArtifactCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ArtifactCommand artifactParent;

            @Parameters(index = "0", description = "Blueprint coordinates (group:artifact:version)")
            private String coordinates;

            /// Artifact descriptor for push operations.
            private record ArtifactDescriptor(String groupId,
                                              String artifactId,
                                              String version,
                                              String label,
                                              Path localPath){}

            @Override
            @SuppressWarnings("JBCT-SEQ-01")
            public Integer call() {
                var parts = coordinates.split(":");
                if ( parts.length != 3) {
                    System.err.println("Invalid coordinates format. Expected: group:artifact:version");
                    return ExitCode.ERROR;
                }
                var groupId = parts[0];
                var artifactId = parts[1];
                var version = parts[2];
                var blueprintPath = findBlueprintJar(groupId, artifactId, version);
                if ( !Files.exists(blueprintPath)) {
                    System.err.println("Blueprint JAR not found in local Maven repository: " + blueprintPath);
                    return ExitCode.ERROR;
                }
                return pushBlueprintWithSlices(groupId, artifactId, version, blueprintPath);
            }

            @SuppressWarnings("JBCT-SEQ-01")
            private Integer pushBlueprintWithSlices(String groupId,
                                                    String artifactId,
                                                    String version,
                                                    Path blueprintPath) {
                var sliceDescriptors = readSliceDescriptors(blueprintPath);
                if ( sliceDescriptors == null) {
                return ExitCode.ERROR;}
                var allArtifacts = buildArtifactList(groupId, artifactId, version, blueprintPath, sliceDescriptors);
                return pushAllArtifacts(artifactId, allArtifacts);
            }

            private List<ArtifactDescriptor> readSliceDescriptors(Path blueprintPath) {
                return readBlueprintToml(blueprintPath).flatMap(PushArtifactCommand::parseSliceCoordinates)
                                        .fold(cause -> reportParseError(cause),
                                              descriptors -> descriptors);
            }

            @SuppressWarnings("JBCT-SEQ-01")
            private List<ArtifactDescriptor> buildArtifactList(String groupId,
                                                               String artifactId,
                                                               String version,
                                                               Path blueprintPath,
                                                               List<ArtifactDescriptor> sliceDescriptors) {
                var all = new ArrayList<ArtifactDescriptor>();
                var blueprintLabel = groupId + ":" + artifactId + ":" + version + ":blueprint";
                all.add(new ArtifactDescriptor(groupId, artifactId, version, blueprintLabel, blueprintPath));
                all.addAll(sliceDescriptors);
                return List.copyOf(all);
            }

            @SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01"})
            private Integer pushAllArtifacts(String artifactId, List<ArtifactDescriptor> artifacts) {
                System.out.println("Pushing " + artifactId + " blueprint (" + artifacts.size() + " artifacts):");
                for ( var artifact : artifacts) {
                    var result = pushSingleArtifact(artifact);
                    if ( result != ExitCode.SUCCESS) {
                    return result;}
                }
                System.out.println("All artifacts pushed successfully.");
                return ExitCode.SUCCESS;
            }

            @SuppressWarnings({"JBCT-UTIL-02", "JBCT-SEQ-01"})
            private Integer pushSingleArtifact(ArtifactDescriptor descriptor) {
                if ( !Files.exists(descriptor.localPath())) {
                    System.err.println("  x " + descriptor.label() + " (not found: " + descriptor.localPath() + ")");
                    return ExitCode.ERROR;
                }
                try {
                    var content = Files.readAllBytes(descriptor.localPath());
                    var repoPath = buildRepoPath(descriptor);
                    var response = artifactParent.parent.putToNode(repoPath, content, "application/java-archive");
                    var errorCode = OutputFormatter.checkResponseError(response,
                                                                       artifactParent.parent.outputOptions(),
                                                                       "Failed to push");
                    if ( errorCode >= 0) {
                    return errorCode;}
                    var sizeKb = content.length / 1024;
                    System.out.println("  + " + descriptor.label() + " (" + sizeKb + "KB)");
                    return ExitCode.SUCCESS;
                }


























                catch (IOException e) {
                    System.err.println("  x " + descriptor.label() + " (error: " + e.getMessage() + ")");
                    return ExitCode.ERROR;
                }
            }

            private static String buildRepoPath(ArtifactDescriptor descriptor) {
                return buildArtifactPath(descriptor.groupId(), descriptor.artifactId(), descriptor.version());
            }

            @SuppressWarnings("JBCT-SEQ-01")
            private static Result<String> readBlueprintToml(Path jarPath) {
                try (var jar = new JarFile(jarPath.toFile())) {
                    var entry = jar.getEntry("META-INF/blueprint.toml");
                    if ( entry == null) {
                    return MISSING_BLUEPRINT_TOML.result();}
                    return Result.success(new String(jar.getInputStream(entry).readAllBytes()));
                }


























                catch (IOException e) {
                    return new ReadBlueprintFailed(e.getMessage()).result();
                }
            }

            private static Result<List<ArtifactDescriptor>> parseSliceCoordinates(String tomlContent) {
                return TomlParser.parse(tomlContent).flatMap(PushArtifactCommand::extractSliceDescriptors);
            }

            private static Result<List<ArtifactDescriptor>> extractSliceDescriptors(TomlDocument doc) {
                return doc.getTableArray("slices").toResult(MISSING_SLICES_SECTION)
                                        .map(PushArtifactCommand::mapSliceTables);
            }

            @SuppressWarnings("JBCT-PAT-01")
            private static List<ArtifactDescriptor> mapSliceTables(List<Map<String, Object>> tables) {
                var descriptors = new ArrayList<ArtifactDescriptor>();
                for ( var table : tables) {
                    var artifact = String.valueOf(table.getOrDefault("artifact", ""));
                    var sliceParts = artifact.split(":");
                    if ( sliceParts.length == 3) {
                        var path = findSliceJar(sliceParts[0], sliceParts[1], sliceParts[2]);
                        descriptors.add(new ArtifactDescriptor(sliceParts[0],
                                                               sliceParts[1],
                                                               sliceParts[2],
                                                               artifact,
                                                               path));
                    }
                }
                return List.copyOf(descriptors);
            }

            @SuppressWarnings("JBCT-RET-03")
            private static List<ArtifactDescriptor> reportParseError(Cause cause) {
                System.err.println("Failed to read blueprint: " + cause.message());
                return null;
            }

            private static Path findBlueprintJar(String groupId, String artifactId, String version) {
                var m2Home = System.getProperty("user.home") + "/.m2/repository";
                return Path.of(m2Home,
                               groupId.replace('.', '/'),
                               artifactId,
                               version,
                               artifactId + "-" + version + "-blueprint.jar");
            }

            private static Path findSliceJar(String groupId, String artifactId, String version) {
                var m2Home = System.getProperty("user.home") + "/.m2/repository";
                return Path.of(m2Home,
                               groupId.replace('.', '/'),
                               artifactId,
                               version,
                               artifactId + "-" + version + ".jar");
            }

            private static final Cause MISSING_BLUEPRINT_TOML = Causes.cause("META-INF/blueprint.toml not found in JAR");
            private static final Cause MISSING_SLICES_SECTION = Causes.cause("No [[slices]] section found in blueprint.toml");

            record ReadBlueprintFailed(String detail) implements Cause {
                @Override public String message() {
                    return "Failed to read blueprint JAR: " + detail;
                }
            }
        }

        @Command(name = "list", description = "List artifacts in the repository")
        static class ListArtifactsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ArtifactCommand artifactParent;

            @Override public Integer call() {
                var response = artifactParent.parent.fetchFromNode("/repository/artifacts");
                return OutputFormatter.printQuery(response, artifactParent.parent.outputOptions());
            }
        }

        @Command(name = "versions", description = "List versions of an artifact")
        static class VersionsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ArtifactCommand artifactParent;

            @Parameters(index = "0", description = "Artifact (group:artifact)")
            private String artifact;

            @Override public Integer call() {
                var parts = artifact.split(":");
                if ( parts.length != 2) {
                    System.err.println("Invalid artifact format. Expected: group:artifact");
                    return ExitCode.ERROR;
                }
                var path = "/repository/" + parts[0].replace('.', '/') + "/" + parts[1] + "/maven-metadata.xml";
                var response = artifactParent.parent.fetchFromNode(path);
                var errorCode = OutputFormatter.checkResponseError(response,
                                                                   artifactParent.parent.outputOptions(),
                                                                   "Failed to get versions");
                if ( errorCode >= 0) {
                return errorCode;}
                return OutputFormatter.printQuery(response, artifactParent.parent.outputOptions());
            }
        }

        @Command(name = "info", description = "Show artifact metadata")
        static class InfoCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ArtifactCommand artifactParent;

            @Parameters(index = "0", description = "Artifact coordinates (group:artifact:version)")
            private String coordinates;

            @Override public Integer call() {
                var parts = coordinates.split(":");
                if ( parts.length != 3) {
                    System.err.println("Invalid coordinates format. Expected: group:artifact:version");
                    return ExitCode.ERROR;
                }
                var path = "/repository/info/" + parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2];
                var response = artifactParent.parent.fetchFromNode(path);
                return OutputFormatter.printQuery(response, artifactParent.parent.outputOptions());
            }
        }

        @Command(name = "delete", description = "Delete an artifact from the repository")
        static class DeleteCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ArtifactCommand artifactParent;

            @Parameters(index = "0", description = "Artifact coordinates (group:artifact:version)")
            private String coordinates;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var parts = coordinates.split(":");
                if ( parts.length != 3) {
                    System.err.println("Invalid coordinates format. Expected: group:artifact:version");
                    return ExitCode.ERROR;
                }
                var path = "/repository/" + parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2];
                var response = artifactParent.parent.deleteFromNode(path);
                var errorCode = OutputFormatter.checkResponseError(response,
                                                                   artifactParent.parent.outputOptions(),
                                                                   "Failed to delete");
                if ( errorCode >= 0) {
                return errorCode;}
                return OutputFormatter.printAction(response,
                                                   artifactParent.parent.outputOptions(),
                                                   "Deleted " + coordinates);
            }
        }

        @Command(name = "metrics", description = "Show artifact storage metrics")
        static class MetricsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ArtifactCommand artifactParent;

            @Override public Integer call() {
                var response = artifactParent.parent.fetchFromNode("/api/artifact-metrics");
                return OutputFormatter.printQuery(response, artifactParent.parent.outputOptions());
            }
        }

        private static String buildArtifactPath(String groupId, String artifactId, String version) {
            return "/repository/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
        }
    }

    @Command(name = "blueprint",
    description = "Blueprint management",
    subcommands = {BlueprintCommand.ApplyCommand.class, BlueprintCommand.ListCommand.class, BlueprintCommand.GetCommand.class, BlueprintCommand.DeleteCommand.class, BlueprintCommand.StatusCommand.class, BlueprintCommand.ValidateCommand.class, BlueprintCommand.DeployArtifactCommand.class, BlueprintCommand.UploadCommand.class})
    static class BlueprintCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        private static final OutputFormatter.TableSpec BLUEPRINT_LIST_TABLE = new OutputFormatter.TableSpec("Blueprints",
                                                                                                            List.of(new OutputFormatter.Column("ID",
                                                                                                                                               "id",
                                                                                                                                               40),
                                                                                                                    new OutputFormatter.Column("SLICES",
                                                                                                                                               "sliceCount",
                                                                                                                                               10)),
                                                                                                            "blueprints");

        private static final OutputFormatter.TableSpec BLUEPRINT_SLICES_TABLE = new OutputFormatter.TableSpec("Slices",
                                                                                                              List.of(new OutputFormatter.Column("ARTIFACT",
                                                                                                                                                 "artifact",
                                                                                                                                                 50),
                                                                                                                      new OutputFormatter.Column("INSTANCES",
                                                                                                                                                 "instances",
                                                                                                                                                 10),
                                                                                                                      new OutputFormatter.Column("TYPE",
                                                                                                                                                 "isDependency",
                                                                                                                                                 12)),
                                                                                                              "slices");

        private static final OutputFormatter.TableSpec BLUEPRINT_STATUS_TABLE = new OutputFormatter.TableSpec("Slice Status",
                                                                                                              List.of(new OutputFormatter.Column("ARTIFACT",
                                                                                                                                                 "artifact",
                                                                                                                                                 50),
                                                                                                                      new OutputFormatter.Column("TARGET",
                                                                                                                                                 "targetInstances",
                                                                                                                                                 8),
                                                                                                                      new OutputFormatter.Column("ACTIVE",
                                                                                                                                                 "activeInstances",
                                                                                                                                                 8),
                                                                                                                      new OutputFormatter.Column("STATUS",
                                                                                                                                                 "status",
                                                                                                                                                 12)),
                                                                                                              "slices");

        @Contract @Override public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "apply", description = "Apply a blueprint file to the cluster")
        static class ApplyCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Path to the blueprint file (.toml)")
            private Path blueprintPath;

            @Override
            @SuppressWarnings({"JBCT-UTIL-02", "JBCT-SEQ-01"})
            public Integer call() {
                try {
                    if ( !Files.exists(blueprintPath)) {
                        System.err.println("Blueprint file not found: " + blueprintPath);
                        return ExitCode.ERROR;
                    }
                    var content = Files.readString(blueprintPath);
                    var response = blueprintParent.parent.postToNode("/api/blueprint", content);
                    var errorCode = OutputFormatter.checkResponseError(response,
                                                                       blueprintParent.parent.outputOptions(),
                                                                       "Failed to apply blueprint");
                    if ( errorCode >= 0) {
                    return errorCode;}
                    return OutputFormatter.printQuery(response, blueprintParent.parent.outputOptions());
                }


























                catch (IOException e) {
                    System.err.println("Error reading blueprint file: " + e.getMessage());
                    return ExitCode.ERROR;
                }
            }
        }

        @Command(name = "list", description = "List all deployed blueprints")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private BlueprintCommand blueprintParent;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var response = blueprintParent.parent.fetchFromNode("/api/blueprints");
                var output = blueprintParent.parent.outputOptions();
                var errorCode = OutputFormatter.checkResponseError(response, output, "Failed to list blueprints");
                if ( errorCode >= 0) {
                return errorCode;}
                return OutputFormatter.printQuery(response, output, BLUEPRINT_LIST_TABLE);
            }
        }

        @Command(name = "get", description = "Get blueprint details")
        static class GetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Blueprint ID (group:artifact:version)")
            private String blueprintId;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var response = blueprintParent.parent.fetchFromNode("/api/blueprint/" + blueprintId);
                var output = blueprintParent.parent.outputOptions();
                var errorCode = OutputFormatter.checkResponseError(response, output, "Failed to get blueprint");
                if ( errorCode >= 0) {
                return errorCode;}
                return OutputFormatter.printQuery(response, output, BLUEPRINT_SLICES_TABLE);
            }
        }

        @Command(name = "delete", description = "Delete a blueprint from the cluster")
        static class DeleteCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Blueprint ID (group:artifact:version)")
            private String blueprintId;

            @CommandLine.Option(names = {"--force", "-f"}, description = "Skip confirmation prompt")
            private boolean force;

            @Override
            @SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
            public Integer call() {
                if ( !force) {
                    var confirmed = confirmDeletion(blueprintId);
                    if ( !confirmed) {
                    return ExitCode.SUCCESS;}
                }
                var response = blueprintParent.parent.deleteFromNode("/api/blueprint/" + blueprintId);
                var errorCode = OutputFormatter.checkResponseError(response,
                                                                   blueprintParent.parent.outputOptions(),
                                                                   "Failed to delete blueprint");
                if ( errorCode >= 0) {
                return errorCode;}
                return OutputFormatter.printAction(response,
                                                   blueprintParent.parent.outputOptions(),
                                                   "Deleted blueprint: " + blueprintId);
            }

            @SuppressWarnings("JBCT-SEQ-01")
            private static boolean confirmDeletion(String id) {
                System.out.print("Are you sure you want to delete blueprint '" + id + "'? (y/N) ");
                try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
                    return option(reader.readLine()).map(String::trim)
                                 .filter(s -> s.equalsIgnoreCase("y"))
                                 .isPresent();
                }


























                catch (IOException e) {
                    System.err.println("Error reading input: " + e.getMessage());
                    return false;
                }
            }
        }

        @Command(name = "status", description = "Show deployment status of a blueprint")
        static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Blueprint ID (group:artifact:version)")
            private String blueprintId;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var response = blueprintParent.parent.fetchFromNode("/api/blueprint/" + blueprintId + "/status");
                var output = blueprintParent.parent.outputOptions();
                var errorCode = OutputFormatter.checkResponseError(response, output, "Failed to get blueprint status");
                if ( errorCode >= 0) {
                return errorCode;}
                return OutputFormatter.printQuery(response, output, BLUEPRINT_STATUS_TABLE);
            }
        }

        @Command(name = "validate", description = "Validate a blueprint file without deploying")
        static class ValidateCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Path to the blueprint file (.toml)")
            private Path blueprintPath;

            @Override
            @SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
            public Integer call() {
                try {
                    if ( !Files.exists(blueprintPath)) {
                        System.err.println("Blueprint file not found: " + blueprintPath);
                        return ExitCode.ERROR;
                    }
                    var content = Files.readString(blueprintPath);
                    var response = blueprintParent.parent.postToNode("/api/blueprint/validate", content);
                    if ( response.contains("\"valid\":false")) {
                    return reportValidationFailure(response);}
                    return OutputFormatter.printQuery(response, blueprintParent.parent.outputOptions());
                }


























                catch (IOException e) {
                    System.err.println("Error reading blueprint file: " + e.getMessage());
                    return ExitCode.ERROR;
                }
            }

            @SuppressWarnings({"JBCT-UTIL-02", "JBCT-SEQ-01"})
            private static Integer reportValidationFailure(String response) {
                System.err.println("Validation FAILED");
                var errors = extractJsonStringArray(response, "errors");
                if ( !errors.isEmpty()) {
                    System.err.println("Errors:");
                    errors.forEach(error -> System.err.println("  - " + error));
                }
                return ExitCode.ERROR;
            }

            @SuppressWarnings("JBCT-PAT-01")
            private static List<String> extractJsonStringArray(String json, String key) {
                var result = new ArrayList<String>();
                var keyPattern = "\"" + key + "\":";
                var start = json.indexOf(keyPattern);
                if ( start == - 1) return result;
                var arrayStart = json.indexOf('[', start);
                var arrayEnd = findMatchingBracket(json, arrayStart);
                if ( arrayStart == - 1 || arrayEnd == - 1) return result;
                var arrayContent = json.substring(arrayStart + 1, arrayEnd);
                parseStringElements(arrayContent, result);
                return result;
            }

            @SuppressWarnings("JBCT-PAT-01")
            private static void parseStringElements(String arrayContent, List<String> result) {
                var inString = false;
                var stringStart = - 1;
                for ( int i = 0; i < arrayContent.length(); i++) {
                    var c = arrayContent.charAt(i);
                    if ( c == '"' && (i == 0 || arrayContent.charAt(i - 1) != '\\')) {
                    if ( !inString) {
                        inString = true;
                        stringStart = i + 1;
                    } else


























                    {
                        result.add(arrayContent.substring(stringStart, i));
                        inString = false;
                    }}
                }
            }

            @SuppressWarnings("JBCT-PAT-01")
            private static int findMatchingBracket(String json, int openIndex) {
                if ( openIndex == - 1 || openIndex >= json.length()) return - 1;
                var openChar = json.charAt(openIndex);
                var closeChar = openChar == '['
                                ? ']'
                                : '}';
                var depth = 1;
                var inString = false;
                for ( int i = openIndex + 1; i < json.length(); i++) {
                    var c = json.charAt(i);
                    if ( c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                    inString = !inString;} else
                    if ( !inString) {
                    if ( c == openChar) depth++;else if ( c == closeChar) {
                        depth--;
                        if ( depth == 0) return i;
                    }}
                }
                return - 1;
            }
        }

        @Command(name = "deploy", description = "Deploy a blueprint from an artifact in the cluster repository")
        @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01"})
        static class DeployArtifactCommand implements Callable<Integer> {
            private static final int POLL_INTERVAL_MS = 2000;

            @CommandLine.ParentCommand private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Artifact coordinates (groupId:artifactId:version)")
            private String coords;

            @CommandLine.Option(names = "--wait", description = "Wait for deployment to complete")
            private boolean waitForCompletion;

            @CommandLine.Option(names = "--timeout", description = "Timeout in seconds when waiting")
            private int timeoutSeconds = 0;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var fullCoords = coords.split(":").length == 3
                                 ? coords + ":blueprint"
                                 : coords;
                var body = "{\"artifact\":\"" + escapeJsonValue(fullCoords) + "\"}";
                var response = blueprintParent.parent.postToNode("/api/blueprint/deploy", body);
                var errorCode = OutputFormatter.checkResponseError(response,
                                                                   blueprintParent.parent.outputOptions(),
                                                                   "Failed to deploy blueprint");
                if ( errorCode >= 0) {
                return errorCode;}
                var printResult = OutputFormatter.printQuery(response, blueprintParent.parent.outputOptions());
                if ( printResult != ExitCode.SUCCESS || !waitForCompletion) {
                return printResult;}
                return validateTimeoutAndPoll();
            }

            private int validateTimeoutAndPoll() {
                if ( timeoutSeconds <= 0) {
                    System.err.println("--timeout is required when using --wait");
                    return ExitCode.ERROR;
                }
                return pollUntilDeployed();
            }

            @SuppressWarnings("JBCT-EX-01")
            private int pollUntilDeployed() {
                System.out.printf("Waiting for %s deployment to complete (timeout: %ds)...%n", coords, timeoutSeconds);
                var deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
                while ( System.currentTimeMillis() < deadline) {
                    var status = queryDeploymentStatus();
                    if ( isDeploymentComplete(status)) {
                        System.out.printf("Deployment complete: %s is active.%n", coords);
                        return ExitCode.SUCCESS;
                    }
                    System.out.printf("  Deployment status: %s%n", status);
                    sleepQuietly();
                }
                System.err.printf("Timeout: %s deployment did not complete within %ds.%n", coords, timeoutSeconds);
                return ExitCode.TIMEOUT;
            }

            private static boolean isDeploymentComplete(String status) {
                return "ACTIVE".equalsIgnoreCase(status) || "DEPLOYED".equalsIgnoreCase(status);
            }

            private String queryDeploymentStatus() {
                var response = blueprintParent.parent.fetchFromNode("/api/slices");
                if ( response.contains("\"error\"")) {
                return "UNKNOWN";}
                return response.contains(coords)
                       ? "ACTIVE"
                       : "PENDING";
            }

            @SuppressWarnings("JBCT-EX-01")
            private static void sleepQuietly() {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                }


























                catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Command(name = "upload", description = "Upload a blueprint JAR file to the cluster")
        static class UploadCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Path to the blueprint JAR file")
            private Path blueprintJarPath;

            @CommandLine.Option(names = {"-g", "--group"}, description = "Group ID", required = true)
            private String groupId;

            @CommandLine.Option(names = {"-a", "--artifact"}, description = "Artifact ID", required = true)
            private String artifactId;

            @CommandLine.Option(names = {"-v", "--version"}, description = "Version", required = true)
            private String version;

            @Override
            @SuppressWarnings({"JBCT-UTIL-02", "JBCT-SEQ-01"})
            public Integer call() {
                try {
                    if ( !Files.exists(blueprintJarPath)) {
                        System.err.println("File not found: " + blueprintJarPath);
                        return ExitCode.ERROR;
                    }
                    var fileSize = Files.size(blueprintJarPath);
                    if ( fileSize > 500 * 1024 * 1024) {
                        System.err.println("File too large: " + fileSize + " bytes (max 500MB)");
                        return ExitCode.ERROR;
                    }
                    var content = Files.readAllBytes(blueprintJarPath);
                    var coordinates = groupId + ":" + artifactId + ":" + version;
                    var repoPath = "/api/repository/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + "-blueprint.jar";
                    var uploadResponse = blueprintParent.parent.putToNode(repoPath, content, "application/java-archive");
                    var uploadError = OutputFormatter.checkResponseError(uploadResponse,
                                                                         blueprintParent.parent.outputOptions(),
                                                                         "Failed to upload");
                    if ( uploadError >= 0) {
                    return uploadError;}
                    var deployBody = "{\"artifact\":\"" + escapeJsonValue(coordinates) + "\"}";
                    var deployResponse = blueprintParent.parent.postToNode("/api/blueprint/deploy", deployBody);
                    var deployError = OutputFormatter.checkResponseError(deployResponse,
                                                                         blueprintParent.parent.outputOptions(),
                                                                         "Failed to deploy");
                    if ( deployError >= 0) {
                    return deployError;}
                    return OutputFormatter.printAction(deployResponse,
                                                       blueprintParent.parent.outputOptions(),
                                                       "Uploaded and deployed " + coordinates);
                }


























                catch (IOException e) {
                    System.err.println("Error reading file: " + e.getMessage());
                    return ExitCode.ERROR;
                }
            }
        }
    }

    // ===== Invocation Metrics Commands =====
    @Command(name = "invocation-metrics",
    description = "Invocation metrics management",
    subcommands = {InvocationMetricsCommand.ListCommand.class, InvocationMetricsCommand.SlowCommand.class, InvocationMetricsCommand.StrategyCommand.class})
    static class InvocationMetricsCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            // Default: show all metrics
            var response = parent.fetchFromNode("/api/invocation-metrics");
            OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "list", description = "List all invocation metrics")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private InvocationMetricsCommand metricsParent;

            @Override public Integer call() {
                var response = metricsParent.parent.fetchFromNode("/api/invocation-metrics");
                return OutputFormatter.printQuery(response, metricsParent.parent.outputOptions());
            }
        }

        @Command(name = "slow", description = "Show slow invocations")
        static class SlowCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private InvocationMetricsCommand metricsParent;

            @Override public Integer call() {
                var response = metricsParent.parent.fetchFromNode("/api/invocation-metrics/slow");
                return OutputFormatter.printQuery(response, metricsParent.parent.outputOptions());
            }
        }

        @Command(name = "strategy", description = "Show or set threshold strategy")
        static class StrategyCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private InvocationMetricsCommand metricsParent;

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
                return ExitCode.SUCCESS;
            }

            private void showCurrentStrategy() {
                var response = metricsParent.parent.fetchFromNode("/api/invocation-metrics/strategy");
                OutputFormatter.printQuery(response, metricsParent.parent.outputOptions());
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private void setStrategy(String strategyType) {
                String body;
                switch ( strategyType.toLowerCase()) {
                    case "fixed" -> body = buildFixedStrategyBody();
                    case "adaptive" -> body = buildAdaptiveStrategyBody();
                    default -> {
                        System.err.println("Unknown strategy type: " + strategyType);
                        System.err.println("Supported: fixed, adaptive");
                        return;
                    }
                }
                var response = metricsParent.parent.postToNode("/api/invocation-metrics/strategy", body);
                if ( OutputFormatter.isErrorResponse(response) || response.contains("Strategy change")) {
                    System.err.println("Error: Strategy changes at runtime are not yet supported.");
                    System.err.println("The strategy is configured at node startup. Use the GET command to view the current strategy.");
                } else


























                {
                OutputFormatter.printQuery(response, metricsParent.parent.outputOptions());}
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
    subcommands = {ControllerCommand.ConfigCommand.class, ControllerCommand.StatusCommand.class, ControllerCommand.EvaluateCommand.class})
    static class ControllerCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "config", description = "Show or update controller configuration")
        static class ConfigCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ControllerCommand controllerParent;

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
                if ( hasConfigUpdate()) {
                    var body = buildConfigUpdateBody();
                    var response = controllerParent.parent.postToNode("/api/controller/config", body);
                    return OutputFormatter.printQuery(response, controllerParent.parent.outputOptions());
                } else


























                {
                    // Show current config
                    var response = controllerParent.parent.fetchFromNode("/api/controller/config");
                    return OutputFormatter.printQuery(response, controllerParent.parent.outputOptions());
                }
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
            @CommandLine.ParentCommand private ControllerCommand controllerParent;

            @Override public Integer call() {
                var response = controllerParent.parent.fetchFromNode("/api/controller/status");
                return OutputFormatter.printQuery(response, controllerParent.parent.outputOptions());
            }
        }

        @Command(name = "evaluate", description = "Force controller evaluation")
        static class EvaluateCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ControllerCommand controllerParent;

            @Override public Integer call() {
                var response = controllerParent.parent.postToNode("/api/controller/evaluate", "{}");
                return OutputFormatter.printAction(response,
                                                   controllerParent.parent.outputOptions(),
                                                   "Controller evaluation triggered");
            }
        }
    }

    // ===== Alerts Commands =====
    @Command(name = "alerts",
    description = "Alert management",
    subcommands = {AlertsCommand.ListCommand.class, AlertsCommand.ActiveCommand.class, AlertsCommand.HistoryCommand.class, AlertsCommand.ClearCommand.class})
    static class AlertsCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            // Default: show all alerts
            var response = parent.fetchFromNode("/api/alerts");
            OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "list", description = "List all alerts")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private AlertsCommand alertsParent;

            @Override public Integer call() {
                var response = alertsParent.parent.fetchFromNode("/api/alerts");
                return OutputFormatter.printQuery(response, alertsParent.parent.outputOptions());
            }
        }

        @Command(name = "active", description = "Show active alerts only")
        static class ActiveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private AlertsCommand alertsParent;

            @Override public Integer call() {
                var response = alertsParent.parent.fetchFromNode("/api/alerts/active");
                return OutputFormatter.printQuery(response, alertsParent.parent.outputOptions());
            }
        }

        @Command(name = "history", description = "Show alert history")
        static class HistoryCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private AlertsCommand alertsParent;

            @Override public Integer call() {
                var response = alertsParent.parent.fetchFromNode("/api/alerts/history");
                return OutputFormatter.printQuery(response, alertsParent.parent.outputOptions());
            }
        }

        @Command(name = "clear", description = "Clear all active alerts")
        static class ClearCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private AlertsCommand alertsParent;

            @Override public Integer call() {
                var response = alertsParent.parent.postToNode("/api/alerts/clear", "{}");
                return OutputFormatter.printAction(response, alertsParent.parent.outputOptions(), "Alerts cleared");
            }
        }
    }

    // ===== Thresholds Commands =====
    @Command(name = "thresholds",
    description = "Alert threshold management",
    subcommands = {ThresholdsCommand.ListCommand.class, ThresholdsCommand.SetCommand.class, ThresholdsCommand.RemoveCommand.class})
    static class ThresholdsCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            // Default: show all thresholds
            var response = parent.fetchFromNode("/api/thresholds");
            OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "list", description = "List all thresholds")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ThresholdsCommand thresholdsParent;

            @Override public Integer call() {
                var response = thresholdsParent.parent.fetchFromNode("/api/thresholds");
                return OutputFormatter.printQuery(response, thresholdsParent.parent.outputOptions());
            }
        }

        @Command(name = "set", description = "Set a threshold")
        static class SetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ThresholdsCommand thresholdsParent;

            @Parameters(index = "0", description = "Metric name")
            private String metric;

            @CommandLine.Option(names = {"-w", "--warning"}, description = "Warning threshold", required = true)
            private double warning;

            @CommandLine.Option(names = {"-c", "--critical"}, description = "Critical threshold", required = true)
            private double critical;

            @Override public Integer call() {
                var body = "{\"metric\":\"" + metric + "\",\"warning\":" + warning + ",\"critical\":" + critical + "}";
                var response = thresholdsParent.parent.postToNode("/api/thresholds", body);
                return OutputFormatter.printAction(response,
                                                   thresholdsParent.parent.outputOptions(),
                                                   "Threshold set for " + metric);
            }
        }

        @Command(name = "remove", description = "Remove a threshold")
        static class RemoveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ThresholdsCommand thresholdsParent;

            @Parameters(index = "0", description = "Metric name")
            private String metric;

            @Override public Integer call() {
                var response = thresholdsParent.parent.deleteFromNode("/api/thresholds/" + metric);
                return OutputFormatter.printAction(response,
                                                   thresholdsParent.parent.outputOptions(),
                                                   "Threshold removed for " + metric);
            }
        }
    }

    // ===== Traces Commands =====
    @Command(name = "traces",
    description = "View distributed invocation traces",
    subcommands = {TracesCommand.ListCommand.class, TracesCommand.GetCommand.class, TracesCommand.StatsCommand.class})
    static class TracesCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "list", description = "List recent traces")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private TracesCommand tracesParent;

            @CommandLine.Option(names = {"-l", "--limit"}, description = "Max traces", defaultValue = "100")
            private int limit;

            @CommandLine.Option(names = {"-m", "--method"}, description = "Filter by method")
            private String method;

            @CommandLine.Option(names = {"-s", "--status"}, description = "Filter by status (SUCCESS/FAILURE)")
            private String status;

            @Override public Integer call() {
                var path = buildTracesListPath();
                var response = tracesParent.parent.fetchFromNode(path);
                return OutputFormatter.printQuery(response, tracesParent.parent.outputOptions());
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private String buildTracesListPath() {
                var sb = new StringBuilder("/api/traces?limit=").append(limit);
                option(method).onPresent(m -> sb.append("&method=").append(m));
                option(status).onPresent(s -> sb.append("&status=").append(s));
                return sb.toString();
            }
        }

        @Command(name = "get", description = "Get traces for a request ID")
        static class GetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private TracesCommand tracesParent;

            @Parameters(index = "0", description = "Request ID")
            private String requestId;

            @Override public Integer call() {
                var response = tracesParent.parent.fetchFromNode("/api/traces/" + requestId);
                return OutputFormatter.printQuery(response, tracesParent.parent.outputOptions());
            }
        }

        @Command(name = "stats", description = "Show trace statistics")
        static class StatsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private TracesCommand tracesParent;

            @Override public Integer call() {
                var response = tracesParent.parent.fetchFromNode("/api/traces/stats");
                return OutputFormatter.printQuery(response, tracesParent.parent.outputOptions());
            }
        }
    }

    // ===== Observability Commands =====
    @Command(name = "observability",
    description = "Manage observability configuration",
    subcommands = {ObservabilityCommand.DepthListCommand.class, ObservabilityCommand.DepthSetCommand.class, ObservabilityCommand.DepthRemoveCommand.class})
    static class ObservabilityCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "depth", description = "List depth overrides")
        static class DepthListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ObservabilityCommand obsParent;

            @Override public Integer call() {
                var response = obsParent.parent.fetchFromNode("/api/observability/depth");
                return OutputFormatter.printQuery(response, obsParent.parent.outputOptions());
            }
        }

        @Command(name = "depth-set", description = "Set depth threshold for a method")
        static class DepthSetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ObservabilityCommand obsParent;

            @Parameters(index = "0", description = "Artifact#method (e.g., org.example:my-slice:1.0.0#processOrder)")
            private String target;

            @Parameters(index = "1", description = "Depth threshold")
            private int depthThreshold;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var hashIndex = target.indexOf('#');
                if ( hashIndex == - 1) {
                    System.err.println("Invalid format. Use: artifact#method");
                    return ExitCode.ERROR;
                }
                var artifact = target.substring(0, hashIndex);
                var method = target.substring(hashIndex + 1);
                var body = buildDepthSetBody(artifact, method);
                var response = obsParent.parent.postToNode("/api/observability/depth", body);
                return OutputFormatter.printAction(response,
                                                   obsParent.parent.outputOptions(),
                                                   "Depth threshold set for " + target);
            }

            private String buildDepthSetBody(String artifact, String method) {
                return "{\"artifact\":\"" + artifact + "\",\"method\":\"" + method + "\",\"depthThreshold\":" + depthThreshold + "}";
            }
        }

        @Command(name = "depth-remove", description = "Remove depth override")
        static class DepthRemoveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ObservabilityCommand obsParent;

            @Parameters(index = "0", description = "Artifact#method")
            private String target;

            @Override public Integer call() {
                var hashIndex = target.indexOf('#');
                if ( hashIndex == - 1) {
                    System.err.println("Invalid format. Use: artifact#method");
                    return ExitCode.ERROR;
                }
                var artifact = target.substring(0, hashIndex);
                var method = target.substring(hashIndex + 1);
                var response = obsParent.parent.deleteFromNode("/api/observability/depth/" + artifact + "/" + method);
                return OutputFormatter.printAction(response,
                                                   obsParent.parent.outputOptions(),
                                                   "Depth override removed for " + target);
            }
        }
    }

    // ===== Logging Commands =====
    @Command(name = "logging",
    description = "Runtime log level management",
    subcommands = {LoggingCommand.ListCommand.class, LoggingCommand.SetCommand.class, LoggingCommand.ResetCommand.class})
    static class LoggingCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            var response = parent.fetchFromNode("/api/logging/levels");
            OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "list", description = "List runtime-configured log levels")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private LoggingCommand loggingParent;

            @Override public Integer call() {
                var response = loggingParent.parent.fetchFromNode("/api/logging/levels");
                return OutputFormatter.printQuery(response, loggingParent.parent.outputOptions());
            }
        }

        @Command(name = "set", description = "Set log level for a logger")
        static class SetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private LoggingCommand loggingParent;

            @Parameters(index = "0", description = "Logger name (e.g., org.pragmatica.aether)")
            private String logger;

            @Parameters(index = "1", description = "Log level (TRACE, DEBUG, INFO, WARN, ERROR)")
            private String level;

            @Override public Integer call() {
                var body = "{\"logger\":\"" + logger + "\",\"level\":\"" + level.toUpperCase() + "\"}";
                var response = loggingParent.parent.postToNode("/api/logging/levels", body);
                return OutputFormatter.printAction(response,
                                                   loggingParent.parent.outputOptions(),
                                                   "Log level set: " + logger + " = " + level.toUpperCase());
            }
        }

        @Command(name = "reset", description = "Reset logger to config default")
        static class ResetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private LoggingCommand loggingParent;

            @Parameters(index = "0", description = "Logger name")
            private String logger;

            @Override public Integer call() {
                var response = loggingParent.parent.deleteFromNode("/api/logging/levels/" + logger);
                return OutputFormatter.printAction(response,
                                                   loggingParent.parent.outputOptions(),
                                                   "Logger reset: " + logger);
            }
        }
    }

    // ===== Config Commands =====
    @Command(name = "config",
    description = "Dynamic configuration management",
    subcommands = {ConfigCommand.ListCommand.class, ConfigCommand.OverridesCommand.class, ConfigCommand.SetCommand.class, ConfigCommand.RemoveCommand.class})
    static class ConfigCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            // Default: show all config
            var response = parent.fetchFromNode("/api/config");
            OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "list", description = "Show all configuration (base + overrides)")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ConfigCommand configParent;

            @Override public Integer call() {
                var response = configParent.parent.fetchFromNode("/api/config");
                return OutputFormatter.printQuery(response, configParent.parent.outputOptions());
            }
        }

        @Command(name = "overrides", description = "Show only dynamic overrides from KV store")
        static class OverridesCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ConfigCommand configParent;

            @Override public Integer call() {
                var response = configParent.parent.fetchFromNode("/api/config/overrides");
                return OutputFormatter.printQuery(response, configParent.parent.outputOptions());
            }
        }

        @Command(name = "set", description = "Set a configuration override")
        static class SetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ConfigCommand configParent;

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
                return OutputFormatter.printAction(response,
                                                   configParent.parent.outputOptions(),
                                                   "Config set: " + key + " = " + value);
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private String buildConfigSetBody() {
                var sb = new StringBuilder("{\"key\":\"").append(key)
                                                         .append("\",\"value\":\"")
                                                         .append(value)
                                                         .append("\"");
                option(nodeId).onPresent(id -> sb.append(",\"nodeId\":\"").append(id)
                                                        .append("\""));
                sb.append("}");
                return sb.toString();
            }
        }

        @Command(name = "remove", description = "Remove a configuration override")
        static class RemoveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ConfigCommand configParent;

            @Parameters(index = "0", description = "Configuration key (dot.notation)")
            private String key;

            @CommandLine.Option(names = {"--node"}, description = "Target node ID (omit for cluster-wide)")
            private String nodeId;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var response = fetchRemoveResponse();
                return OutputFormatter.printAction(response,
                                                   configParent.parent.outputOptions(),
                                                   "Config removed: " + key);
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
    subcommands = {ScheduledTasksCommand.ListCommand.class, ScheduledTasksCommand.GetCommand.class, ScheduledTasksCommand.PauseCommand.class, ScheduledTasksCommand.ResumeCommand.class, ScheduledTasksCommand.TriggerCommand.class})
    static class ScheduledTasksCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            // Default: list all scheduled tasks
            var response = parent.fetchFromNode("/api/scheduled-tasks");
            OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "list", description = "List all scheduled tasks")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ScheduledTasksCommand tasksParent;

            @Override public Integer call() {
                var response = tasksParent.parent.fetchFromNode("/api/scheduled-tasks");
                return OutputFormatter.printQuery(response, tasksParent.parent.outputOptions());
            }
        }

        @Command(name = "get", description = "Get scheduled tasks by config section")
        static class GetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ScheduledTasksCommand tasksParent;

            @Parameters(index = "0", description = "Config section name")
            private String configSection;

            @Override public Integer call() {
                var response = tasksParent.parent.fetchFromNode("/api/scheduled-tasks/" + configSection);
                return OutputFormatter.printQuery(response, tasksParent.parent.outputOptions());
            }
        }

        @Command(name = "pause", description = "Pause a scheduled task")
        static class PauseCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ScheduledTasksCommand tasksParent;

            @Parameters(index = "0", description = "Config section")
            private String configSection;

            @Parameters(index = "1", description = "Artifact coordinates (groupId:artifactId:version)")
            private String artifact;

            @Parameters(index = "2", description = "Method name")
            private String method;

            @Override public Integer call() {
                var response = tasksParent.parent.postToNode("/api/scheduled-tasks/" + configSection + "/" + artifact + "/" + method + "/pause",
                                                             "");
                return OutputFormatter.printAction(response,
                                                   tasksParent.parent.outputOptions(),
                                                   "Task paused: " + method);
            }
        }

        @Command(name = "resume", description = "Resume a paused scheduled task")
        static class ResumeCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ScheduledTasksCommand tasksParent;

            @Parameters(index = "0", description = "Config section")
            private String configSection;

            @Parameters(index = "1", description = "Artifact coordinates (groupId:artifactId:version)")
            private String artifact;

            @Parameters(index = "2", description = "Method name")
            private String method;

            @Override public Integer call() {
                var response = tasksParent.parent.postToNode("/api/scheduled-tasks/" + configSection + "/" + artifact + "/" + method + "/resume",
                                                             "");
                return OutputFormatter.printAction(response,
                                                   tasksParent.parent.outputOptions(),
                                                   "Task resumed: " + method);
            }
        }

        @Command(name = "trigger", description = "Manually trigger a scheduled task")
        static class TriggerCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private ScheduledTasksCommand tasksParent;

            @Parameters(index = "0", description = "Config section")
            private String configSection;

            @Parameters(index = "1", description = "Artifact coordinates (groupId:artifactId:version)")
            private String artifact;

            @Parameters(index = "2", description = "Method name")
            private String method;

            @Override public Integer call() {
                var response = tasksParent.parent.postToNode("/api/scheduled-tasks/" + configSection + "/" + artifact + "/" + method + "/trigger",
                                                             "");
                return OutputFormatter.printAction(response,
                                                   tasksParent.parent.outputOptions(),
                                                   "Task triggered: " + method);
            }
        }
    }

    // ===== Events Command =====
    @Command(name = "events", description = "Show cluster events")
    static class EventsCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private AetherCli parent;

        @CommandLine.Option(names = {"--since"}, description = "Show events since ISO-8601 timestamp (e.g. 2024-01-15T10:30:00Z)")
        private String since;

        @Override public Integer call() {
            var path = buildEventsPath();
            var response = parent.fetchFromNode(path);
            return OutputFormatter.printQuery(response, parent.outputOptions());
        }

        private String buildEventsPath() {
            return option(since).map(s -> "/api/events?since=" + s)
                         .or("/api/events");
        }
    }

    // ===== Node Commands =====
    @Command(name = "node",
    description = "Node lifecycle management",
    subcommands = {NodeCommand.LifecycleCommand.class, NodeCommand.DrainCommand.class, NodeCommand.ActivateCommand.class, NodeCommand.ShutdownCommand.class})
    static class NodeCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "lifecycle", description = "Show node lifecycle states")
        static class LifecycleCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private NodeCommand nodeParent;

            @Parameters(index = "0", description = "Node ID (omit to list all)", arity = "0..1")
            private String nodeId;

            @Override public Integer call() {
                return option(nodeId).map(this::showSingleNodeLifecycle)
                             .or(() -> showAllLifecycleStates());
            }

            private Integer showAllLifecycleStates() {
                var response = nodeParent.parent.fetchFromNode("/api/nodes/lifecycle");
                return OutputFormatter.printQuery(response, nodeParent.parent.outputOptions());
            }

            private Integer showSingleNodeLifecycle(String id) {
                var response = nodeParent.parent.fetchFromNode("/api/node/lifecycle/" + id);
                return OutputFormatter.printQuery(response, nodeParent.parent.outputOptions());
            }
        }

        @Command(name = "drain", description = "Drain a node (ON_DUTY -> DRAINING)")
        static class DrainCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private NodeCommand nodeParent;

            @Parameters(index = "0", description = "Node ID")
            private String nodeId;

            @Override public Integer call() {
                return executeTransition("drain", nodeId, nodeParent);
            }
        }

        @Command(name = "activate", description = "Activate a node (DRAINING/DECOMMISSIONED -> ON_DUTY)")
        static class ActivateCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private NodeCommand nodeParent;

            @Parameters(index = "0", description = "Node ID")
            private String nodeId;

            @Override public Integer call() {
                return executeTransition("activate", nodeId, nodeParent);
            }
        }

        @Command(name = "shutdown", description = "Shutdown a node (any -> SHUTTING_DOWN)")
        static class ShutdownCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private NodeCommand nodeParent;

            @Parameters(index = "0", description = "Node ID")
            private String nodeId;

            @Override public Integer call() {
                return executeTransition("shutdown", nodeId, nodeParent);
            }
        }

        @SuppressWarnings("JBCT-UTIL-02")
        private static Integer executeTransition(String action, String nodeId, NodeCommand nodeParent) {
            var response = nodeParent.parent.postToNode("/api/node/" + action + "/" + nodeId, "");
            var errorCode = OutputFormatter.checkResponseError(response,
                                                               nodeParent.parent.outputOptions(),
                                                               "Failed to " + action + " node " + nodeId);
            if ( errorCode >= 0) {
            return errorCode;}
            return OutputFormatter.printAction(response, nodeParent.parent.outputOptions(), action + " node " + nodeId);
        }
    }

    // ===== Topology Commands =====
    @Command(name = "topology", description = "Show cluster topology growth status")
    static class TopologyStatusCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private AetherCli parent;

        @Override public Integer call() {
            var response = parent.fetchFromNode("/api/cluster/topology");
            return OutputFormatter.printQuery(response, parent.outputOptions());
        }
    }

    // ===== Backup Commands =====
    @Command(name = "backup", description = "Manage cluster backups",
    subcommands = {BackupCommand.TriggerCommand.class, BackupCommand.ListCommand.class, BackupCommand.RestoreCommand.class})
    static class BackupCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "trigger", description = "Trigger a manual backup")
        static class TriggerCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private BackupCommand backupParent;

            @Override public Integer call() {
                var response = backupParent.parent.postToNode("/api/backup", "{}");
                return OutputFormatter.printAction(response, backupParent.parent.outputOptions(), "Backup triggered");
            }
        }

        @Command(name = "list", description = "List available backups")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private BackupCommand backupParent;

            @Override public Integer call() {
                var response = backupParent.parent.fetchFromNode("/api/backups");
                return OutputFormatter.printQuery(response, backupParent.parent.outputOptions());
            }
        }

        @Command(name = "restore", description = "Restore from a backup")
        static class RestoreCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private BackupCommand backupParent;

            @Parameters(index = "0", description = "Git commit ID to restore from")
            private String commitId;

            @Override public Integer call() {
                var response = backupParent.parent.postToNode("/api/backup/restore", "{\"commit\":\"" + commitId + "\"}");
                return OutputFormatter.printAction(response,
                                                   backupParent.parent.outputOptions(),
                                                   "Restore initiated from " + commitId);
            }
        }
    }

    // ===== Workers Commands =====
    @Command(name = "workers", description = "Manage worker nodes",
    subcommands = {WorkersCommand.ListCommand.class, WorkersCommand.HealthCommand.class, WorkersCommand.EndpointsCommand.class})
    static class WorkersCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "list", description = "List worker nodes")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private WorkersCommand workersParent;

            @Override public Integer call() {
                var response = workersParent.parent.fetchFromNode("/api/workers");
                return OutputFormatter.printQuery(response, workersParent.parent.outputOptions());
            }
        }

        @Command(name = "health", description = "Show worker pool health summary")
        static class HealthCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private WorkersCommand workersParent;

            @Override public Integer call() {
                var response = workersParent.parent.fetchFromNode("/api/workers/health");
                return OutputFormatter.printQuery(response, workersParent.parent.outputOptions());
            }
        }

        @Command(name = "endpoints", description = "List worker endpoints")
        static class EndpointsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private WorkersCommand workersParent;

            @Override public Integer call() {
                var response = workersParent.parent.fetchFromNode("/api/workers/endpoints");
                return OutputFormatter.printQuery(response, workersParent.parent.outputOptions());
            }
        }
    }

    // ===== Schema Commands =====
    @Command(name = "schema", description = "Manage datasource schemas",
    subcommands = {SchemaCommand.StatusCommand.class, SchemaCommand.HistoryCommand.class, SchemaCommand.MigrateCommand.class, SchemaCommand.UndoCommand.class, SchemaCommand.BaselineCommand.class, SchemaCommand.RetryCommand.class})
    static class SchemaCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "status", description = "Show schema status for all datasources")
        static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private SchemaCommand schemaParent;

            @Parameters(index = "0", description = "Datasource name (optional)", arity = "0..1")
            private String datasource;

            @Override public Integer call() {
                var path = datasource != null
                           ? "/api/schema/status/" + datasource
                           : "/api/schema/status";
                var response = schemaParent.parent.fetchFromNode(path);
                return OutputFormatter.printQuery(response, schemaParent.parent.outputOptions());
            }
        }

        @Command(name = "history", description = "Show migration history for a datasource")
        static class HistoryCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private SchemaCommand schemaParent;

            @Parameters(index = "0", description = "Datasource name")
            private String datasource;

            @Override public Integer call() {
                var response = schemaParent.parent.fetchFromNode("/api/schema/history/" + datasource);
                return OutputFormatter.printQuery(response, schemaParent.parent.outputOptions());
            }
        }

        @Command(name = "migrate", description = "Trigger manual migration for a datasource")
        static class MigrateCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private SchemaCommand schemaParent;

            @Parameters(index = "0", description = "Datasource name")
            private String datasource;

            @Override public Integer call() {
                var response = schemaParent.parent.postToNode("/api/schema/migrate/" + datasource, "{}");
                return OutputFormatter.printAction(response,
                                                   schemaParent.parent.outputOptions(),
                                                   "Migration triggered for " + datasource);
            }
        }

        @Command(name = "undo", description = "Undo migrations to target version")
        static class UndoCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private SchemaCommand schemaParent;

            @Parameters(index = "0", description = "Datasource name")
            private String datasource;

            @CommandLine.Option(names = {"-v", "--version"}, required = true, description = "Target version")
            private int targetVersion;

            @Override public Integer call() {
                var response = schemaParent.parent.postToNode("/api/schema/undo/" + datasource + "?targetVersion=" + targetVersion,
                                                              "{}");
                return OutputFormatter.printAction(response,
                                                   schemaParent.parent.outputOptions(),
                                                   "Undo to version " + targetVersion + " for " + datasource);
            }
        }

        @Command(name = "baseline", description = "Baseline a datasource at a version")
        static class BaselineCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private SchemaCommand schemaParent;

            @Parameters(index = "0", description = "Datasource name")
            private String datasource;

            @CommandLine.Option(names = {"-v", "--version"}, required = true, description = "Baseline version")
            private int version;

            @Override public Integer call() {
                var response = schemaParent.parent.postToNode("/api/schema/baseline/" + datasource + "?version=" + version,
                                                              "{}");
                return OutputFormatter.printAction(response,
                                                   schemaParent.parent.outputOptions(),
                                                   "Baseline set at version " + version + " for " + datasource);
            }
        }

        @Command(name = "retry", description = "Retry a failed schema migration")
        static class RetryCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private SchemaCommand schemaParent;

            @Parameters(index = "0", description = "Datasource name")
            private String datasource;

            @Override public Integer call() {
                var response = schemaParent.parent.postToNode("/api/schema/retry/" + datasource, "{}");
                return OutputFormatter.printAction(response,
                                                   schemaParent.parent.outputOptions(),
                                                   "Migration retry triggered for " + datasource);
            }
        }
    }

    // ===== A/B Test Commands =====
    @Command(name = "ab-test",
    description = "Manage A/B test deployments",
    subcommands = {AbTestCommand.CreateCommand.class, AbTestCommand.ListCommand.class, AbTestCommand.StatusCommand.class, AbTestCommand.MetricsCommand.class, AbTestCommand.ConcludeCommand.class})
    static class AbTestCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "create", description = "Create a new A/B test")
        static class CreateCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private AbTestCommand abParent;

            @Parameters(index = "0", description = "Artifact base (group:artifact)")
            private String artifactBase;

            @CommandLine.Option(names = {"--version-a"}, description = "Version A (control)", required = true)
            private String versionA;

            @CommandLine.Option(names = {"--version-b"}, description = "Version B (experiment)", required = true)
            private String versionB;

            @CommandLine.Option(names = {"--split"}, description = "Traffic split A:B (e.g., 50:50)", defaultValue = "50:50")
            private String split;

            @CommandLine.Option(names = {"-n", "--instances"}, description = "Instances per variant", defaultValue = "1")
            private int instances;

            @Override public Integer call() {
                var body = buildCreateBody();
                var response = abParent.parent.postToNode("/api/ab-test/create", body);
                return OutputFormatter.printQuery(response, abParent.parent.outputOptions());
            }

            private String buildCreateBody() {
                return "{\"artifactBase\":\"" + artifactBase + "\"," + "\"versionA\":\"" + versionA + "\"," + "\"versionB\":\"" + versionB + "\"," + "\"split\":\"" + split + "\"," + "\"instancesPerVariant\":" + instances + "}";
            }
        }

        @Command(name = "list", description = "List A/B tests")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private AbTestCommand abParent;

            @Override public Integer call() {
                var response = abParent.parent.fetchFromNode("/api/ab-tests");
                return OutputFormatter.printQuery(response, abParent.parent.outputOptions());
            }
        }

        @Command(name = "status", description = "Show A/B test status")
        static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private AbTestCommand abParent;

            @Parameters(index = "0", description = "Test ID")
            private String testId;

            @Override public Integer call() {
                var response = abParent.parent.fetchFromNode("/api/ab-test/" + testId);
                return OutputFormatter.printQuery(response, abParent.parent.outputOptions());
            }
        }

        @Command(name = "metrics", description = "Show A/B test comparison metrics")
        static class MetricsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private AbTestCommand abParent;

            @Parameters(index = "0", description = "Test ID")
            private String testId;

            @Override public Integer call() {
                var response = abParent.parent.fetchFromNode("/api/ab-test/" + testId + "/metrics");
                return OutputFormatter.printQuery(response, abParent.parent.outputOptions());
            }
        }

        @Command(name = "conclude", description = "Conclude A/B test and select winner")
        static class ConcludeCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private AbTestCommand abParent;

            @Parameters(index = "0", description = "Test ID")
            private String testId;

            @CommandLine.Option(names = {"--winner"}, description = "Winning variant: A or B", required = true)
            private String winner;

            @Override public Integer call() {
                var body = "{\"winner\":\"" + winner + "\"}";
                var response = abParent.parent.postToNode("/api/ab-test/" + testId + "/conclude", body);
                return OutputFormatter.printAction(response,
                                                   abParent.parent.outputOptions(),
                                                   "A/B test " + testId + " concluded with winner: " + winner);
            }
        }
    }

    // ===== Stream Commands =====
    @Command(name = "stream",
    description = "Manage event streams",
    subcommands = {StreamCommand.ListCommand.class, StreamCommand.StatusCommand.class, StreamCommand.PublishCommand.class})
    static class StreamCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "list", description = "List all streams")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private StreamCommand streamParent;

            @Override public Integer call() {
                var response = streamParent.parent.fetchFromNode("/api/streams");
                return OutputFormatter.printQuery(response, streamParent.parent.outputOptions());
            }
        }

        @Command(name = "status", description = "Show stream details")
        static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private StreamCommand streamParent;

            @Parameters(index = "0", description = "Stream name")
            private String name;

            @Override public Integer call() {
                var response = streamParent.parent.fetchFromNode("/api/streams/" + name);
                return OutputFormatter.printQuery(response, streamParent.parent.outputOptions());
            }
        }

        @Command(name = "publish", description = "Publish a message to a stream")
        static class PublishCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private StreamCommand streamParent;

            @Parameters(index = "0", description = "Stream name")
            private String name;

            @Parameters(index = "1", description = "Message content")
            private String message;

            @Override public Integer call() {
                var encoded = Base64.getEncoder().encodeToString(message.getBytes());
                var body = "{\"data\":\"" + encoded + "\"}";
                var response = streamParent.parent.postToNode("/api/streams/" + name + "/publish", body);
                return OutputFormatter.printAction(response,
                                                   streamParent.parent.outputOptions(),
                                                   "Published to stream " + name);
            }
        }
    }

    // ===== Certificate Commands =====
    @Command(name = "cert", description = "Certificate management",
    subcommands = {CertCommand.StatusCommand.class})
    static class CertCommand implements Runnable {
        @CommandLine.ParentCommand private AetherCli parent;

        @Contract @Override public void run() {
            // Default: show certificate status
            var response = parent.fetchFromNode("/api/certificate");
            OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "status", description = "Show certificate status and expiry info")
        static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand private CertCommand certParent;

            @Override public Integer call() {
                var response = certParent.parent.fetchFromNode("/api/certificate");
                return OutputFormatter.printQuery(response, certParent.parent.outputOptions());
            }
        }
    }

    /// Trust manager that accepts all certificates. Used only when --tls-skip-verify is enabled.
    @SuppressWarnings("JBCT-PAT-01")
    static final class TrustAllManager implements X509TrustManager {
        @Contract @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Contract @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
