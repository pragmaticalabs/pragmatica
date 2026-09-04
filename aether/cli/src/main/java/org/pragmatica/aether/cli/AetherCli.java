// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.BuildInfo;
import org.pragmatica.aether.config.ConfigLoader;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import picocli.AutoComplete.GenerateCompletion;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import static org.pragmatica.aether.management.route.ManagementRoute.*;
import static org.pragmatica.lang.Option.empty;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


@Command(name = "aether", mixinStandardHelpOptions = true, versionProvider = AetherVersionProvider.class, description = "Command-line interface for Aether cluster management", subcommands = {AetherCli.StatusCommand.class, AetherCli.NodesCommand.class, AetherCli.SlicesCommand.class, AetherCli.MetricsCommand.class, AetherCli.HealthCommand.class, AetherCli.ScaleCommand.class, AetherCli.BlueprintCommand.class, AetherCli.ArtifactCommand.class, AetherCli.InvocationMetricsCommand.class, AetherCli.ControllerCommand.class, AetherCli.AlertsCommand.class, AetherCli.ThresholdsCommand.class, AetherCli.TracesCommand.class, AetherCli.ObservabilityCommand.class, AetherCli.LoggingCommand.class, AetherCli.ConfigCommand.class, AetherCli.ScheduledTasksCommand.class, AetherCli.EventsCommand.class, AetherCli.WorkersCommand.class, AetherCli.BackupCommand.class, AetherCli.BackupSingularCommand.class, AetherCli.SchemaCommand.class, AetherCli.AbTestCommand.class, AetherCli.StreamCommand.class, org.pragmatica.aether.cli.stream.StreamCommand.class, AetherCli.CertCommand.class, AetherCli.RoutesCommand.class, AetherCli.VersionsCommand.class, AetherCli.DhtCommand.class, AetherCli.EntityCommand.class, org.pragmatica.aether.cli.deploy.DeployCommand.class, org.pragmatica.aether.cli.cluster.ClusterCommand.class, org.pragmatica.aether.cli.storage.StorageCommand.class, org.pragmatica.aether.cli.whoami.WhoamiCommand.class, org.pragmatica.aether.cli.ttm.TtmCommand.class, GenerateCompletion.class})
@Contract
public class AetherCli implements Runnable {
    private static final String DEFAULT_ADDRESS = "localhost:8080";

    @CommandLine.Option(names = {"-c", "--connect", "--endpoint"}, description = "Node address to connect to (host:port)")
    private String nodeAddress;

    @CommandLine.Option(names = {"--config"}, description = "Path to aether.toml config file")
    private Path configPath;

    @CommandLine.Option(names = "--api-key", description = "API key for authenticated access (prefer AETHER_API_KEY env var to avoid process list exposure)")
    private String apiKey;

    @CommandLine.Option(names = {"-k", "--tls-skip-verify"}, description = "Skip TLS certificate verification (insecure)")
    private boolean tlsSkipVerify;

    @CommandLine.Option(names = {"--request-timeout"}, description = "Per-request timeout in seconds (default 130, must exceed server-side max of 120). 0 disables.")
    private int requestTimeoutSeconds = 130;

    @CommandLine.Mixin
    private OutputOptions outputOptions = new OutputOptions();

    private HttpOperations httpOps;

    public OutputOptions outputOptions() {
        return outputOptions;
    }

    @Contract
    public static void main(String[] args) {
        var cli = new AetherCli();
        var cmd = new CommandLine(cli);

        cli.lookupConnection(args);
        cli.tlsSkipVerify = containsTlsSkipVerify(args);
        cli.httpOps = cli.buildHttpOperations();
        org.pragmatica.aether.cli.cluster.ClusterHttpClient.setEndpointOverride(cli.resolveEndpointUrl());
        extractApiKeyArg(args).orElse(() -> option(System.getenv("AETHER_API_KEY")).filter(k -> !k.isBlank()))
                        .onPresent(org.pragmatica.aether.cli.cluster.ClusterHttpClient::setApiKeyOverride);
        org.pragmatica.aether.cli.cluster.ClusterHttpClient.setRequestTimeout(resolveRequestTimeoutDuration(args));
        if (isReplMode(args)) {
            cli.runRepl(cmd);
        } else {
            int exitCode = cmd.execute(args);

            System.exit(exitCode);
        }
    }

    private String resolveEndpointUrl() {
        if (nodeAddress == null || nodeAddress.isBlank()) {
            return "";
        }

        return hasScheme(nodeAddress)
               ? nodeAddress
               : resolveScheme() + nodeAddress;
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01"})
    private static Option<String> extractApiKeyArg(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--api-key") && i + 1 < args.length) {
                return some(args[i + 1]);
            }

            if (args[i].startsWith("--api-key=")) {
                return some(args[i].substring("--api-key=".length()));
            }
        }

        return empty();
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01"})
    private static Option<String> extractRequestTimeoutArg(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--request-timeout") && i + 1 < args.length) {
                return some(args[i + 1]);
            }

            if (args[i].startsWith("--request-timeout=")) {
                return some(args[i].substring("--request-timeout=".length()));
            }
        }

        return empty();
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"})
    private static Duration resolveRequestTimeoutDuration(String[] args) {
        var seconds = extractRequestTimeoutArg(args).map(s -> {
                                                             try {
                                                             return Integer.parseInt(s.trim());
                                                         } catch (NumberFormatException e) {
                                                             return 130;
                                                         }
                                                         })
                                              .or(130);

        return seconds > 0
               ? TimeSpan.timeSpan(seconds)
                         .seconds()
                         .duration()
               : Duration.ZERO;
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static boolean isReplMode(String[] args) {
        if (args.length == 0) {
            return true;
        }

        var skipNext = false;

        for (var arg : args) {
            if (skipNext) {
                skipNext = false;
                continue;
            }

            if (isBooleanConnectionFlag(arg)) {} else if (isConnectionValueFlag(arg)) {
                if (!arg.contains("=")) {
                    skipNext = true;
                }
            } else {
                return false;
            }
        }

        return true;
    }

    private static boolean isConnectionFlag(String arg) {
        return isConnectionValueFlag(arg) || isBooleanConnectionFlag(arg);
    }

    private static boolean isConnectionValueFlag(String arg) {
        return arg.startsWith("-c") || arg.startsWith("--connect") || arg.startsWith("--endpoint") || arg.startsWith("--config") || arg.startsWith("--api-key");
    }

    private static boolean isBooleanConnectionFlag(String arg) {
        return arg.equals("-k") || arg.equals("--tls-skip-verify");
    }

    private static boolean containsTlsSkipVerify(String[] args) {
        for (var arg : args) {
            if (arg.equals("-k") || arg.equals("--tls-skip-verify")) {
                return true;
            }
        }

        return false;
    }

    @SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01"})
    private HttpOperations buildHttpOperations() {
        if (!tlsSkipVerify) {
            return JdkHttpOperations.jdkHttpOperations();
        }

        return buildTrustAllHttpOperations();
    }

    @SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01", "JBCT-EX-01"})
    private static HttpOperations buildTrustAllHttpOperations() {
        try {
            var sslContext = createTrustAllSslContext();
            var client = HttpClient.newBuilder().sslContext(sslContext).build();

            return JdkHttpOperations.jdkHttpOperations(client);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            System.err.println("Warning: Failed to create trust-all SSL context: " + e.getMessage());

            return JdkHttpOperations.jdkHttpOperations();
        }
    }

    // JBCT-RET-08: SSLContext.init(null keyManagers, …) — null is the JDK TLS contract (trust-only context)
    @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01", "JBCT-RET-08"})
    private static SSLContext createTrustAllSslContext() throws NoSuchAlgorithmException, KeyManagementException {
        var trustAll = new TrustManager[]{new TrustAllManager()};
        var sslContext = SSLContext.getInstance("TLS");

        sslContext.init(null, trustAll, new SecureRandom());

        return sslContext;
    }

    @SuppressWarnings("JBCT-UTIL-02")
    private void lookupConnection(String[] args) {
        var connectArg = extractConnectArg(args);
        var configArg = extractConfigArg(args);

        connectArg.onPresent(address -> nodeAddress = address).onEmpty(() -> setAddressFromConfigOrDefault(configArg));
    }

    private void setAddressFromConfigOrDefault(Option<Path> configArg) {
        configArg.filter(Files::exists).onPresent(this::readConfigFromPath).onEmpty(() -> nodeAddress = DEFAULT_ADDRESS);
    }

    @Contract
    private void readConfigFromPath(Path path) {
        ConfigLoader.load(path).onSuccess(this::setAddressFromConfig).onFailure(this::onConfigLoadFailure);
        configPath = path;
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01"})
    private static Option<String> extractConnectArg(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ((args[i].equals("-c") || args[i].equals("--connect") || args[i].equals("--endpoint")) && i + 1 < args.length) {
                return some(args[i + 1]);
            } else if (args[i].startsWith("--connect=")) {
                return some(args[i].substring("--connect=".length()));
            } else if (args[i].startsWith("--endpoint=")) {
                return some(args[i].substring("--endpoint=".length()));
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
        var port = config.cluster().ports().management();

        nodeAddress = "localhost:" + port;
    }

    private void onConfigLoadFailure(Cause cause) {
        System.err.println("Warning: Failed to load config: " + cause.message());
        nodeAddress = DEFAULT_ADDRESS;
    }

    @Contract
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01", "JBCT-UTIL-02"})
    private void runRepl(CommandLine cmd) {
        System.out.println("Aether v" + BuildInfo.current().displayString() + " - Connected to " + nodeAddress);
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

                if (line.trim().isEmpty()) {
                    continue;
                }

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
        if (tlsSkipVerify) {
            args.add("--tls-skip-verify");
        }

        for (var arg : replArgs) {
            args.add(arg);
        }

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

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"})
    public String fetch(ManagementRoute route, List<String> params) {
        return assembleOr(route, params, this::rawGet);
    }

    /// Fetches the body of the given route as raw bytes — for binary endpoints
    /// (e.g. artifact downloads) where the response is not a JSON string.
    /// Returns failure if the route cannot be assembled, the HTTP send fails,
    /// or the response status is not 2xx.
    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"})
    public Result<byte[]> fetchBytes(ManagementRoute route, List<String> params) {
        return route.assemble(params)
                    .flatMap(this::rawGetBytes);
    }

    public String fetch(ManagementRoute route) {
        return fetch(route, List.of());
    }

    public String fetch(ManagementRoute route, List<String> params, String queryString) {
        return assembleOr(route, params, path -> rawGet(appendQuery(path, queryString)));
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"})
    public String post(ManagementRoute route, List<String> params, String body) {
        return assembleOr(route, params, path -> rawPost(path, body));
    }

    public String post(ManagementRoute route, String body) {
        return post(route, List.of(), body);
    }

    public String post(ManagementRoute route, List<String> params, String queryString, String body) {
        return assembleOr(route, params, path -> rawPost(appendQuery(path, queryString), body));
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01", "JBCT-UTIL-02"})
    String put(ManagementRoute route, List<String> params, byte[] content, String contentType) {
        return assembleOr(route, params, path -> rawPut(path, content, contentType));
    }

    @SuppressWarnings("JBCT-UTIL-01")
    public String delete(ManagementRoute route, List<String> params) {
        return assembleOr(route, params, this::rawDelete);
    }

    public String delete(ManagementRoute route) {
        return delete(route, List.of());
    }

    private String assembleOr(ManagementRoute route,
                              List<String> params,
                              java.util.function.Function<String, String> exec) {
        return route.assemble(params)
                    .fold(cause -> "{\"error\":\"" + cause.message() + "\"}",
                          exec::apply);
    }

    private static String appendQuery(String path, String queryString) {
        return queryString == null || queryString.isEmpty()
               ? path
               : path + "?" + queryString;
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"})
    private String rawGet(String path) {
        var uri = resolveNodeUri(path);
        var request = buildGetRequest(uri);

        return httpOps.sendString(request)
                      .await()
                      .fold(cause -> "{\"error\":\"" + cause.message() + "\"}",
                            AetherCli::extractResponseBody);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"})
    private Result<byte[]> rawGetBytes(String path) {
        var uri = resolveNodeUri(path);
        var request = buildGetRequest(uri);

        return httpOps.sendBytes(request)
                      .await()
                      .flatMap(AetherCli::extractBytesBody);
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static Result<byte[]> extractBytesBody(HttpResult<byte[]> response) {
        var status = response.statusCode();

        if (status >= 200 && status < 300) {
            return Result.success(response.body());
        }

        if (status == 401) {
            return BytesFetchError.General.UNAUTHORIZED.result();
        }

        if (status == 403) {
            return BytesFetchError.General.FORBIDDEN.result();
        }

        if (status == 404) {
            return BytesFetchError.General.NOT_FOUND.result();
        }

        return new BytesFetchError.HttpStatus(status).result();
    }

    sealed interface BytesFetchError extends Cause {
        enum General implements BytesFetchError {
            UNAUTHORIZED("Authentication required. Use --api-key or set AETHER_API_KEY environment variable."),
            FORBIDDEN("Access denied. The provided API key does not have sufficient permissions."),
            NOT_FOUND("Artifact not found");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        record HttpStatus(int statusCode) implements BytesFetchError {
            @Override
            public String message() {
                return "HTTP " + statusCode;
            }
        }
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"})
    private String rawPost(String path, String body) {
        var uri = resolveNodeUri(path);
        var request = buildPostRequest(uri, body);

        return httpOps.sendString(request)
                      .await()
                      .fold(cause -> "{\"error\":\"" + cause.message() + "\"}",
                            AetherCli::extractResponseBody);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01", "JBCT-UTIL-02"})
    private String rawPut(String path, byte[] content, String contentType) {
        var uri = resolveNodeUri(path);
        var request = buildPutRequest(uri, content, contentType);

        return httpOps.sendString(request)
                      .await()
                      .fold(cause -> "{\"error\":\"" + cause.message() + "\"}",
                            AetherCli::extractPutResponseBody);
    }

    @SuppressWarnings("JBCT-UTIL-01")
    private String rawDelete(String path) {
        var uri = resolveNodeUri(path);
        var request = buildDeleteRequest(uri);

        return httpOps.sendString(request)
                      .await()
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

    private void attachTimeout(HttpRequest.Builder builder) {
        if (requestTimeoutSeconds > 0) {
            builder.timeout(TimeSpan.timeSpan(requestTimeoutSeconds).seconds().duration());
        }
    }

    private HttpRequest buildGetRequest(URI uri) {
        var builder = HttpRequest.newBuilder().uri(uri).GET();

        attachApiKey(builder);
        attachTimeout(builder);

        return builder.build();
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private HttpRequest buildPostRequest(URI uri, String body) {
        var builder = HttpRequest.newBuilder()
                                 .uri(uri)
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body));

        attachApiKey(builder);
        attachTimeout(builder);

        return builder.build();
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private HttpRequest buildPutRequest(URI uri, byte[] content, String contentType) {
        var builder = HttpRequest.newBuilder()
                                 .uri(uri)
                                 .header("Content-Type", contentType)
                                 .PUT(HttpRequest.BodyPublishers.ofByteArray(content));

        attachApiKey(builder);
        attachTimeout(builder);

        return builder.build();
    }

    private HttpRequest buildDeleteRequest(URI uri) {
        var builder = HttpRequest.newBuilder().uri(uri).DELETE();

        attachApiKey(builder);
        attachTimeout(builder);

        return builder.build();
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static String extractResponseBody(HttpResult<String> response) {
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
    private static String extractPutResponseBody(HttpResult<String> response) {
        if (response.statusCode() == 200 || response.statusCode() == 201) {
            return response.body()
                           .isEmpty()
                   ? "{\"status\":\"ok\"}"
                   : response.body();
        }

        return formatErrorResponse(response);
    }

    private static String formatErrorResponse(HttpResult<String> response) {
        var body = response.body();

        if (body != null && body.startsWith("{")) {
            return body;
        }

        var escaped = body == null
                      ? ""
                      : body.replace("\\", "\\\\").replace("\"", "\\\"");

        return "{\"error\":\"HTTP " + response.statusCode() + ": " + escaped + "\"}";
    }

    private static String escapeJsonValue(String s) {
        if (s == null) return "";

        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Command(name = "status", description = "Show cluster status (or specific node when [id] is given)")
    static class StatusCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Parameters(index = "0", description = "Node ID (omit for local node)", arity = "0..1")
        private String nodeId;

        @Override
        public Integer call() {
            var response = option(nodeId).map(id -> parent.fetch(NODE_STATUS_GET,
                                                                 List.of(id)))
                                 .or(() -> parent.fetch(NODE_STATUS));

            return OutputFormatter.printQuery(response, parent.outputOptions());
        }
    }

    @Command(name = "nodes", description = "Node management — list, lifecycle, per-node introspection", subcommands = {NodesCommand.LifecycleCommand.class, NodesCommand.LiveCommand.class, NodesCommand.ResolveCommand.class, NodesCommand.DrainCommand.class, NodesCommand.ShutdownCommand.class, NodesCommand.PromoteCommand.class, NodesCommand.SlicesCommand.class, NodesCommand.RoutesCommand.class, NodesCommand.InflightCommand.class, NodesCommand.MetricsCommand.class, NodesCommand.HealthCommand.class})
    static class NodesCommand implements Callable<Integer> {
        private static final OutputFormatter.TableSpec LIVE_NODES_TABLE = new OutputFormatter.TableSpec("Live Nodes",
                                                                                                        List.of(new OutputFormatter.Column("NODE ID",
                                                                                                                                           "nodeId",
                                                                                                                                           30),
                                                                                                                new OutputFormatter.Column("ADDRESS",
                                                                                                                                           "address",
                                                                                                                                           24),
                                                                                                                new OutputFormatter.Column("ROLE",
                                                                                                                                           "role",
                                                                                                                                           8),
                                                                                                                new OutputFormatter.Column("SWIM ALIVE",
                                                                                                                                           "swimAlive",
                                                                                                                                           12),
                                                                                                                new OutputFormatter.Column("REPORTED STATE",
                                                                                                                                           "reportedState",
                                                                                                                                           16)),
                                                                                                        "nodes");

        // JBCT-RET-08: no array-path for this table (optional field); Option-ifying TableSpec.arrayPath globally is disproportionate
        @SuppressWarnings("JBCT-RET-08")
        private static final OutputFormatter.TableSpec RESOLVE_TABLE = new OutputFormatter.TableSpec("Endpoint",
                                                                                                     List.of(new OutputFormatter.Column("NODE ID",
                                                                                                                                        "nodeId",
                                                                                                                                        30),
                                                                                                             new OutputFormatter.Column("ADDRESS",
                                                                                                                                        "address",
                                                                                                                                        24),
                                                                                                             new OutputFormatter.Column("REACHABLE",
                                                                                                                                        "reachable",
                                                                                                                                        12)),
                                                                                                     null);

        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public Integer call() {
            var response = parent.fetch(NODES_LIST);

            return OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "lifecycle", description = "Show node lifecycle states")
        static class LifecycleCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private NodesCommand nodesParent;

            @Parameters(index = "0", description = "Node ID (omit to list all)", arity = "0..1")
            private String nodeId;

            @CommandLine.Option(names = {"--state"}, description = "Filter list to nodes in this reported state (case-insensitive). Multi-state union via `+`, e.g. READY+SYNCING. Ignored when [id] is supplied.")
            private String state;

            @Override
            public Integer call() {
                return option(nodeId).map(this::showSingleNodeLifecycle)
                             .or(() -> showAllLifecycleStates());
            }

            private Integer showAllLifecycleStates() {
                var response = nodesParent.parent.fetch(NODE_LIFECYCLE_LIST, List.of(), buildLifecycleQuery());

                return OutputFormatter.printQuery(response, nodesParent.parent.outputOptions());
            }

            private String buildLifecycleQuery() {
                return option(state).map(s -> "state=" + s)
                             .or("");
            }

            private Integer showSingleNodeLifecycle(String id) {
                var response = nodesParent.parent.fetch(NODE_LIFECYCLE_GET, List.of(id));

                return OutputFormatter.printQuery(response, nodesParent.parent.outputOptions());
            }
        }

        @Command(name = "live", description = "Show live nodes — unified identity/address/role/SWIM-liveness/reported-state document")
        static class LiveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private NodesCommand nodesParent;

            @CommandLine.Option(names = {"--only-alive"}, description = "Filter to nodes with swimAlive=true (excludes the zombie class)")
            private boolean onlyAlive;

            @Override
            public Integer call() {
                var response = nodesParent.parent.fetch(NODES_LIVE);
                var filtered = onlyAlive
                               ? LiveNodesFilter.onlyAlive(response)
                               : response;

                return OutputFormatter.printQuery(filtered, nodesParent.parent.outputOptions(), LIVE_NODES_TABLE);
            }
        }

        @Command(name = "resolve", description = "Resolve a nodeId to its cluster-transport endpoint (exit 0 if reachable, 1 if not)")
        static class ResolveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private NodesCommand nodesParent;

            @Parameters(index = "0", description = "Node ID to resolve")
            private String nodeId;

            @Override
            public Integer call() {
                var response = nodesParent.parent.fetch(NODE_ENDPOINT_GET, List.of(nodeId));
                var errorCode = OutputFormatter.checkResponseError(response,
                                                                   nodesParent.parent.outputOptions(),
                                                                   "Failed to resolve node " + nodeId);

                if (errorCode >= 0) {
                    return errorCode;
                }

                OutputFormatter.printQuery(response, nodesParent.parent.outputOptions(), RESOLVE_TABLE);

                return LiveNodesFilter.isReachable(response)
                       ? ExitCode.SUCCESS
                       : ExitCode.ERROR;
            }
        }

        @Command(name = "drain", description = "Drain a node (READY -> DRAINING)")
        static class DrainCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private NodesCommand nodesParent;

            @Parameters(index = "0", description = "Node ID")
            private String nodeId;

            @Override
            public Integer call() {
                return executeTransition(NODE_DRAIN, "drain", nodeId, nodesParent);
            }
        }

        @Command(name = "shutdown", description = "Shutdown a node (any -> SHUTTING_DOWN)")
        static class ShutdownCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private NodesCommand nodesParent;

            @Parameters(index = "0", description = "Node ID")
            private String nodeId;

            @Override
            public Integer call() {
                return executeTransition(NODE_SHUTDOWN, "shutdown", nodeId, nodesParent);
            }
        }

        @Command(name = "promote", description = "Promote a node to a new role (CORE or WORKER) via consensus")
        static class PromoteCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private NodesCommand nodesParent;

            @Parameters(index = "0", description = "Node ID to promote")
            private String nodeId;

            @CommandLine.Option(names = {"--role"}, required = true, description = "Target role: CORE or WORKER (case-insensitive)")
            private String role;

            @Override
            public Integer call() {
                var body = "{\"targetRole\":\"" + role.trim().toUpperCase() + "\"}";
                var response = nodesParent.parent.post(NODE_PROMOTE, List.of(nodeId), body);
                var errorCode = OutputFormatter.checkResponseError(response,
                                                                   nodesParent.parent.outputOptions(),
                                                                   "Failed to promote node " + nodeId);

                if (errorCode >= 0) {
                    return errorCode;
                }

                return OutputFormatter.printAction(response,
                                                   nodesParent.parent.outputOptions(),
                                                   "promote node " + nodeId + " to " + role.trim().toUpperCase());
            }
        }

        @Command(name = "slices", description = "Show slices on a node (defaults to local; pass [id] for any node)")
        static class SlicesCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private NodesCommand nodesParent;

            @Parameters(index = "0", description = "Node ID (omit for local node)", arity = "0..1")
            private String nodeId;

            @Override
            public Integer call() {
                var response = option(nodeId).map(id -> nodesParent.parent.fetch(NODE_SLICES_GET,
                                                                                 List.of(id)))
                                     .or(() -> nodesParent.parent.fetch(NODE_SLICES));

                return OutputFormatter.printQuery(response, nodesParent.parent.outputOptions());
            }
        }

        @Command(name = "routes", description = "Show HTTP routes on a node (defaults to local; pass [id] for any node)")
        static class RoutesCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private NodesCommand nodesParent;

            @Parameters(index = "0", description = "Node ID (omit for local node)", arity = "0..1")
            private String nodeId;

            @Override
            public Integer call() {
                var response = option(nodeId).map(id -> nodesParent.parent.fetch(NODE_ROUTES_GET,
                                                                                 List.of(id)))
                                     .or(() -> nodesParent.parent.fetch(NODE_ROUTES));

                return OutputFormatter.printQuery(response, nodesParent.parent.outputOptions());
            }
        }

        @Command(name = "inflight", description = "Show in-flight request count on a node (defaults to local; pass [id] for any node)")
        static class InflightCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private NodesCommand nodesParent;

            @Parameters(index = "0", description = "Node ID (omit for local node)", arity = "0..1")
            private String nodeId;

            @Override
            public Integer call() {
                var response = option(nodeId).map(id -> nodesParent.parent.fetch(NODE_INFLIGHT_GET,
                                                                                 List.of(id)))
                                     .or(() -> nodesParent.parent.fetch(NODE_INFLIGHT));

                return OutputFormatter.printQuery(response, nodesParent.parent.outputOptions());
            }
        }

        @Command(name = "metrics", description = "Show per-node metrics (defaults to local; pass [id] for any node)")
        static class MetricsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private NodesCommand nodesParent;

            @Parameters(index = "0", description = "Node ID (omit for local node)", arity = "0..1")
            private String nodeId;

            @Override
            public Integer call() {
                var response = option(nodeId).map(id -> nodesParent.parent.fetch(NODE_METRICS_GET,
                                                                                 List.of(id)))
                                     .or(() -> nodesParent.parent.fetch(NODE_METRICS));

                return OutputFormatter.printQuery(response, nodesParent.parent.outputOptions());
            }
        }

        @Command(name = "health", description = "Show readiness check on a node (defaults to local; pass [id] for any node; --liveness for /health/live)")
        static class HealthCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private NodesCommand nodesParent;

            @Parameters(index = "0", description = "Node ID (omit for local node)", arity = "0..1")
            private String nodeId;

            @CommandLine.Option(names = "--liveness", description = "Query /health/live instead of /health/ready")
            private boolean liveness;

            @Override
            public Integer call() {
                var perNodeRoute = liveness
                                   ? HEALTH_LIVE_GET
                                   : HEALTH_READY_GET;
                var localRoute = liveness
                                 ? HEALTH_LIVE
                                 : HEALTH_READY;
                var response = option(nodeId).map(id -> nodesParent.parent.fetch(perNodeRoute,
                                                                                 List.of(id)))
                                     .or(() -> nodesParent.parent.fetch(localRoute));

                return OutputFormatter.printQuery(response, nodesParent.parent.outputOptions());
            }
        }

        @SuppressWarnings("JBCT-UTIL-02")
        private static Integer executeTransition(ManagementRoute route,
                                                 String action,
                                                 String nodeId,
                                                 NodesCommand nodesParent) {
            var response = nodesParent.parent.post(route, List.of(nodeId), "");
            var errorCode = OutputFormatter.checkResponseError(response,
                                                               nodesParent.parent.outputOptions(),
                                                               "Failed to " + action + " node " + nodeId);

            if (errorCode >= 0) {
                return errorCode;
            }

            return OutputFormatter.printAction(response, nodesParent.parent.outputOptions(), action + " node " + nodeId);
        }
    }

    @Command(name = "slices", description = "Show all slices across the cluster", subcommands = {SlicesCommand.StatusCommand.class, SlicesCommand.TopologyCommand.class, SlicesCommand.ConfigCommand.class})
    static class SlicesCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @CommandLine.Option(names = {"--state"}, description = "Filter to slices/instances in this state (case-insensitive, e.g. ACTIVE, LOADED)")
        private String state;

        @Override
        public Integer call() {
            var response = parent.fetch(SLICES_LIST, List.of(), buildSlicesQuery());

            return OutputFormatter.printQuery(response, parent.outputOptions());
        }

        private String buildSlicesQuery() {
            return option(state).map(s -> "state=" + s)
                         .or("");
        }

        @Command(name = "status", description = "Show aggregate slice status across the cluster (per-slice state breakdown)")
        static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private SlicesCommand slicesParent;

            @Override
            public Integer call() {
                var response = slicesParent.parent.fetch(SLICES_STATUS);

                return OutputFormatter.printQuery(response, slicesParent.parent.outputOptions());
            }
        }

        @Command(name = "topology", description = "Show slice topology — per-slice governor mapping across the cluster")
        static class TopologyCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private SlicesCommand slicesParent;

            @Override
            public Integer call() {
                var response = slicesParent.parent.fetch(SLICE_TOPOLOGY);

                return OutputFormatter.printQuery(response, slicesParent.parent.outputOptions());
            }
        }

        @Command(name = "config", description = "Show effective configuration view for a loaded slice with per-key layer attribution")
        static class ConfigCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private SlicesCommand slicesParent;

            @CommandLine.Parameters(index = "0", description = "Slice artifact coordinates (e.g. org.example:my-slice:1.0.0)")
            private String sliceId;

            @Override
            public Integer call() {
                var response = slicesParent.parent.fetch(SLICE_CONFIG, List.of(sliceId));

                return OutputFormatter.printQuery(response, slicesParent.parent.outputOptions());
            }
        }
    }

    @Command(name = "routes", description = "Show HTTP routes across the cluster")
    static class RoutesCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public Integer call() {
            var response = parent.fetch(ROUTES_LIST);

            return OutputFormatter.printQuery(response, parent.outputOptions());
        }
    }

    @Command(name = "versions", description = "Show deployed versioned slices and their API version registries (#198)")
    static class VersionsCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public Integer call() {
            var response = parent.fetch(VERSIONS);

            return OutputFormatter.printQuery(response, parent.outputOptions());
        }
    }

    @Command(name = "metrics", description = "Show cluster metrics", subcommands = {MetricsCommand.PrometheusCommand.class, MetricsCommand.TransportCommand.class, MetricsCommand.ComprehensiveCommand.class, MetricsCommand.DerivedCommand.class, MetricsCommand.HistoryCommand.class, MetricsCommand.TimeoutsCommand.class})
    static class MetricsCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public Integer call() {
            var response = parent.fetch(METRICS);

            return OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "prometheus", description = "Show Prometheus-format metrics")
        static class PrometheusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private MetricsCommand metricsParent;

            @Override
            public Integer call() {
                var response = metricsParent.parent.fetch(METRICS_PROMETHEUS);

                return OutputFormatter.printQuery(response, metricsParent.parent.outputOptions());
            }
        }

        @Command(name = "transport", description = "Show transport-layer metrics")
        static class TransportCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private MetricsCommand metricsParent;

            @Override
            public Integer call() {
                var response = metricsParent.parent.fetch(METRICS_TRANSPORT);

                return OutputFormatter.printQuery(response, metricsParent.parent.outputOptions());
            }
        }

        @Command(name = "comprehensive", description = "Show comprehensive minute-aggregated metrics")
        static class ComprehensiveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private MetricsCommand metricsParent;

            @Override
            public Integer call() {
                var response = metricsParent.parent.fetch(METRICS_COMPREHENSIVE);

                return OutputFormatter.printQuery(response, metricsParent.parent.outputOptions());
            }
        }

        @Command(name = "derived", description = "Show derived (computed) metrics: trends, saturation, health score")
        static class DerivedCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private MetricsCommand metricsParent;

            @Override
            public Integer call() {
                var response = metricsParent.parent.fetch(METRICS_DERIVED);

                return OutputFormatter.printQuery(response, metricsParent.parent.outputOptions());
            }
        }

        @Command(name = "history", description = "Show historical metrics over a time range")
        static class HistoryCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private MetricsCommand metricsParent;

            @CommandLine.Option(names = "--range", description = "Time range. Values: 5m, 15m, 1h (default), 2h")
            private String range;

            @CommandLine.Option(names = "--since", description = "Alias for --range")
            private String since;

            @Override
            public Integer call() {
                var response = metricsParent.parent.fetch(METRICS_HISTORY, List.of(), buildHistoryQuery());

                return OutputFormatter.printQuery(response, metricsParent.parent.outputOptions());
            }

            String buildHistoryQuery() {
                return option(range).orElse(() -> option(since))
                             .map(v -> "range=" + v)
                             .or("");
            }
        }

        @Command(name = "timeouts", description = "Show per-subsystem timeout-fired counters")
        static class TimeoutsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private MetricsCommand metricsParent;

            @Override
            public Integer call() {
                var response = metricsParent.parent.fetch(METRICS_TIMEOUTS);

                return OutputFormatter.printQuery(response, metricsParent.parent.outputOptions());
            }
        }
    }

    @Command(name = "health", description = "Check node health")
    static class HealthCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Override
        public Integer call() {
            var response = parent.fetch(CLUSTER_HEALTH);

            return OutputFormatter.printQuery(response, parent.outputOptions());
        }
    }

    @Command(name = "scale", description = "Scale a deployed slice")
    @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01"})
    static class ScaleCommand implements Callable<Integer> {
        private static final int POLL_INTERVAL_MS = 2000;

        @CommandLine.ParentCommand
        private AetherCli parent;

        @Parameters(index = "0", description = "Artifact coordinates (group:artifact:version)")
        private String artifact;

        @CommandLine.Option(names = {"-n", "--instances"}, description = "Target number of instances", required = true)
        private int instances;

        @CommandLine.Option(names = {"-p", "--placement"}, description = "Placement strategy: CORE_ONLY, WORKER_PREFERRED, WORKER_ONLY")
        private String placement;

        @CommandLine.Option(names = "--wait", description = "Wait for scaling to complete")
        private boolean waitForCompletion;

        @CommandLine.Option(names = "--timeout", description = "Timeout in seconds when waiting", defaultValue = "300")
        private int timeoutSeconds;

        @Override
        public Integer call() {
            var body = buildScaleBody();
            var response = parent.post(SLICE_SCALE, body);
            var result = OutputFormatter.printAction(response,
                                                     parent.outputOptions(),
                                                     "Scaled " + artifact + " to " + instances + " instances");

            if (result != ExitCode.SUCCESS || !waitForCompletion) {
                return result;
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

            while (System.currentTimeMillis() < deadline) {
                var currentInstances = queryCurrentInstances();

                if (currentInstances >= instances) {
                    System.out.printf("Scaling complete: %s now has %d instances.%n", artifact, currentInstances);

                    return ExitCode.SUCCESS;
                }

                System.out.printf("  Current instances: %s / %d%n", ScaleWait.describe(currentInstances), instances);
                sleepQuietly();
            }

            System.err.printf("Timeout: %s did not reach %d instances within %ds.%n",
                              artifact,
                              instances,
                              timeoutSeconds);

            return ExitCode.TIMEOUT;
        }

        /// Counts instances the cluster reports as ACTIVE for this artifact — the same
        /// per-instance state `aether slices status` shows.
        private int queryCurrentInstances() {
            return ScaleWait.activeInstances(parent.fetch(SLICES_LIST), artifact);
        }

        @SuppressWarnings("JBCT-EX-01")
        private static void sleepQuietly() {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        private String buildScaleBody() {
            var sb = new StringBuilder("{\"artifact\":\"").append(escapeJsonValue(artifact))
                                                          .append("\",\"instances\":")
                                                          .append(instances);

            if (placement != null) {
                sb.append(",\"placement\":\"").append(escapeJsonValue(placement)).append("\"");
            }

            return sb.append("}")
                     .toString();
        }
    }

    @Command(name = "artifacts", description = "Artifact repository management", subcommands = {ArtifactCommand.DeployArtifactCommand.class, ArtifactCommand.PushArtifactCommand.class, ArtifactCommand.ListArtifactsCommand.class, ArtifactCommand.VersionsCommand.class, ArtifactCommand.InfoCommand.class, ArtifactCommand.GetArtifactCommand.class, ArtifactCommand.DeleteCommand.class, ArtifactCommand.MetricsCommand.class})
    static class ArtifactCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
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
                try {
                    if (!Files.exists(jarPath)) {
                        System.err.println("File not found: " + jarPath);

                        return ExitCode.ERROR;
                    }

                    byte[] content = Files.readAllBytes(jarPath);
                    var coordinates = groupId + ":" + artifactId + ":" + version;
                    var response = artifactParent.parent.put(ARTIFACT_PUT,
                                                             artifactParams(groupId, artifactId, version),
                                                             content,
                                                             "application/java-archive");

                    return reportDeployResult(response, coordinates, content.length);
                } catch (IOException e) {
                    System.err.println("Error reading file: " + e.getMessage());

                    return ExitCode.ERROR;
                }
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private Integer reportDeployResult(String response, String coordinates, int size) {
                var errorCode = OutputFormatter.checkResponseError(response,
                                                                   artifactParent.parent.outputOptions(),
                                                                   "Failed to deploy");

                if (errorCode >= 0) {
                    return errorCode;
                }

                var message = "Deployed " + coordinates + "\n  File: " + jarPath + "\n  Size: " + size + " bytes";

                return OutputFormatter.printAction(response, artifactParent.parent.outputOptions(), message);
            }
        }

        @Command(name = "push", description = "Push blueprint and its slices from local Maven repository to cluster")
        static class PushArtifactCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ArtifactCommand artifactParent;

            @Parameters(index = "0", description = "Blueprint coordinates (group:artifact:version)")
            private String coordinates;

            private record ArtifactDescriptor(String groupId,
                                              String artifactId,
                                              String version,
                                              String label,
                                              Path localPath) {}

            @Override
            @SuppressWarnings("JBCT-SEQ-01")
            public Integer call() {
                var parts = coordinates.split(":");

                if (parts.length != 3) {
                    System.err.println("Invalid coordinates format. Expected: group:artifact:version");

                    return ExitCode.ERROR;
                }

                var groupId = parts[0];
                var artifactId = parts[1];
                var version = parts[2];
                var blueprintPath = findBlueprintJar(groupId, artifactId, version);

                if (!Files.exists(blueprintPath)) {
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

                if (sliceDescriptors == null) {
                    return ExitCode.ERROR;
                }

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
                var options = artifactParent.parent.outputOptions();
                var jsonMode = options.format() == OutputFormat.JSON;

                if (!jsonMode) {
                    System.out.println("Pushing " + artifactId + " blueprint (" + artifacts.size() + " artifacts):");
                }

                var statuses = new ArrayList<String>();

                for (var artifact : artifacts) {
                    var outcome = pushSingleArtifact(artifact, jsonMode);

                    if (outcome.exitCode() != ExitCode.SUCCESS) {
                        return outcome.exitCode();
                    }

                    statuses.add(outcome.status());
                }

                if (jsonMode) {
                    emitBulkPushJson(artifactId, artifacts, statuses);
                } else {
                    System.out.println("All artifacts pushed successfully.");
                }

                return ExitCode.SUCCESS;
            }

            private record PushOutcome(int exitCode, String status, String response) {}

            private void emitBulkPushJson(String artifactId,
                                          List<ArtifactDescriptor> artifacts,
                                          List<String> statuses) {
                var aggregate = aggregateStatus(statuses);
                var sb = new StringBuilder(256);

                sb.append("{\"status\":\"")
                  .append(aggregate)
                  .append("\",")
                  .append("\"blueprint\":\"")
                  .append(escapeJsonValue(artifactId))
                  .append("\",")
                  .append("\"artifacts\":[");
                for (int i = 0; i < artifacts.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }

                    sb.append("{\"coords\":\"")
                      .append(escapeJsonValue(artifacts.get(i).label()))
                      .append("\",")
                      .append("\"status\":\"")
                      .append(statuses.get(i))
                      .append("\"}");
                }

                sb.append("]}");
                System.out.println(sb);
            }

            /// Aggregate per-artifact statuses into a single top-level field that
            /// integration scripts can read with a single `jq '.status'` lookup:
            ///   - all `uploaded`         → `"uploaded"`
            ///   - all `already-present`  → `"already-present"`
            ///   - mixed                  → `"mixed"`
            /// `mixed` still counts as success (exit code 0) — every individual artifact
            /// either uploaded or was already present.
            private static String aggregateStatus(List<String> statuses) {
                if (statuses.isEmpty()) {
                    return "uploaded";
                }

                var first = statuses.getFirst();

                for (var s : statuses) {
                    if (!s.equals(first)) {
                        return "mixed";
                    }
                }

                return first;
            }

            @SuppressWarnings({"JBCT-UTIL-02", "JBCT-SEQ-01"})
            private PushOutcome pushSingleArtifact(ArtifactDescriptor descriptor, boolean jsonMode) {
                if (!Files.exists(descriptor.localPath())) {
                    System.err.println("  x " + descriptor.label() + " (not found: " + descriptor.localPath() + ")");

                    return new PushOutcome(ExitCode.ERROR, "missing", "");
                }

                try {
                    var content = Files.readAllBytes(descriptor.localPath());
                    var response = artifactParent.parent.put(ARTIFACT_PUT,
                                                             artifactParams(descriptor.groupId(),
                                                                            descriptor.artifactId(),
                                                                            descriptor.version()),
                                                             content,
                                                             "application/java-archive");
                    var errorCode = OutputFormatter.checkResponseError(response,
                                                                       artifactParent.parent.outputOptions(),
                                                                       "Failed to push");

                    if (errorCode >= 0) {
                        return new PushOutcome(errorCode, "failed", response);
                    }

                    var status = parsePushStatus(response);

                    if (!jsonMode) {
                        var sizeKb = content.length / 1024;
                        var suffix = status.equals("already-present")
                                     ? " (already present)"
                                     : "";

                        System.out.println("  + " + descriptor.label() + " (" + sizeKb + "KB)" + suffix);
                    }

                    return new PushOutcome(ExitCode.SUCCESS, status, response);
                } catch (IOException e) {
                    System.err.println("  x " + descriptor.label() + " (error: " + e.getMessage() + ")");

                    return new PushOutcome(ExitCode.ERROR, "io-error", "");
                }
            }

            /// Extract the `status` field from the server's idempotent-PUT JSON body.
            /// Falls back to `"uploaded"` if the body cannot be parsed (defensive — the
            /// server contract is fixed, so this only triggers if a buggy server replies
            /// with a non-JSON 200 OK).
            private static String parsePushStatus(String response) {
                if (response == null || response.isBlank()) {
                    return "uploaded";
                }

                var trimmed = response.trim();
                var marker = "\"status\"";
                var idx = trimmed.indexOf(marker);

                if (idx < 0) {
                    return "uploaded";
                }

                var rest = trimmed.substring(idx + marker.length());
                var colon = rest.indexOf(':');

                if (colon < 0) {
                    return "uploaded";
                }

                var afterColon = rest.substring(colon + 1).stripLeading();

                if (afterColon.isEmpty() || afterColon.charAt(0) != '"') {
                    return "uploaded";
                }

                var endQuote = afterColon.indexOf('"', 1);

                if (endQuote < 0) {
                    return "uploaded";
                }

                return afterColon.substring(1, endQuote);
            }

            @SuppressWarnings("JBCT-SEQ-01")
            private static Result<String> readBlueprintToml(Path jarPath) {
                try (var jar = new JarFile(jarPath.toFile())) {
                    var entry = jar.getEntry("META-INF/blueprint.toml");

                    if (entry == null) {
                        return MISSING_BLUEPRINT_TOML.result();
                    }

                    return Result.success(new String(jar.getInputStream(entry).readAllBytes()));
                } catch (IOException e) {
                    return new ReadBlueprintFailed(e.getMessage()).result();
                }
            }

            private static Result<List<ArtifactDescriptor>> parseSliceCoordinates(String tomlContent) {
                return TomlParser.parse(tomlContent).flatMap(PushArtifactCommand::extractSliceDescriptors);
            }

            private static Result<List<ArtifactDescriptor>> extractSliceDescriptors(TomlDocument doc) {
                return doc.getTableArray("slices")
                          .toResult(MISSING_SLICES_SECTION)
                          .map(PushArtifactCommand::mapSliceTables);
            }

            @SuppressWarnings("JBCT-PAT-01")
            private static List<ArtifactDescriptor> mapSliceTables(List<Map<String, Object>> tables) {
                var descriptors = new ArrayList<ArtifactDescriptor>();

                for (var table : tables) {
                    var artifact = String.valueOf(table.getOrDefault("artifact", ""));
                    var sliceParts = artifact.split(":");

                    if (sliceParts.length == 3) {
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

            private static List<ArtifactDescriptor> reportParseError(Cause cause) {
                System.err.println("Failed to read blueprint: " + cause.message());

                return List.of();
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
                @Override
                public String message() {
                    return "Failed to read blueprint JAR: " + detail;
                }
            }
        }

        @Command(name = "list", description = "List artifacts in the repository")
        static class ListArtifactsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ArtifactCommand artifactParent;

            @Override
            public Integer call() {
                var response = artifactParent.parent.fetch(REPOSITORY_ARTIFACTS_LIST);

                return OutputFormatter.printQuery(response, artifactParent.parent.outputOptions());
            }
        }

        @Command(name = "versions", description = "List versions of an artifact (default: maven-metadata.xml; --format json emits a structured envelope)")
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

                    return ExitCode.ERROR;
                }

                var response = artifactParent.parent.fetch(MAVEN_METADATA,
                                                           List.of(parts[0].replace('.', '/'),
                                                                   parts[1],
                                                                   "maven-metadata.xml"));
                var errorCode = OutputFormatter.checkResponseError(response,
                                                                   artifactParent.parent.outputOptions(),
                                                                   "Failed to get versions");

                if (errorCode >= 0) {
                    return errorCode;
                }

                return artifactParent.parent.outputOptions()
                                            .format() == OutputFormat.TABLE
                       ? printRawXml(response)
                       : OutputFormatter.printQuery(MavenMetadataFormatter.toJson(response),
                                                    artifactParent.parent.outputOptions());
            }

            private static int printRawXml(String response) {
                System.out.println(response);

                return ExitCode.SUCCESS;
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

                    return ExitCode.ERROR;
                }

                var response = artifactParent.parent.fetch(ARTIFACT_INFO,
                                                           List.of(parts[0].replace('.', '/'), parts[1], parts[2]));

                return OutputFormatter.printQuery(response, artifactParent.parent.outputOptions());
            }
        }

        @Command(name = "get", description = "Download an artifact file from the repository (writes to stdout or --out)")
        static class GetArtifactCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ArtifactCommand artifactParent;

            @Parameters(index = "0", description = "Artifact coordinates (group:artifact:version)")
            private String coordinates;

            @CommandLine.Option(names = "--out", description = "Write artifact bytes to this file (default: stdout)")
            private Path outPath;

            @CommandLine.Option(names = "--file", description = "Filename within the artifact (default: <artifactId>-<version>.jar)")
            private String file;

            @Override
            @SuppressWarnings({"JBCT-UTIL-02", "JBCT-SEQ-01"})
            public Integer call() {
                var parts = coordinates.split(":");

                if (parts.length != 3) {
                    System.err.println("Invalid coordinates format. Expected: group:artifact:version");

                    return ExitCode.ERROR;
                }

                var groupPath = parts[0].replace('.', '/');
                var artifactId = parts[1];
                var version = parts[2];
                var filename = option(file).filter(s -> !s.isBlank()).or(artifactId + "-" + version + ".jar");
                var bytes = artifactParent.parent.fetchBytes(ARTIFACT_GET,
                                                             List.of(groupPath, artifactId, version, filename));

                return bytes.fold(this::reportFetchFailure, this::writeBytes);
            }

            private Integer reportFetchFailure(Cause cause) {
                System.err.println("Failed to fetch artifact: " + cause.message());

                return ExitCode.ERROR;
            }

            @SuppressWarnings({"JBCT-EX-01", "JBCT-UTIL-02"})
            private Integer writeBytes(byte[] content) {
                return option(outPath).map(path -> writeToFile(path, content))
                             .or(() -> writeToStdout(content));
            }

            @SuppressWarnings("JBCT-EX-01")
            private Integer writeToFile(Path path, byte[] content) {
                try {
                    Files.write(path, content);
                    System.err.println("Wrote " + content.length + " bytes to " + path);

                    return ExitCode.SUCCESS;
                } catch (IOException e) {
                    System.err.println("Failed to write file " + path + ": " + e.getMessage());

                    return ExitCode.ERROR;
                }
            }

            @SuppressWarnings("JBCT-EX-01")
            private static Integer writeToStdout(byte[] content) {
                try {
                    System.out.write(content);
                    System.out.flush();

                    return ExitCode.SUCCESS;
                } catch (IOException e) {
                    System.err.println("Failed to write to stdout: " + e.getMessage());

                    return ExitCode.ERROR;
                }
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

                    return ExitCode.ERROR;
                }

                var response = artifactParent.parent.delete(ARTIFACT_DELETE,
                                                            List.of(parts[0].replace('.', '/'), parts[1], parts[2]));
                var errorCode = OutputFormatter.checkResponseError(response,
                                                                   artifactParent.parent.outputOptions(),
                                                                   "Failed to delete");

                if (errorCode >= 0) {
                    return errorCode;
                }

                return OutputFormatter.printAction(response,
                                                   artifactParent.parent.outputOptions(),
                                                   "Deleted " + coordinates);
            }
        }

        @Command(name = "metrics", description = "Show artifact storage metrics")
        static class MetricsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ArtifactCommand artifactParent;

            @Override
            public Integer call() {
                var response = artifactParent.parent.fetch(ARTIFACT_METRICS);

                return OutputFormatter.printQuery(response, artifactParent.parent.outputOptions());
            }
        }

        private static List<String> artifactParams(String groupId, String artifactId, String version) {
            return List.of(groupId.replace('.', '/'), artifactId, version, artifactId + "-" + version + ".jar");
        }
    }

    @Command(name = "blueprints", description = "Blueprint management", subcommands = {BlueprintCommand.ApplyCommand.class, BlueprintCommand.ListCommand.class, BlueprintCommand.GetCommand.class, BlueprintCommand.DeleteCommand.class, BlueprintCommand.StatusCommand.class, BlueprintCommand.ValidateCommand.class, BlueprintCommand.DeployArtifactCommand.class, BlueprintCommand.PublishCommand.class, BlueprintCommand.UploadCommand.class})
    static class BlueprintCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

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

        @Contract
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
                try {
                    if (!Files.exists(blueprintPath)) {
                        System.err.println("Blueprint file not found: " + blueprintPath);

                        return ExitCode.ERROR;
                    }

                    var content = Files.readString(blueprintPath);
                    var response = blueprintParent.parent.post(BLUEPRINT_PUBLISH_BODY, content);
                    var errorCode = OutputFormatter.checkResponseError(response,
                                                                       blueprintParent.parent.outputOptions(),
                                                                       "Failed to apply blueprint");

                    if (errorCode >= 0) {
                        return errorCode;
                    }

                    return OutputFormatter.printQuery(response, blueprintParent.parent.outputOptions());
                } catch (IOException e) {
                    System.err.println("Error reading blueprint file: " + e.getMessage());

                    return ExitCode.ERROR;
                }
            }
        }

        @Command(name = "list", description = "List all deployed blueprints")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BlueprintCommand blueprintParent;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var response = blueprintParent.parent.fetch(BLUEPRINT_LIST);
                var output = blueprintParent.parent.outputOptions();
                var errorCode = OutputFormatter.checkResponseError(response, output, "Failed to list blueprints");

                if (errorCode >= 0) {
                    return errorCode;
                }

                return OutputFormatter.printQuery(response, output, BLUEPRINT_LIST_TABLE);
            }
        }

        @Command(name = "get", description = "Get blueprint details")
        static class GetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Blueprint ID (group:artifact:version)")
            private String blueprintId;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var response = blueprintParent.parent.fetch(BLUEPRINT_GET, List.of(blueprintId));
                var output = blueprintParent.parent.outputOptions();
                var errorCode = OutputFormatter.checkResponseError(response, output, "Failed to get blueprint");

                if (errorCode >= 0) {
                    return errorCode;
                }

                return OutputFormatter.printQuery(response, output, BLUEPRINT_SLICES_TABLE);
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
                        return ExitCode.SUCCESS;
                    }
                }

                var response = blueprintParent.parent.delete(BLUEPRINT_DELETE, List.of(blueprintId));
                var errorCode = OutputFormatter.checkResponseError(response,
                                                                   blueprintParent.parent.outputOptions(),
                                                                   "Failed to delete blueprint");

                if (errorCode >= 0) {
                    return errorCode;
                }

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

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var response = blueprintParent.parent.fetch(BLUEPRINT_STATUS, List.of(blueprintId));
                var output = blueprintParent.parent.outputOptions();
                var errorCode = OutputFormatter.checkResponseError(response, output, "Failed to get blueprint status");

                if (errorCode >= 0) {
                    return errorCode;
                }

                return OutputFormatter.printQuery(response, output, BLUEPRINT_STATUS_TABLE);
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
                try {
                    if (!Files.exists(blueprintPath)) {
                        System.err.println("Blueprint file not found: " + blueprintPath);

                        return ExitCode.ERROR;
                    }

                    var content = Files.readString(blueprintPath);
                    var response = blueprintParent.parent.post(BLUEPRINT_VALIDATE, content);

                    if (response.contains("\"valid\":false")) {
                        return reportValidationFailure(response);
                    }

                    return OutputFormatter.printQuery(response, blueprintParent.parent.outputOptions());
                } catch (IOException e) {
                    System.err.println("Error reading blueprint file: " + e.getMessage());

                    return ExitCode.ERROR;
                }
            }

            @SuppressWarnings({"JBCT-UTIL-02", "JBCT-SEQ-01"})
            private static Integer reportValidationFailure(String response) {
                System.err.println("Validation FAILED");
                var errors = extractJsonStringArray(response, "errors");

                if (!errors.isEmpty()) {
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

                if (start == -1) return result;

                var arrayStart = json.indexOf('[', start);
                var arrayEnd = findMatchingBracket(json, arrayStart);

                if (arrayStart == -1 || arrayEnd == -1) return result;

                var arrayContent = json.substring(arrayStart + 1, arrayEnd);

                parseStringElements(arrayContent, result);

                return result;
            }

            @SuppressWarnings("JBCT-PAT-01")
            private static void parseStringElements(String arrayContent, List<String> result) {
                var inString = false;
                var stringStart = -1;

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

            @SuppressWarnings("JBCT-PAT-01")
            private static int findMatchingBracket(String json, int openIndex) {
                if (openIndex == -1 || openIndex >= json.length()) return -1;

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
                        if (c == openChar) depth++; else if (c == closeChar) {
                            depth--;
                            if (depth == 0) return i;
                        }
                    }
                }

                return -1;
            }
        }

        @Command(name = "deploy", description = "Deploy a blueprint from an artifact in the cluster repository")
        @SuppressWarnings("JBCT-SEQ-01")
        static class DeployArtifactCommand implements Callable<Integer> {
            private static final int POLL_INTERVAL_MS = 2000;

            @CommandLine.ParentCommand
            private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Artifact coordinates (groupId:artifactId:version)")
            private String coords;

            @CommandLine.Option(names = "--wait", description = "Wait for deployment to complete")
            private boolean waitForCompletion;

            @CommandLine.Option(names = "--timeout", description = "Timeout in seconds when waiting", defaultValue = "300")
            private int timeoutSeconds;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var fullCoords = coords.split(":").length == 3
                                 ? coords + ":blueprint"
                                 : coords;
                var body = "{\"artifact\":\"" + escapeJsonValue(fullCoords) + "\"}";
                var response = blueprintParent.parent.post(BLUEPRINT_DEPLOY, body);
                var errorCode = OutputFormatter.checkResponseError(response,
                                                                   blueprintParent.parent.outputOptions(),
                                                                   "Failed to deploy blueprint");

                if (errorCode >= 0) {
                    return errorCode;
                }

                var printResult = OutputFormatter.printQuery(response, blueprintParent.parent.outputOptions());

                if (printResult != ExitCode.SUCCESS || !waitForCompletion) {
                    return printResult;
                }

                return DeploymentWait.blueprintId(response)
                                     .map(this::pollUntilDeployed)
                                     .or(DeployArtifactCommand::reportUnknownBlueprintId);
            }

            private int pollUntilDeployed(String blueprintId) {
                System.out.printf("Waiting for %s deployment to complete (timeout: %ds)...%n",
                                  blueprintId,
                                  timeoutSeconds);
                var exitCode = DeploymentWait.awaitCompletion(() -> queryOverallStatus(blueprintId),
                                                              System.currentTimeMillis() + (long) timeoutSeconds * 1000,
                                                              POLL_INTERVAL_MS);

                return exitCode == ExitCode.SUCCESS
                       ? reportComplete(blueprintId)
                       : reportTimeout(blueprintId);
            }

            /// Reads the blueprint deployment status the node derives from its replicated
            /// `DeploymentMap` — the same state `aether slices status` reports.
            private String queryOverallStatus(String blueprintId) {
                return DeploymentWait.overallStatus(blueprintParent.parent.fetch(BLUEPRINT_STATUS, List.of(blueprintId)));
            }

            private static int reportComplete(String blueprintId) {
                System.out.printf("Deployment complete: %s is active.%n", blueprintId);

                return ExitCode.SUCCESS;
            }

            private int reportTimeout(String blueprintId) {
                System.err.printf("Timeout: %s deployment did not complete within %ds.%n", blueprintId, timeoutSeconds);

                return ExitCode.TIMEOUT;
            }

            /// The deploy call succeeded but the response carried no blueprint id, so there is
            /// nothing to poll. Fail loudly rather than waiting on an unidentified deployment.
            private static int reportUnknownBlueprintId() {
                System.err.println("Cannot wait: deploy response did not report a blueprint id.");

                return ExitCode.ERROR;
            }
        }

        @Command(name = "publish", description = "Publish a blueprint already present in the artifact repository (POST /api/blueprints/publish)")
        static class PublishCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BlueprintCommand blueprintParent;

            @Parameters(index = "0", description = "Artifact coordinates (group:artifact:version)")
            private String coordinates;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var parts = coordinates.split(":");

                if (parts.length != 3) {
                    System.err.println("Invalid coordinates format. Expected: group:artifact:version");

                    return ExitCode.ERROR;
                }

                var fullCoords = coordinates + ":blueprint";
                var body = "{\"artifact\":\"" + escapeJsonValue(fullCoords) + "\"}";
                var response = blueprintParent.parent.post(BLUEPRINT_PUBLISH_ARTIFACT, body);
                var errorCode = OutputFormatter.checkResponseError(response,
                                                                   blueprintParent.parent.outputOptions(),
                                                                   "Failed to publish blueprint");

                if (errorCode >= 0) {
                    return errorCode;
                }

                return OutputFormatter.printAction(response,
                                                   blueprintParent.parent.outputOptions(),
                                                   "Published blueprint: " + coordinates);
            }
        }

        @Command(name = "upload", description = "Upload a blueprint JAR file to the cluster")
        static class UploadCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BlueprintCommand blueprintParent;

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
                    if (!Files.exists(blueprintJarPath)) {
                        System.err.println("File not found: " + blueprintJarPath);

                        return ExitCode.ERROR;
                    }

                    var fileSize = Files.size(blueprintJarPath);

                    if (fileSize > 500 * 1024 * 1024) {
                        System.err.println("File too large: " + fileSize + " bytes (max 500MB)");

                        return ExitCode.ERROR;
                    }

                    var content = Files.readAllBytes(blueprintJarPath);
                    var coordinates = groupId + ":" + artifactId + ":" + version;
                    var uploadResponse = blueprintParent.parent.put(ARTIFACT_PUT,
                                                                    List.of(groupId.replace('.', '/'),
                                                                            artifactId,
                                                                            version,
                                                                            artifactId
                                                                           + "-" + version
                                                                           + "-blueprint.jar"),
                                                                    content,
                                                                    "application/java-archive");
                    var uploadError = OutputFormatter.checkResponseError(uploadResponse,
                                                                         blueprintParent.parent.outputOptions(),
                                                                         "Failed to upload");

                    if (uploadError >= 0) {
                        return uploadError;
                    }

                    var deployBody = "{\"artifact\":\"" + escapeJsonValue(coordinates) + "\"}";
                    var deployResponse = blueprintParent.parent.post(BLUEPRINT_DEPLOY, deployBody);
                    var deployError = OutputFormatter.checkResponseError(deployResponse,
                                                                         blueprintParent.parent.outputOptions(),
                                                                         "Failed to deploy");

                    if (deployError >= 0) {
                        return deployError;
                    }

                    return OutputFormatter.printAction(deployResponse,
                                                       blueprintParent.parent.outputOptions(),
                                                       "Uploaded and deployed " + coordinates);
                } catch (IOException e) {
                    System.err.println("Error reading file: " + e.getMessage());

                    return ExitCode.ERROR;
                }
            }
        }
    }

    @Command(name = "invocation-metrics", description = "Invocation metrics management", subcommands = {InvocationMetricsCommand.ListCommand.class, InvocationMetricsCommand.SlowCommand.class, InvocationMetricsCommand.StrategyCommand.class})
    static class InvocationMetricsCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            var response = parent.fetch(INVOCATION_METRICS);

            OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "list", description = "List all invocation metrics")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private InvocationMetricsCommand metricsParent;

            @Override
            public Integer call() {
                var response = metricsParent.parent.fetch(INVOCATION_METRICS);

                return OutputFormatter.printQuery(response, metricsParent.parent.outputOptions());
            }
        }

        @Command(name = "slow", description = "Show slow invocations")
        static class SlowCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private InvocationMetricsCommand metricsParent;

            @Override
            public Integer call() {
                var response = metricsParent.parent.fetch(INVOCATION_METRICS_SLOW);

                return OutputFormatter.printQuery(response, metricsParent.parent.outputOptions());
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
                option(type).onPresent(this::setStrategy).onEmpty(this::showCurrentStrategy);

                return ExitCode.SUCCESS;
            }

            private void showCurrentStrategy() {
                var response = metricsParent.parent.fetch(INVOCATION_METRICS_STRATEGY_GET);

                OutputFormatter.printQuery(response, metricsParent.parent.outputOptions());
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

                var response = metricsParent.parent.post(INVOCATION_METRICS_STRATEGY_SET, body);

                if (OutputFormatter.isErrorResponse(response) || response.contains("Strategy change")) {
                    System.err.println("Error: Strategy changes at runtime are not yet supported.");
                    System.err.println("The strategy is configured at node startup. Use the GET command to view the current strategy.");
                } else {
                    OutputFormatter.printQuery(response, metricsParent.parent.outputOptions());
                }
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

    @Command(name = "controller", description = "Controller configuration and status", subcommands = {ControllerCommand.ConfigCommand.class, ControllerCommand.StatusCommand.class, ControllerCommand.DecisionsCommand.class, ControllerCommand.EvaluateCommand.class})
    static class ControllerCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
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
                    var response = controllerParent.parent.post(CONTROLLER_CONFIG_SET, body);

                    return OutputFormatter.printQuery(response, controllerParent.parent.outputOptions());
                } else {
                    var response = controllerParent.parent.fetch(CONTROLLER_CONFIG_GET);

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
            @CommandLine.ParentCommand
            private ControllerCommand controllerParent;

            @Override
            public Integer call() {
                var response = controllerParent.parent.fetch(CONTROLLER_STATUS);

                return OutputFormatter.printQuery(response, controllerParent.parent.outputOptions());
            }
        }

        @Command(name = "decisions", description = "Show per-slice scaling decision snapshot")
        static class DecisionsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ControllerCommand controllerParent;

            @Override
            public Integer call() {
                var response = controllerParent.parent.fetch(CONTROLLER_DECISIONS);

                return OutputFormatter.printQuery(response, controllerParent.parent.outputOptions());
            }
        }

        @Command(name = "evaluate", description = "Force controller evaluation")
        static class EvaluateCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ControllerCommand controllerParent;

            @Override
            public Integer call() {
                var response = controllerParent.parent.post(CONTROLLER_EVALUATE, "{}");

                return OutputFormatter.printAction(response,
                                                   controllerParent.parent.outputOptions(),
                                                   "Controller evaluation triggered");
            }
        }
    }

    @Command(name = "alerts", description = "Alert management", subcommands = {AlertsCommand.ListCommand.class, AlertsCommand.ActiveCommand.class, AlertsCommand.HistoryCommand.class, AlertsCommand.ClearCommand.class, AlertsCommand.InjectCommand.class})
    static class AlertsCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            var response = parent.fetch(ALERTS);

            OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "list", description = "List all alerts")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AlertsCommand alertsParent;

            @Override
            public Integer call() {
                var response = alertsParent.parent.fetch(ALERTS);

                return OutputFormatter.printQuery(response, alertsParent.parent.outputOptions());
            }
        }

        @Command(name = "active", description = "Show active alerts only")
        static class ActiveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AlertsCommand alertsParent;

            @Override
            public Integer call() {
                var response = alertsParent.parent.fetch(ALERTS_ACTIVE);

                return OutputFormatter.printQuery(response, alertsParent.parent.outputOptions());
            }
        }

        @Command(name = "history", description = "Show alert history")
        static class HistoryCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AlertsCommand alertsParent;

            @Override
            public Integer call() {
                var response = alertsParent.parent.fetch(ALERTS_HISTORY);

                return OutputFormatter.printQuery(response, alertsParent.parent.outputOptions());
            }
        }

        @Command(name = "clear", description = "Clear all active alerts")
        static class ClearCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AlertsCommand alertsParent;

            @Override
            public Integer call() {
                var response = alertsParent.parent.post(ALERTS_CLEAR, "{}");

                return OutputFormatter.printAction(response, alertsParent.parent.outputOptions(), "Alerts cleared");
            }
        }

        @Command(name = "inject", description = "Inject a synthetic alert entry (operator-driven; visible via 'aether alerts list')")
        static class InjectCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AlertsCommand alertsParent;

            @CommandLine.Option(names = {"-n", "--name"}, description = "Alert name (free-form identifier, required)", required = true)
            private String name;

            @CommandLine.Option(names = {"-s", "--severity"}, description = "Severity: INFO, WARNING, or CRITICAL (required)", required = true)
            private String severity;

            @CommandLine.Option(names = {"-m", "--message"}, description = "Human-readable alert message (required)", required = true)
            private String message;

            @CommandLine.Option(names = {"--metric"}, description = "Optional metric name this alert references")
            private String metric;

            @CommandLine.Option(names = {"--value"}, description = "Optional metric value at the time of injection")
            private Double value;

            @Override
            public Integer call() {
                var body = buildInjectBody();
                var response = alertsParent.parent.post(ALERTS_INJECT, body);

                return OutputFormatter.printQuery(response, alertsParent.parent.outputOptions());
            }

            private String buildInjectBody() {
                var sb = new StringBuilder("{\"name\":\"").append(escapeJson(name))
                                                          .append("\",")
                                                          .append("\"severity\":\"")
                                                          .append(escapeJson(severity))
                                                          .append("\",")
                                                          .append("\"message\":\"")
                                                          .append(escapeJson(message))
                                                          .append("\"");

                if (metric != null) {
                    sb.append(",\"metric\":\"").append(escapeJson(metric)).append("\"");
                }

                if (value != null) {
                    sb.append(",\"value\":").append(value);
                }

                return sb.append("}")
                         .toString();
            }

            private static String escapeJson(String s) {
                return s.replace("\\", "\\\\")
                        .replace("\"", "\\\"");
            }
        }
    }

    @Command(name = "thresholds", description = "Alert threshold management", subcommands = {ThresholdsCommand.ListCommand.class, ThresholdsCommand.SetCommand.class, ThresholdsCommand.RemoveCommand.class})
    static class ThresholdsCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            var response = parent.fetch(THRESHOLDS_LIST);

            OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "list", description = "List all thresholds")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ThresholdsCommand thresholdsParent;

            @Override
            public Integer call() {
                var response = thresholdsParent.parent.fetch(THRESHOLDS_LIST);

                return OutputFormatter.printQuery(response, thresholdsParent.parent.outputOptions());
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
                var response = thresholdsParent.parent.post(THRESHOLD_SET, body);

                return OutputFormatter.printAction(response,
                                                   thresholdsParent.parent.outputOptions(),
                                                   "Threshold set for " + metric);
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
                var response = thresholdsParent.parent.delete(THRESHOLD_DELETE, List.of(metric));

                return OutputFormatter.printAction(response,
                                                   thresholdsParent.parent.outputOptions(),
                                                   "Threshold removed for " + metric);
            }
        }
    }

    @Command(name = "traces", description = "View distributed invocation traces", subcommands = {TracesCommand.ListCommand.class, TracesCommand.GetCommand.class, TracesCommand.StatsCommand.class, TracesCommand.InjectCommand.class})
    static class TracesCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "list", description = "List recent traces")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private TracesCommand tracesParent;

            @CommandLine.Option(names = {"-l", "--limit"}, description = "Max traces", defaultValue = "100")
            private int limit;

            @CommandLine.Option(names = {"-m", "--method"}, description = "Filter by method")
            private String method;

            @CommandLine.Option(names = {"-s", "--status"}, description = "Filter by status (SUCCESS/FAILURE)")
            private String status;

            @Override
            public Integer call() {
                var response = tracesParent.parent.fetch(TRACES_QUERY, List.of(), buildTracesQueryString());

                return OutputFormatter.printQuery(response, tracesParent.parent.outputOptions());
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private String buildTracesQueryString() {
                var sb = new StringBuilder("limit=").append(limit);

                option(method).onPresent(m -> sb.append("&method=")
                                                .append(m));
                option(status).onPresent(s -> sb.append("&status=")
                                                .append(s));

                return sb.toString();
            }
        }

        @Command(name = "get", description = "Get traces for a request ID")
        static class GetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private TracesCommand tracesParent;

            @Parameters(index = "0", description = "Request ID")
            private String requestId;

            @Override
            public Integer call() {
                var response = tracesParent.parent.fetch(TRACE_BY_REQUEST_ID, List.of(requestId));

                return OutputFormatter.printQuery(response, tracesParent.parent.outputOptions());
            }
        }

        @Command(name = "stats", description = "Show trace statistics")
        static class StatsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private TracesCommand tracesParent;

            @Override
            public Integer call() {
                var response = tracesParent.parent.fetch(TRACES_STATS);

                return OutputFormatter.printQuery(response, tracesParent.parent.outputOptions());
            }
        }

        @Command(name = "inject", description = "Inject a synthetic trace entry (operator-driven; visible via 'aether traces list')")
        static class InjectCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private TracesCommand tracesParent;

            @CommandLine.Option(names = {"--operation"}, description = "Operation/callee name for the trace (required)", required = true)
            private String operation;

            @CommandLine.Option(names = {"-d", "--duration-ms"}, description = "Duration in milliseconds (default 10)")
            private Long durationMs;

            @CommandLine.Option(names = {"--depth"}, description = "Call depth (default 0)")
            private Integer depth;

            @CommandLine.Option(names = {"--request-id"}, description = "Request id (UUID generated if omitted)")
            private String requestId;

            @CommandLine.Option(names = {"--trace-id"}, description = "Trace id (UUID generated if omitted)")
            private String traceId;

            @Override
            public Integer call() {
                var body = buildInjectBody();
                var response = tracesParent.parent.post(TRACES_INJECT, body);

                return OutputFormatter.printQuery(response, tracesParent.parent.outputOptions());
            }

            private String buildInjectBody() {
                var sb = new StringBuilder("{\"operation\":\"").append(escapeJson(operation)).append("\"");

                if (durationMs != null) {
                    sb.append(",\"durationMs\":").append(durationMs);
                }

                if (depth != null) {
                    sb.append(",\"depth\":").append(depth);
                }

                if (requestId != null) {
                    sb.append(",\"requestId\":\"").append(escapeJson(requestId)).append("\"");
                }

                if (traceId != null) {
                    sb.append(",\"traceId\":\"").append(escapeJson(traceId)).append("\"");
                }

                return sb.append("}")
                         .toString();
            }

            private static String escapeJson(String s) {
                return s.replace("\\", "\\\\")
                        .replace("\"", "\\\"");
            }
        }
    }

    @Command(name = "dht", description = "DHT inspection and operations", subcommands = {DhtCommand.ReplicationMapCommand.class})
    static class DhtCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "replication-map", description = "Show DHT replication map (which keys live on which nodes)")
        static class ReplicationMapCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private DhtCommand dhtParent;

            @CommandLine.Option(names = {"-l", "--limit"}, description = "Max entries to return (default 100)")
            private Integer limit;

            @CommandLine.Option(names = {"-p", "--prefix"}, description = "Only include keys matching this prefix")
            private String prefix;

            @Override
            public Integer call() {
                var response = dhtParent.parent.fetch(DHT_REPLICATION_MAP, List.of(), buildQueryString());

                return OutputFormatter.printQuery(response, dhtParent.parent.outputOptions());
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private String buildQueryString() {
                var sb = new StringBuilder();

                option(limit).onPresent(v -> appendParam(sb, "limit", String.valueOf(v)));
                option(prefix).onPresent(v -> appendParam(sb, "prefix", v));

                return sb.toString();
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private static void appendParam(StringBuilder sb, String name, String value) {
                if (sb.length() > 0) {
                    sb.append("&");
                }

                sb.append(name).append("=").append(value);
            }
        }
    }

    @Command(name = "observability", description = "Manage observability configuration", subcommands = {ObservabilityCommand.DepthListCommand.class, ObservabilityCommand.DepthSetCommand.class, ObservabilityCommand.DepthRemoveCommand.class, ObservabilityCommand.ConfigListCommand.class, ObservabilityCommand.ConfigGetCommand.class, ObservabilityCommand.ConfigSetCommand.class, ObservabilityCommand.ConfigRemoveCommand.class})
    static class ObservabilityCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "depth", description = "List depth overrides")
        static class DepthListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ObservabilityCommand obsParent;

            @Override
            public Integer call() {
                var response = obsParent.parent.fetch(OBSERVABILITY_DEPTH_GET);

                return OutputFormatter.printQuery(response, obsParent.parent.outputOptions());
            }
        }

        @Command(name = "depth-set", description = "Set depth threshold for a method")
        static class DepthSetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ObservabilityCommand obsParent;

            @Parameters(index = "0", description = "Artifact#method (e.g., org.example:my-slice:1.0.0#processOrder)")
            private String target;

            @Parameters(index = "1", description = "Depth threshold")
            private int depthThreshold;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var hashIndex = target.indexOf('#');

                if (hashIndex == -1) {
                    System.err.println("Invalid format. Use: artifact#method");

                    return ExitCode.ERROR;
                }

                var artifact = target.substring(0, hashIndex);
                var method = target.substring(hashIndex + 1);
                var body = buildDepthSetBody(artifact, method);
                var response = obsParent.parent.post(OBSERVABILITY_DEPTH_SET, body);

                return OutputFormatter.printAction(response,
                                                   obsParent.parent.outputOptions(),
                                                   "Depth threshold set for " + target);
            }

            private String buildDepthSetBody(String artifact, String method) {
                return "{\"artifact\":\"" + artifact
                     + "\",\"method\":\"" + method
                     + "\",\"depthThreshold\":" + depthThreshold
                     + "}";
            }
        }

        @Command(name = "depth-remove", description = "Remove depth override")
        static class DepthRemoveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ObservabilityCommand obsParent;

            @Parameters(index = "0", description = "Artifact#method")
            private String target;

            @Override
            public Integer call() {
                var hashIndex = target.indexOf('#');

                if (hashIndex == -1) {
                    System.err.println("Invalid format. Use: artifact#method");

                    return ExitCode.ERROR;
                }

                var artifact = target.substring(0, hashIndex);
                var method = target.substring(hashIndex + 1);
                var response = obsParent.parent.delete(OBSERVABILITY_DEPTH_DELETE, List.of(artifact, method));

                return OutputFormatter.printAction(response,
                                                   obsParent.parent.outputOptions(),
                                                   "Depth override removed for " + target);
            }
        }

        @Command(name = "config", description = "List effective observability config (baseline|configured|darkened) per injection point")
        static class ConfigListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ObservabilityCommand obsParent;

            @Override
            public Integer call() {
                var response = obsParent.parent.fetch(OBSERVABILITY_CONFIG_GET);

                return OutputFormatter.printQuery(response, obsParent.parent.outputOptions());
            }
        }

        @Command(name = "config-get", description = "Show effective observability config for one artifact/method")
        static class ConfigGetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ObservabilityCommand obsParent;

            @Parameters(index = "0", description = "Artifact base (groupId:artifactId), or * for the global scope")
            private String artifact;

            @Parameters(index = "1", description = "Method name, or * for the artifact scope")
            private String method;

            @Override
            public Integer call() {
                var response = obsParent.parent.fetch(OBSERVABILITY_CONFIG_GET_ONE, List.of(artifact, method));

                return OutputFormatter.printQuery(response, obsParent.parent.outputOptions());
            }
        }

        @Command(name = "config-set", description = "Set the observability config snapshot for a scope (absent facet flags = off)")
        static class ConfigSetCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ObservabilityCommand obsParent;

            @Parameters(index = "0", description = "Artifact base (groupId:artifactId), or * for the global scope")
            private String artifact;

            @Parameters(index = "1", description = "Method name, or * for the artifact scope")
            private String method;

            @CommandLine.Option(names = {"--logging"}, description = "Enable the logging facet")
            private boolean logging;

            @CommandLine.Option(names = {"--metrics"}, description = "Enable the metrics (counting) facet")
            private boolean metrics;

            @CommandLine.Option(names = {"--tracing"}, description = "Enable the tracing facet")
            private boolean tracing;

            @CommandLine.Option(names = {"--spans"}, description = "Enable the spans facet (reserved, no body yet; #304)")
            private boolean spans;

            @CommandLine.Option(names = {"--depth"}, description = "Logging-ladder depth threshold (default 1)", defaultValue = "1")
            private int depth;

            @Override
            public Integer call() {
                var body = buildConfigSetBody();
                var response = obsParent.parent.post(OBSERVABILITY_CONFIG_SET, body);

                return OutputFormatter.printAction(response,
                                                   obsParent.parent.outputOptions(),
                                                   "Observability config set for " + artifact + "/" + method);
            }

            private String buildConfigSetBody() {
                return "{\"artifact\":\"" + artifact
                     + "\",\"method\":\"" + method
                     + "\",\"logging\":" + logging
                     + ",\"metrics\":" + metrics
                     + ",\"spans\":" + spans
                     + ",\"tracing\":" + tracing
                     + ",\"depth\":" + depth
                     + "}";
            }
        }

        @Command(name = "config-remove", description = "Remove the observability config at a scope (resolution falls back per hierarchy)")
        static class ConfigRemoveCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ObservabilityCommand obsParent;

            @Parameters(index = "0", description = "Artifact base (groupId:artifactId), or * for the global scope")
            private String artifact;

            @Parameters(index = "1", description = "Method name, or * for the artifact scope")
            private String method;

            @Override
            public Integer call() {
                var response = obsParent.parent.delete(OBSERVABILITY_CONFIG_DELETE, List.of(artifact, method));

                return OutputFormatter.printAction(response,
                                                   obsParent.parent.outputOptions(),
                                                   "Observability config removed for " + artifact + "/" + method);
            }
        }
    }

    @Command(name = "logging", description = "Runtime log level management", subcommands = {LoggingCommand.ListCommand.class, LoggingCommand.SetCommand.class, LoggingCommand.ResetCommand.class})
    static class LoggingCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            var response = parent.fetch(LOG_LEVELS_LIST);

            OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "list", description = "List runtime-configured log levels")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private LoggingCommand loggingParent;

            @Override
            public Integer call() {
                var response = loggingParent.parent.fetch(LOG_LEVELS_LIST);

                return OutputFormatter.printQuery(response, loggingParent.parent.outputOptions());
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
                var response = loggingParent.parent.post(LOG_LEVEL_SET, body);

                return OutputFormatter.printAction(response,
                                                   loggingParent.parent.outputOptions(),
                                                   "Log level set: " + logger + " = " + level.toUpperCase());
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
                var response = loggingParent.parent.delete(LOG_LEVEL_RESET, List.of(logger));

                return OutputFormatter.printAction(response,
                                                   loggingParent.parent.outputOptions(),
                                                   "Logger reset: " + logger);
            }
        }
    }

    @Command(name = "config", description = "Dynamic configuration management", subcommands = {ConfigCommand.ListCommand.class, ConfigCommand.OverridesCommand.class, ConfigCommand.SetCommand.class, ConfigCommand.RemoveCommand.class})
    static class ConfigCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            var response = parent.fetch(CONFIG_LIST);

            OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "list", description = "Show all configuration (base + overrides)")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ConfigCommand configParent;

            @Override
            public Integer call() {
                var response = configParent.parent.fetch(CONFIG_LIST);

                return OutputFormatter.printQuery(response, configParent.parent.outputOptions());
            }
        }

        @Command(name = "overrides", description = "Show only dynamic overrides from KV store")
        static class OverridesCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ConfigCommand configParent;

            @Override
            public Integer call() {
                var response = configParent.parent.fetch(CONFIG_OVERRIDES);

                return OutputFormatter.printQuery(response, configParent.parent.outputOptions());
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
                var response = configParent.parent.post(CONFIG_SET, body);

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

                return OutputFormatter.printAction(response,
                                                   configParent.parent.outputOptions(),
                                                   "Config removed: " + key);
            }

            @SuppressWarnings("JBCT-UTIL-02")
            private String fetchRemoveResponse() {
                return option(nodeId).map(id -> configParent.parent.delete(CONFIG_NODE_DELETE,
                                                                           List.of(id, key)))
                             .or(() -> configParent.parent.delete(CONFIG_DELETE,
                                                                  List.of(key)));
            }
        }
    }

    @Command(name = "scheduled-tasks", description = "Scheduled task management", subcommands = {ScheduledTasksCommand.ListCommand.class, ScheduledTasksCommand.GetCommand.class, ScheduledTasksCommand.PauseCommand.class, ScheduledTasksCommand.ResumeCommand.class, ScheduledTasksCommand.TriggerCommand.class, ScheduledTasksCommand.InjectCommand.class, ScheduledTasksCommand.ExecutionsByNodeCommand.class})
    static class ScheduledTasksCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            var response = parent.fetch(SCHEDULED_TASKS_LIST);

            OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "list", description = "List all scheduled tasks")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ScheduledTasksCommand tasksParent;

            @Override
            public Integer call() {
                var response = tasksParent.parent.fetch(SCHEDULED_TASKS_LIST);

                return OutputFormatter.printQuery(response, tasksParent.parent.outputOptions());
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
                var response = tasksParent.parent.fetch(SCHEDULED_TASKS_BY_SECTION, List.of(configSection));

                return OutputFormatter.printQuery(response, tasksParent.parent.outputOptions());
            }
        }

        @Command(name = "pause", description = "Pause a scheduled task")
        static class PauseCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ScheduledTasksCommand tasksParent;

            @Parameters(index = "0", description = "Config section")
            private String configSection;

            @Parameters(index = "1", description = "Artifact coordinates (groupId:artifactId:version)")
            private String artifact;

            @Parameters(index = "2", description = "Method name")
            private String method;

            @Override
            public Integer call() {
                var response = tasksParent.parent.post(SCHEDULED_TASK_PAUSE,
                                                       List.of(configSection, artifact, method),
                                                       "");

                return OutputFormatter.printAction(response,
                                                   tasksParent.parent.outputOptions(),
                                                   "Task paused: " + method);
            }
        }

        @Command(name = "resume", description = "Resume a paused scheduled task")
        static class ResumeCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ScheduledTasksCommand tasksParent;

            @Parameters(index = "0", description = "Config section")
            private String configSection;

            @Parameters(index = "1", description = "Artifact coordinates (groupId:artifactId:version)")
            private String artifact;

            @Parameters(index = "2", description = "Method name")
            private String method;

            @Override
            public Integer call() {
                var response = tasksParent.parent.post(SCHEDULED_TASK_RESUME,
                                                       List.of(configSection, artifact, method),
                                                       "");

                return OutputFormatter.printAction(response,
                                                   tasksParent.parent.outputOptions(),
                                                   "Task resumed: " + method);
            }
        }

        @Command(name = "trigger", description = "Manually trigger a scheduled task")
        static class TriggerCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ScheduledTasksCommand tasksParent;

            @Parameters(index = "0", description = "Config section")
            private String configSection;

            @Parameters(index = "1", description = "Artifact coordinates (groupId:artifactId:version)")
            private String artifact;

            @Parameters(index = "2", description = "Method name")
            private String method;

            @Override
            public Integer call() {
                var response = tasksParent.parent.post(SCHEDULED_TASK_TRIGGER,
                                                       List.of(configSection, artifact, method),
                                                       "");

                return OutputFormatter.printAction(response,
                                                   tasksParent.parent.outputOptions(),
                                                   "Task triggered: " + method);
            }
        }

        @Command(name = "inject", description = "Synchronously fire a scheduled task and advance its lastExecutionTime (dev-mode only)")
        static class InjectCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ScheduledTasksCommand tasksParent;

            @CommandLine.Option(names = {"--section"}, description = "Config section (required)", required = true)
            private String section;

            @CommandLine.Option(names = {"--artifact"}, description = "Artifact coordinates groupId:artifactId (required)", required = true)
            private String artifact;

            @CommandLine.Option(names = {"--method"}, description = "Method name (required)", required = true)
            private String method;

            @Override
            public Integer call() {
                var body = "{\"section\":\"" + escapeJson(section)
                         + "\",\"artifact\":\"" + escapeJson(artifact)
                         + "\",\"method\":\"" + escapeJson(method)
                         + "\"}";
                var response = tasksParent.parent.post(SCHEDULED_TASK_INJECT, body);

                return OutputFormatter.printQuery(response, tasksParent.parent.outputOptions());
            }

            private static String escapeJson(String s) {
                return s.replace("\\", "\\\\")
                        .replace("\"", "\\\"");
            }
        }

        @Command(name = "executions-by-node", description = "Get per-node execution counts for a scheduled task (P-NEW-H)")
        static class ExecutionsByNodeCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private ScheduledTasksCommand tasksParent;

            @Parameters(index = "0", description = "Config section")
            private String configSection;

            @Parameters(index = "1", description = "Artifact coordinates (groupId:artifactId:version)")
            private String artifact;

            @Parameters(index = "2", description = "Method name")
            private String method;

            @Override
            public Integer call() {
                var response = tasksParent.parent.fetch(SCHEDULED_TASK_EXECUTIONS_BY_NODE,
                                                        List.of(configSection, artifact, method));

                return OutputFormatter.printQuery(response, tasksParent.parent.outputOptions());
            }
        }
    }

    @Command(name = "events", description = "Show cluster events")
    static class EventsCommand implements Callable<Integer> {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @CommandLine.Option(names = {"--since"}, description = "Show events since ISO-8601 timestamp (e.g. 2024-01-15T10:30:00Z)")
        private String since;

        @Override
        public Integer call() {
            var response = parent.fetch(EVENTS, List.of(), buildEventsQuery());

            return OutputFormatter.printQuery(response, parent.outputOptions());
        }

        private String buildEventsQuery() {
            return option(since).map(s -> "since=" + s)
                         .or("");
        }
    }

    @Command(name = "backups", description = "Manage cluster backups", subcommands = {BackupCommand.TriggerCommand.class, BackupCommand.ListCommand.class, BackupCommand.RestoreCommand.class})
    static class BackupCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "trigger", description = "Trigger a manual backup")
        static class TriggerCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BackupCommand backupParent;

            @Override
            public Integer call() {
                var response = backupParent.parent.post(BACKUP_TRIGGER, "{}");

                return OutputFormatter.printAction(response, backupParent.parent.outputOptions(), "Backup triggered");
            }
        }

        @Command(name = "list", description = "List available backups")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BackupCommand backupParent;

            @Override
            public Integer call() {
                var response = backupParent.parent.fetch(BACKUPS_LIST);

                return OutputFormatter.printQuery(response, backupParent.parent.outputOptions());
            }
        }

        @Command(name = "restore", description = "Restore from a backup")
        static class RestoreCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BackupCommand backupParent;

            @Parameters(index = "0", description = "Git commit ID to restore from")
            private String commitId;

            @CommandLine.Option(names = {"--yes", "--force"}, description = "Skip interactive confirmation")
            private boolean skipConfirmation;

            @Override
            public Integer call() {
                if (!DestructiveAction.destructiveAction().confirm(skipConfirmation,
                                                                   "This will restore cluster state from backup " + commitId
                                                                  + ", overwriting current state.")) {
                    System.out.println("Aborted.");

                    return ExitCode.SUCCESS;
                }

                var response = backupParent.parent.post(BACKUP_RESTORE, "{\"commit\":\"" + commitId + "\"}");

                return OutputFormatter.printAction(response,
                                                   backupParent.parent.outputOptions(),
                                                   "Restore initiated from " + commitId);
            }
        }
    }

    /// Singular `aether backup` parent — operator-facing alias of `aether backups`
    /// with verb-style `create` / `restore` subcommands as called out in
    /// `aether/docs/internal/production-readiness-followup-2026-05-21.md` P-NEW-C
    /// (test target `TC-NEW-G4-kv-store-backup`). Shares the same REST routes
    /// (`POST /api/backups`, `POST /api/backups/restore`, `GET /api/backups`) as
    /// the existing plural form — this is an additional CLI surface, not a new
    /// server endpoint.
    @Command(name = "backup", description = "Operator-facing backup commands (create/restore/list)", subcommands = {BackupSingularCommand.CreateCommand.class, BackupSingularCommand.RestoreCommand.class, BackupSingularCommand.ListCommand.class})
    static class BackupSingularCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "create", description = "Create a new backup (synchronous; --wait polls for visibility)")
        @SuppressWarnings({"JBCT-PAT-01", "JBCT-SEQ-01"})
        static class CreateCommand implements Callable<Integer> {
            private static final int POLL_INTERVAL_MS = 1000;

            @CommandLine.ParentCommand
            private BackupSingularCommand backupParent;

            @CommandLine.Option(names = "--wait", description = "Poll /api/backups until the new backup appears (or --timeout elapses)")
            private boolean waitForCompletion;

            @CommandLine.Option(names = "--timeout", description = "Timeout in seconds when --wait is set", defaultValue = "60")
            private int timeoutSeconds;

            @Override
            public Integer call() {
                var existingCount = waitForCompletion
                                    ? countExistingBackups()
                                    : -1;
                var response = backupParent.parent.post(BACKUP_TRIGGER, "{}");
                var result = OutputFormatter.printAction(response, backupParent.parent.outputOptions(), "Backup created");

                if (result != ExitCode.SUCCESS || !waitForCompletion) {
                    return result;
                }

                return pollUntilBackupVisible(existingCount);
            }

            private int countExistingBackups() {
                var response = backupParent.parent.fetch(BACKUPS_LIST);

                return countBackupEntries(response);
            }

            @SuppressWarnings("JBCT-EX-01")
            private int pollUntilBackupVisible(int baselineCount) {
                System.out.printf("Waiting for backup to appear in /api/backups (timeout: %ds)...%n", timeoutSeconds);
                var deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;

                while (System.currentTimeMillis() < deadline) {
                    var currentCount = countExistingBackups();

                    if (currentCount > baselineCount) {
                        System.out.printf("Backup visible: %d entries in /api/backups.%n", currentCount);

                        return ExitCode.SUCCESS;
                    }

                    sleepQuietly();
                }

                System.err.printf("Timeout: backup did not become visible within %ds.%n", timeoutSeconds);

                return ExitCode.TIMEOUT;
            }

            @SuppressWarnings("JBCT-EX-01")
            private static void sleepQuietly() {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }

            /// Best-effort count of `"commitId"` occurrences in the BACKUPS_LIST JSON
            /// envelope. Falls back to 0 on parse error so a missing/empty list does
            /// not collide with the "saw new entry" condition.
            static int countBackupEntries(String response) {
                if (response == null || response.isBlank() || response.contains("\"error\"")) {
                    return 0;
                }

                var marker = "\"commitId\"";
                var idx = 0;
                var count = 0;

                while ((idx = response.indexOf(marker, idx)) >= 0) {
                    count++;
                    idx += marker.length();
                }

                return count;
            }
        }

        @Command(name = "restore", description = "Restore the cluster from a backup commit (synchronous)")
        static class RestoreCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BackupSingularCommand backupParent;

            @Parameters(index = "0", description = "Git commit ID to restore from")
            private String commitId;

            @CommandLine.Option(names = "--wait", description = "Reserved for future async-restore support (currently a no-op — restore is synchronous server-side)")
            private boolean waitForCompletion;

            @CommandLine.Option(names = {"--yes", "--force"}, description = "Skip interactive confirmation")
            private boolean skipConfirmation;

            @Override
            public Integer call() {
                if (!DestructiveAction.destructiveAction().confirm(skipConfirmation,
                                                                   "This will restore cluster state from backup " + commitId
                                                                  + ", overwriting current state.")) {
                    System.out.println("Aborted.");

                    return ExitCode.SUCCESS;
                }

                var response = backupParent.parent.post(BACKUP_RESTORE, buildRestoreBody());

                return OutputFormatter.printAction(response,
                                                   backupParent.parent.outputOptions(),
                                                   "Restore initiated from " + commitId);
            }

            String buildRestoreBody() {
                return "{\"commit\":\"" + escapeJsonValue(commitId) + "\"}";
            }
        }

        @Command(name = "list", description = "List available backups")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private BackupSingularCommand backupParent;

            @Override
            public Integer call() {
                var response = backupParent.parent.fetch(BACKUPS_LIST);

                return OutputFormatter.printQuery(response, backupParent.parent.outputOptions());
            }
        }
    }

    /// #525: `health` and `endpoints` subcommands removed. Per-worker health and per-worker
    /// endpoints are never published to consensus, so those commands could only ever fail. The
    /// routes remain declared and answer an honest 501 (see NotImplementedRoutes) for anyone
    /// calling the HTTP API directly; the CLI simply stops offering what it cannot deliver.
    @Command(name = "workers", description = "Inspect worker nodes", subcommands = {WorkersCommand.ListCommand.class})
    static class WorkersCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "list", description = "List worker nodes and the community each belongs to")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private WorkersCommand workersParent;

            @Override
            public Integer call() {
                var response = workersParent.parent.fetch(WORKERS_LIST);

                return OutputFormatter.printQuery(response, workersParent.parent.outputOptions());
            }
        }
    }

    @Command(name = "schema", description = "Manage datasource schemas", subcommands = {SchemaCommand.StatusCommand.class, SchemaCommand.HistoryCommand.class, SchemaCommand.MigrateCommand.class, SchemaCommand.UndoCommand.class, SchemaCommand.BaselineCommand.class, SchemaCommand.RetryCommand.class})
    static class SchemaCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        /// `OWNING BLUEPRINT` is the blueprint whose migrations claim this datasource (#542/#550).
        /// It is the field that says whose slices a non-COMPLETED status is holding: activation is
        /// withheld from the slices of THIS blueprint only, never from an unrelated one.
        ///
        /// `HELD SLICES` (#760/#724 review round 2 item k) names the actual artifacts a blocking
        /// status is withholding — `OWNING BLUEPRINT` alone answers "whose deploy is stuck", not
        /// "which of its slices". The REST response already carried `heldSlices` (`SchemaRoutes`)
        /// with no CLI surface for it; feature-catalog.md described the value as "surfaced ... in the
        /// OWNING BLUEPRINT column", which was never true — `heldSlices` is a distinct field the table
        /// never rendered. This column, not a doc rewrite, is the fix: the operator-facing diagnosis
        /// #760 exists for needs the actual slice names, not just the owner. Renders via
        /// `OutputFormatter.navigateToField`'s non-value-node fallback (`JsonNode.toString()`), so a
        /// held row prints as a bracketed JSON array (e.g. `["org.example:my-app-slice:1.0.0"]`)
        /// rather than a comma-joined list — acceptable for an operator-diagnostic column, not
        /// dressed up as more than it is.
        ///
        /// Package-visible (like `SchemaRoutes.baselineDatasource`) so `SchemaStatusTableTest` can
        /// render the real column set over a real response body: a wrong `jsonPath` renders a blank
        /// column rather than failing, so only a test that RUNS the renderer can catch it.
        static final List<OutputFormatter.Column> SCHEMA_STATUS_COLUMNS = List.of(new OutputFormatter.Column("DATASOURCE",
                                                                                                             "datasource",
                                                                                                             20),
                                                                                  new OutputFormatter.Column("STATUS",
                                                                                                             "status",
                                                                                                             10),
                                                                                  new OutputFormatter.Column("HELD SLICES",
                                                                                                             "heldSlices",
                                                                                                             90),
                                                                                  new OutputFormatter.Column("VERSION",
                                                                                                             "currentVersion",
                                                                                                             7),
                                                                                  new OutputFormatter.Column("LAST MIGRATION",
                                                                                                             "lastMigration",
                                                                                                             32),
                                                                                  new OutputFormatter.Column("OWNING BLUEPRINT",
                                                                                                             "owningBlueprint",
                                                                                                             45));

        static final OutputFormatter.TableSpec SCHEMA_STATUS_ALL_TABLE = new OutputFormatter.TableSpec("Schema Status",
                                                                                                       SCHEMA_STATUS_COLUMNS,
                                                                                                       "datasources");

        // JBCT-RET-08: single-datasource responses are a bare object, so this table has no
        // array-path (matching RESOLVE_TABLE); Option-ifying TableSpec.arrayPath globally is
        // disproportionate
        @SuppressWarnings("JBCT-RET-08")
        static final OutputFormatter.TableSpec SCHEMA_STATUS_ONE_TABLE = new OutputFormatter.TableSpec("Schema Status",
                                                                                                       SCHEMA_STATUS_COLUMNS,
                                                                                                       null);

        @Contract
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "status", description = "Show schema status for all datasources")
        static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private SchemaCommand schemaParent;

            @Parameters(index = "0", description = "Datasource name (optional)", arity = "0..1")
            private String datasource;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var output = schemaParent.parent.outputOptions();
                var response = datasource != null
                               ? schemaParent.parent.fetch(SCHEMA_STATUS_ONE, List.of(datasource))
                               : schemaParent.parent.fetch(SCHEMA_STATUS_ALL);
                var errorCode = OutputFormatter.checkResponseError(response, output, "Failed to get schema status");

                if (errorCode >= 0) {
                    return errorCode;
                }

                return OutputFormatter.printQuery(response,
                                                  output,
                                                  datasource != null
                                                  ? SCHEMA_STATUS_ONE_TABLE
                                                  : SCHEMA_STATUS_ALL_TABLE);
            }
        }

        @Command(name = "history", description = "Show migration history for a datasource")
        static class HistoryCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private SchemaCommand schemaParent;

            @Parameters(index = "0", description = "Datasource name")
            private String datasource;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var output = schemaParent.parent.outputOptions();
                var response = schemaParent.parent.fetch(SCHEMA_HISTORY, List.of(datasource));
                var errorCode = OutputFormatter.checkResponseError(response, output, "Failed to get schema history");

                if (errorCode >= 0) {
                    return errorCode;
                }

                return OutputFormatter.printQuery(response, output, SCHEMA_STATUS_ONE_TABLE);
            }
        }

        @Command(name = "migrate", description = "Trigger manual migration for a datasource")
        static class MigrateCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private SchemaCommand schemaParent;

            @Parameters(index = "0", description = "Datasource name")
            private String datasource;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var output = schemaParent.parent.outputOptions();
                var response = schemaParent.parent.post(SCHEMA_MIGRATE, List.of(datasource), "{}");
                var errorCode = OutputFormatter.checkResponseError(response, output, "Failed to trigger migration");

                if (errorCode >= 0) {
                    return errorCode;
                }

                return OutputFormatter.printAction(response, output, "Migration triggered for " + datasource);
            }
        }

        @Command(name = "undo", description = "Undo migrations to target version")
        static class UndoCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private SchemaCommand schemaParent;

            @Parameters(index = "0", description = "Datasource name")
            private String datasource;

            @CommandLine.Option(names = {"-v", "--version"}, required = true, description = "Target version")
            private int targetVersion;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var output = schemaParent.parent.outputOptions();
                var response = schemaParent.parent.post(SCHEMA_UNDO,
                                                        List.of(datasource),
                                                        "targetVersion=" + targetVersion,
                                                        "{}");
                var errorCode = OutputFormatter.checkResponseError(response, output, "Failed to undo migrations");

                if (errorCode >= 0) {
                    return errorCode;
                }

                return OutputFormatter.printAction(response,
                                                   output,
                                                   "Undo to version " + targetVersion + " for " + datasource);
            }
        }

        @Command(name = "baseline", description = "Baseline a datasource at a version")
        static class BaselineCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private SchemaCommand schemaParent;

            @Parameters(index = "0", description = "Datasource name")
            private String datasource;

            @CommandLine.Option(names = {"-v", "--version"}, required = true, description = "Baseline version")
            private int version;

            /// Baselining inherits the existing record's owning blueprint, so a datasource with no
            /// schema record cannot be baselined (#551) — the server answers with an error rather
            /// than fabricating an unowned record. The error guard is what makes that visible:
            /// without it the canned success line would be printed over the failure body.
            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var output = schemaParent.parent.outputOptions();
                var response = schemaParent.parent.post(SCHEMA_BASELINE, List.of(datasource), "version=" + version, "{}");
                var errorCode = OutputFormatter.checkResponseError(response, output, "Failed to baseline datasource");

                if (errorCode >= 0) {
                    return errorCode;
                }

                return OutputFormatter.printAction(response,
                                                   output,
                                                   "Baseline set at version " + version + " for " + datasource);
            }
        }

        @Command(name = "retry", description = "Retry a failed schema migration (clears the activation hold on the owning blueprint's slices)")
        static class RetryCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private SchemaCommand schemaParent;

            @Parameters(index = "0", description = "Datasource name")
            private String datasource;

            @Override
            @SuppressWarnings("JBCT-UTIL-02")
            public Integer call() {
                var output = schemaParent.parent.outputOptions();
                var response = schemaParent.parent.post(SCHEMA_RETRY, List.of(datasource), "{}");
                var errorCode = OutputFormatter.checkResponseError(response, output, "Failed to retry migration");

                if (errorCode >= 0) {
                    return errorCode;
                }

                return OutputFormatter.printAction(response, output, "Migration retry triggered for " + datasource);
            }
        }
    }

    @Command(name = "ab-tests", description = "Manage A/B test deployments", subcommands = {AbTestCommand.CreateCommand.class, AbTestCommand.ListCommand.class, AbTestCommand.StatusCommand.class, AbTestCommand.MetricsCommand.class, AbTestCommand.ConcludeCommand.class})
    static class AbTestCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        @Command(name = "create", description = "Create a new A/B test")
        static class CreateCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AbTestCommand abParent;

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

            @Override
            public Integer call() {
                var body = buildCreateBody();
                var response = abParent.parent.post(AB_TEST_CREATE, body);

                return OutputFormatter.printQuery(response, abParent.parent.outputOptions());
            }

            private String buildCreateBody() {
                return "{\"artifactBase\":\"" + artifactBase
                     + "\","
                     + "\"versionA\":\"" + versionA
                     + "\","
                     + "\"versionB\":\"" + versionB
                     + "\","
                     + "\"split\":\"" + split
                     + "\","
                     + "\"instancesPerVariant\":" + instances
                     + "}";
            }
        }

        @Command(name = "list", description = "List A/B tests")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AbTestCommand abParent;

            @Override
            public Integer call() {
                var response = abParent.parent.fetch(AB_TEST_LIST);

                return OutputFormatter.printQuery(response, abParent.parent.outputOptions());
            }
        }

        @Command(name = "status", description = "Show A/B test status")
        static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AbTestCommand abParent;

            @Parameters(index = "0", description = "Test ID")
            private String testId;

            @Override
            public Integer call() {
                var response = abParent.parent.fetch(AB_TEST_GET, List.of(testId));

                return OutputFormatter.printQuery(response, abParent.parent.outputOptions());
            }
        }

        @Command(name = "metrics", description = "Show A/B test comparison metrics")
        static class MetricsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AbTestCommand abParent;

            @Parameters(index = "0", description = "Test ID")
            private String testId;

            @Override
            public Integer call() {
                var response = abParent.parent.fetch(AB_TEST_METRICS, List.of(testId));

                return OutputFormatter.printQuery(response, abParent.parent.outputOptions());
            }
        }

        @Command(name = "conclude", description = "Conclude A/B test and select winner")
        static class ConcludeCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private AbTestCommand abParent;

            @Parameters(index = "0", description = "Test ID")
            private String testId;

            @CommandLine.Option(names = {"--winner"}, description = "Winning variant: A or B", required = true)
            private String winner;

            @Override
            public Integer call() {
                var body = "{\"winner\":\"" + winner + "\"}";
                var response = abParent.parent.post(AB_TEST_CONCLUDE, List.of(testId), body);

                return OutputFormatter.printAction(response,
                                                   abParent.parent.outputOptions(),
                                                   "A/B test " + testId + " concluded with winner: " + winner);
            }
        }
    }

    /// #345 I3 — durable-entity checkpoint observability.
    ///
    /// A checkpoint is the only thing that bounds an entity log: until a partition is checkpointed the
    /// retention floor reclaims nothing for it. A driver that silently stopped shows no other symptom —
    /// writes and reads keep succeeding — so `writes` climbing is the signal an operator needs, and a
    /// keyspace whose `writes` stays flat under load is the thing to act on.
    @Command(name = "entity", description = "Inspect durable entity keyspaces", subcommands = {EntityCommand.CheckpointsCommand.class, EntityCommand.KeyspacesCommand.class})
    static class EntityCommand {
        @CommandLine.ParentCommand
        AetherCli parent;

        /// LOCAL route: each node checkpoints only the partitions it folds, so this reports the
        /// RECEIVING node's own work. Query a specific node's management port to see that node's view.
        @Command(name = "checkpoints", description = "Show this node's durable-entity checkpoint progress")
        static class CheckpointsCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private EntityCommand entityParent;

            @Override
            public Integer call() {
                var response = entityParent.parent.fetch(ENTITY_CHECKPOINTS);

                return OutputFormatter.printQuery(response, entityParent.parent.outputOptions());
            }
        }

        /// The HOSTING view (#634-3 fold-in): which nodes committed a registration for each keyspace —
        /// the exact candidate set the leader mints entity-arc owners over. Assembled from replicated
        /// KV, so any caught-up node answers identically; `partitionCountsDisagree: true` marks a
        /// rolling-redeploy window where hosts declared different counts (arcs span the max).
        @Command(name = "keyspaces", description = "Show durable-entity keyspaces with their hosting node sets")
        static class KeyspacesCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private EntityCommand entityParent;

            @Override
            public Integer call() {
                var response = entityParent.parent.fetch(ENTITY_KEYSPACES);

                return OutputFormatter.printQuery(response, entityParent.parent.outputOptions());
            }
        }
    }

    @Command(name = "streams", description = "Manage event streams", subcommands = {StreamCommand.ListCommand.class, StreamCommand.StatusCommand.class, StreamCommand.ConsumersCommand.class, StreamCommand.PublishCommand.class, StreamCommand.ReadCommand.class, StreamCommand.CreateCommand.class, StreamCommand.DeleteCommand.class, StreamCommand.ConsumerGroupCommand.class})
    static class StreamCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }

        /// Bare name (no colon) defaults to the system-namespace catalog address at the default
        /// version, preserving `status`/`publish`/`read`/`delete`'s original single-name UX; anything
        /// containing a colon is parsed as a full `namespace:stream:version` address via the canonical
        /// [ResourceAddress#resourceAddress] parser. Needed because those four commands now address the
        /// catalog-form routes (`STREAM_GET`/`STREAMS_PUBLISH`/`STREAM_READ`/`STREAMS_DELETE` —
        /// management-api-versioning-spec.md hard cutover) instead of the old flat bare-name routes, so
        /// a non-`system` namespace needs a way in that a bare name alone can't express.
        private static Result<ResourceAddress> resolveStreamAddress(String raw) {
            return raw.contains(":")
                   ? ResourceAddress.resourceAddress(raw)
                   : ResourceAddress.systemResource(raw, ResourceVersion.defaultVersion());
        }

        private static int handleAddressError(Cause cause) {
            System.err.println("Error: invalid stream address: " + cause.message());

            return ExitCode.ERROR;
        }

        @Command(name = "list", description = "List all streams")
        static class ListCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private StreamCommand streamParent;

            @Override
            public Integer call() {
                var response = streamParent.parent.fetch(STREAMS_LIST);

                return OutputFormatter.printQuery(response, streamParent.parent.outputOptions());
            }
        }

        @Command(name = "status", description = "Show stream details")
        static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private StreamCommand streamParent;

            @Parameters(index = "0", description = "Stream name or address: name | namespace:stream:version (bare name defaults to system:name:1.0.0)")
            private String address;

            @Override
            public Integer call() {
                return resolveStreamAddress(address).fold(StreamCommand::handleAddressError, this::fetchStatus);
            }

            private int fetchStatus(ResourceAddress addr) {
                var response = streamParent.parent.fetch(STREAM_GET,
                                                         List.of(addr.namespace().value(),
                                                                 addr.name().value(),
                                                                 addr.version().asString()));

                return OutputFormatter.printQuery(response, streamParent.parent.outputOptions());
            }
        }

        /// #488/#535 operator surface: which declarative `[streams.X]` consumers this node knows about,
        /// which partitions it consumes, and — since #535 — which node consumes each partition and which
        /// owns it. LOCAL scope: the answer describes the node you asked, so `unassignedPartitions` and
        /// the diagnostic are that node's view. Every node computes the same assignment, so one call is
        /// enough to answer "who consumes partition 3".
        @Command(name = "consumers", description = "Show declarative stream consumers on the target node")
        static class ConsumersCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private StreamCommand streamParent;

            @Override
            public Integer call() {
                var response = streamParent.parent.fetch(STREAM_DECLARATIVE_CONSUMERS);

                return OutputFormatter.printQuery(response, streamParent.parent.outputOptions());
            }
        }

        @Command(name = "publish", description = "Publish a message to a stream")
        static class PublishCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private StreamCommand streamParent;

            @Parameters(index = "0", description = "Stream name or address: name | namespace:stream:version (bare name defaults to system:name:1.0.0)")
            private String address;

            @Parameters(index = "1", description = "Message content")
            private String message;

            /// #524: Management-API publish writes untyped bytes, so it cannot extract an
            /// `@PartitionKey` the way an app publish does — an explicit `--partition` here is the
            /// operator choosing a target directly, not key-based routing. Omitted keeps the
            /// unchanged default: partition 0.
            @CommandLine.Option(names = "--partition", description = "Target partition (default: 0). Management-API publish does no key-based routing -- it writes untyped bytes with no event to extract a key from, so this is a direct, deliberate target choice.")
            private Integer partition;

            @Override
            public Integer call() {
                return resolveStreamAddress(address).fold(StreamCommand::handleAddressError, this::publish);
            }

            private int publish(ResourceAddress addr) {
                var encoded = Base64.getEncoder().encodeToString(message.getBytes());
                var body = partition == null
                           ? "{\"data\":\"" + encoded + "\"}"
                           : "{\"data\":\"" + encoded + "\",\"partition\":" + partition + "}";
                var response = streamParent.parent.post(STREAMS_PUBLISH,
                                                        List.of(addr.namespace().value(),
                                                                addr.name().value(),
                                                                addr.version().asString()),
                                                        body);

                return OutputFormatter.printAction(response,
                                                   streamParent.parent.outputOptions(),
                                                   "Published to stream " + addr.asString());
            }
        }

        @Command(name = "read", description = "Read events from a stream partition")
        static class ReadCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private StreamCommand streamParent;

            @Parameters(index = "0", description = "Stream name or address: name | namespace:stream:version (bare name defaults to system:name:1.0.0)")
            private String address;

            @Parameters(index = "1", description = "Partition number")
            private String partition;

            @CommandLine.Option(names = "--since", description = "Starting offset (maps to ?from=)")
            private Long since;

            @CommandLine.Option(names = "--limit", description = "Maximum number of events (maps to ?max=)")
            private Integer limit;

            @Override
            public Integer call() {
                return resolveStreamAddress(address).fold(StreamCommand::handleAddressError, this::read);
            }

            private int read(ResourceAddress addr) {
                var response = streamParent.parent.fetch(STREAM_READ,
                                                         List.of(addr.namespace().value(),
                                                                 addr.name().value(),
                                                                 addr.version().asString(),
                                                                 partition),
                                                         buildReadQuery());

                return OutputFormatter.printQuery(response, streamParent.parent.outputOptions());
            }

            private String buildReadQuery() {
                return Stream.of(option(since).map(v -> "from=" + v),
                                 option(limit).map(v -> "max=" + v))
                             .flatMap(Option::stream)
                             .collect(Collectors.joining("&"));
            }
        }

        @Command(name = "create", description = "Create a new event stream")
        static class CreateCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private StreamCommand streamParent;

            @Parameters(index = "0", description = "Stream name")
            private String name;

            @CommandLine.Option(names = "--partitions", description = "Number of partitions (default: server-side)")
            private Integer partitions;

            @Override
            public Integer call() {
                var body = buildCreateBody();
                var response = streamParent.parent.post(STREAM_CREATE, List.of(), body);

                return OutputFormatter.printAction(response,
                                                   streamParent.parent.outputOptions(),
                                                   "Created stream " + name);
            }

            private String buildCreateBody() {
                return option(partitions).map(p -> "{\"name\":\"" + name + "\",\"partitions\":" + p + "}")
                             .or("{\"name\":\"" + name + "\"}");
            }
        }

        @Command(name = "delete", description = "Delete an event stream")
        static class DeleteCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private StreamCommand streamParent;

            @Parameters(index = "0", description = "Stream name or address: name | namespace:stream:version (bare name defaults to system:name:1.0.0)")
            private String address;

            @CommandLine.Option(names = {"--force", "-f"}, description = "Skip confirmation prompt")
            private boolean force;

            @Override
            @SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
            public Integer call() {
                return resolveStreamAddress(address).fold(StreamCommand::handleAddressError, this::deleteStream);
            }

            @SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
            private int deleteStream(ResourceAddress addr) {
                if (!force) {
                    var confirmed = confirmDeletion(addr.asString());

                    if (!confirmed) {
                        return ExitCode.SUCCESS;
                    }
                }

                var response = streamParent.parent.delete(STREAMS_DELETE,
                                                          List.of(addr.namespace().value(),
                                                                  addr.name().value(),
                                                                  addr.version().asString()));
                var errorCode = OutputFormatter.checkResponseError(response,
                                                                   streamParent.parent.outputOptions(),
                                                                   "Failed to delete stream");

                if (errorCode >= 0) {
                    return errorCode;
                }

                return OutputFormatter.printAction(response,
                                                   streamParent.parent.outputOptions(),
                                                   "Deleted stream: " + addr.asString());
            }

            @SuppressWarnings("JBCT-SEQ-01")
            private static boolean confirmDeletion(String streamName) {
                System.out.print("Are you sure you want to delete stream '" + streamName + "'? (y/N) ");
                try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
                    return option(reader.readLine()).map(String::trim)
                                 .filter(s -> s.equalsIgnoreCase("y"))
                                 .isPresent();
                } catch (IOException _) {
                    return false;
                }
            }
        }

        @Command(name = "consumer-group", description = "Manage stream consumer groups", subcommands = {ConsumerGroupCommand.JoinCommand.class, ConsumerGroupCommand.LeaveCommand.class, ConsumerGroupCommand.StatusCommand.class})
        static class ConsumerGroupCommand implements Runnable {
            @CommandLine.ParentCommand
            private StreamCommand streamParent;

            @Contract
            @Override
            public void run() {
                CommandLine.usage(this, System.out);
            }

            @Command(name = "join", description = "Join a consumer group on a stream")
            static class JoinCommand implements Callable<Integer> {
                @CommandLine.ParentCommand
                private ConsumerGroupCommand groupParent;

                @Parameters(index = "0", description = "Consumer group ID")
                private String groupId;

                @Parameters(index = "1", description = "Stream name")
                private String streamName;

                @CommandLine.Option(names = "--consumer-id", required = true, description = "Consumer identifier within the group")
                private String consumerId;

                @CommandLine.Option(names = "--partitions", description = "Partition count expected by the consumer (default: 1)")
                private Integer partitionCount;

                @Override
                public Integer call() {
                    var partitions = option(partitionCount).or(1);
                    var body = "{\"groupId\":\"" + groupId
                             + "\",\"streamName\":\"" + streamName
                             + "\",\"partitionCount\":" + partitions
                             + ",\"consumerId\":\"" + consumerId
                             + "\"}";
                    var response = groupParent.streamParent.parent.post(CONSUMER_GROUP_JOIN, List.of(), body);

                    return OutputFormatter.printAction(response,
                                                       groupParent.streamParent.parent.outputOptions(),
                                                       "Consumer " + consumerId + " joined group " + groupId);
                }
            }

            @Command(name = "leave", description = "Leave a consumer group on a stream")
            static class LeaveCommand implements Callable<Integer> {
                @CommandLine.ParentCommand
                private ConsumerGroupCommand groupParent;

                @Parameters(index = "0", description = "Consumer group ID")
                private String groupId;

                @Parameters(index = "1", description = "Stream name")
                private String streamName;

                @CommandLine.Option(names = "--consumer-id", required = true, description = "Consumer identifier within the group")
                private String consumerId;

                @Override
                public Integer call() {
                    var body = "{\"groupId\":\"" + groupId
                             + "\",\"streamName\":\"" + streamName
                             + "\",\"consumerId\":\"" + consumerId
                             + "\"}";
                    var response = groupParent.streamParent.parent.post(CONSUMER_GROUP_LEAVE, List.of(), body);

                    return OutputFormatter.printAction(response,
                                                       groupParent.streamParent.parent.outputOptions(),
                                                       "Consumer " + consumerId + " left group " + groupId);
                }
            }

            @Command(name = "status", description = "Show consumer group status")
            static class StatusCommand implements Callable<Integer> {
                @CommandLine.ParentCommand
                private ConsumerGroupCommand groupParent;

                @Parameters(index = "0", description = "Consumer group ID")
                private String groupId;

                @Parameters(index = "1", arity = "0..1", description = "Stream name (informational; status is per-group)")
                private String streamName;

                @Override
                public Integer call() {
                    var response = groupParent.streamParent.parent.fetch(CONSUMER_GROUP_STATUS, List.of(groupId));

                    return OutputFormatter.printQuery(response, groupParent.streamParent.parent.outputOptions());
                }
            }
        }
    }

    @Command(name = "certs", description = "Certificate management", subcommands = {CertCommand.StatusCommand.class})
    static class CertCommand implements Runnable {
        @CommandLine.ParentCommand
        private AetherCli parent;

        @Contract
        @Override
        public void run() {
            var response = parent.fetch(CERTIFICATES_LIST);

            OutputFormatter.printQuery(response, parent.outputOptions());
        }

        @Command(name = "status", description = "Show certificate status and expiry info")
        static class StatusCommand implements Callable<Integer> {
            @CommandLine.ParentCommand
            private CertCommand certParent;

            @Override
            public Integer call() {
                var response = certParent.parent.fetch(CERTIFICATES_LIST);

                return OutputFormatter.printQuery(response, certParent.parent.outputOptions());
            }
        }
    }

    @SuppressWarnings("JBCT-PAT-01")
    static final class TrustAllManager implements X509TrustManager {
        @Contract
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Contract
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
