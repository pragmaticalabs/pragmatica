package org.pragmatica.aether.forge;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.ember.EmberConfig;
import org.pragmatica.aether.lb.AetherPassiveLB;
import org.pragmatica.aether.lb.PassiveLBConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.aether.dashboard.StaticFileHandler;
import org.pragmatica.aether.forge.load.ConfigurableLoadRunner;
import org.pragmatica.aether.forge.load.LoadConfigLoader;
import org.pragmatica.aether.forge.simulator.EntryPointMetrics;
import org.pragmatica.aether.api.StatusWebSocketHandler;
import org.pragmatica.aether.api.StatusWebSocketPublisher;
import org.pragmatica.aether.api.WebSocketAuthenticator;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.forge.api.StatusRoutes;
import org.pragmatica.http.routing.JsonCodec;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.websocket.WebSocketEndpoint;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.awt.Desktop;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Main entry point for Aether Forge.
/// Starts a cluster, load generator, and web dashboard on a single JVM.
///
/// CLI arguments:
/// ```
/// --config &lt;forge.toml&gt;       Forge cluster configuration
/// --blueprint &lt;coords&gt;        Artifact coordinates to deploy (groupId:artifactId:version)
///                              Cluster resolves JAR via configured Repository chain
/// --load-config &lt;file.toml&gt;   Load test configuration
/// --auto-start                Start load generation after config loaded
/// ```
///
/// Environment variables (override CLI args):
/// ```
/// FORGE_CONFIG        - Path to forge.toml
/// FORGE_BLUEPRINT     - Artifact coordinates (groupId:artifactId:version)
/// FORGE_LOAD_CONFIG   - Path to load config file
/// FORGE_AUTO_START    - Set to "true" to auto-start load
/// FORGE_PORT          - Dashboard port (backwards compatible)
/// CLUSTER_SIZE        - Number of nodes (backwards compatible)
/// LOAD_RATE           - Initial load rate (backwards compatible)
/// ```
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
public final class ForgeServer {
    private static final Logger log = LoggerFactory.getLogger(ForgeServer.class);

    private static final int MAX_CONTENT_LENGTH = 65536;
    private static final JsonCodec CODEC = JsonCodecAdapter.defaultCodec();

    private final StartupConfig startupConfig;
    private final EmberConfig forgeConfig;

    private volatile Option<EmberCluster> cluster = Option.empty();
    private volatile Option<AetherPassiveLB> loadBalancer = Option.empty();
    private volatile Option<ConfigurableLoadRunner> configurableLoadRunner = Option.empty();
    private volatile Option<ForgeMetrics> metrics = Option.empty();
    private volatile Option<ForgeApiHandler> apiHandler = Option.empty();
    private volatile Option<StaticFileHandler> staticHandler = Option.empty();
    private volatile Option<HttpServer> httpServer = Option.empty();
    private volatile Option<ScheduledExecutorService> metricsScheduler = Option.empty();
    private volatile Option<StatusWebSocketPublisher> wsPublisher = Option.empty();
    private final StatusWebSocketHandler wsHandler = new StatusWebSocketHandler(WebSocketAuthenticator.webSocketAuthenticator(SecurityValidator.noOpValidator(),
                                                                                                                              false));
    private final StatusWebSocketHandler dashboardWsHandler = new StatusWebSocketHandler(WebSocketAuthenticator.webSocketAuthenticator(SecurityValidator.noOpValidator(),
                                                                                                                                       false));
    private final StatusWebSocketHandler eventWsHandler = new StatusWebSocketHandler(WebSocketAuthenticator.webSocketAuthenticator(SecurityValidator.noOpValidator(),
                                                                                                                                   false));
    private final HttpOperations http = JdkHttpOperations.jdkHttpOperations();
    private final long startTime = System.currentTimeMillis();
    private volatile String lastEventTimestamp = "";

    private ForgeServer(StartupConfig startupConfig, EmberConfig forgeConfig) {
        this.startupConfig = startupConfig;
        this.forgeConfig = forgeConfig;
    }

    private static final String VERSION = "Aether Forge 0.20.0";

    public static void main(String[] args) {
        if (hasFlag(args, "--help", "-h")) {
            printHelp();
            return;
        }
        if (hasFlag(args, "--version", "-V")) {
            System.out.println(VERSION);
            return;
        }
        // Parse CLI args with env var overrides
        var startupConfigResult = StartupConfig.startupConfig(args);
        startupConfigResult.onFailure(cause -> {
            log.error("Configuration error: {}", cause.message());
            System.exit(1);
        });
        if (startupConfigResult.isFailure()) {
            return;
        }
        var startupConfig = startupConfigResult.unwrap();
        var forgeConfig = loadForgeConfig(startupConfig);
        printBanner(forgeConfig, startupConfig);
        var server = new ForgeServer(startupConfig, forgeConfig);
        Runtime.getRuntime()
               .addShutdownHook(new Thread(() -> {
                   log.info("Shutting down...");
                   server.stop();
               }));
        try{
            server.start();
        } catch (Exception e) {
            log.error("Failed to start Forge server", e);
            System.exit(1);
        }
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private static boolean hasFlag(String[] args, String longFlag, String shortFlag) {
        for (var arg : args) {
            if (arg.equals(longFlag) || arg.equals(shortFlag)) {
                return true;
            }
        }
        return false;
    }

    private static void printHelp() {
        System.out.println(VERSION);
        System.out.println();
        System.out.println("Start a local Aether Forge development cluster.");
        System.out.println();
        System.out.println("Usage: aether-forge [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --config <forge.toml>       Forge cluster configuration");
        System.out.println("  --blueprint <coords>        Artifact coordinates (groupId:artifactId:version)");
        System.out.println("                              Cluster resolves JAR via configured Repository chain");
        System.out.println("  --load-config <file.toml>   Load test configuration");
        System.out.println("  --auto-start                Start load generation after config loaded");
        System.out.println("  -h, --help                  Show this help message");
        System.out.println("  -V, --version               Print version");
        System.out.println();
        System.out.println("Environment variables:");
        System.out.println("  FORGE_CONFIG        Path to forge.toml");
        System.out.println("  FORGE_BLUEPRINT     Artifact coordinates (groupId:artifactId:version)");
        System.out.println("  FORGE_LOAD_CONFIG   Path to load config file");
        System.out.println("  FORGE_AUTO_START    Set to \"true\" to auto-start load");
        System.out.println("  FORGE_PORT          Dashboard port (default: 8888)");
        System.out.println("  CLUSTER_SIZE        Number of nodes (default: 5)");
    }

    private static EmberConfig loadForgeConfig(StartupConfig startupConfig) {
        return startupConfig.forgeConfig()
                            .map(EmberConfig::load)
                            .map(r -> r.onFailure(c -> log.error("Failed to load forge config: {}",
                                                                 c.message()))
                                       .or(EmberConfig.DEFAULT))
                            .or(createDefaultForgeConfig(startupConfig));
    }

    private static EmberConfig createDefaultForgeConfig(StartupConfig startupConfig) {
        // Validation already done in StartupConfig, safe to unwrap
        return EmberConfig.emberConfig(startupConfig.clusterSize(),
                                       EmberConfig.DEFAULT_MANAGEMENT_PORT,
                                       startupConfig.port())
                          .or(EmberConfig.DEFAULT);
    }

    private static void printBanner(EmberConfig forgeConfig, StartupConfig startupConfig) {
        log.info("=".repeat(60));
        log.info("    AETHER FORGE");
        log.info("=".repeat(60));
        log.info("  Dashboard: http://localhost:{}", forgeConfig.dashboardPort());
        log.info("  Cluster size: {} nodes", forgeConfig.nodes());
        log.info("  App HTTP port: {} (load target)", forgeConfig.appHttpPort());
        if (forgeConfig.lbEnabled()) {
            log.info("  Load balancer: http://localhost:{}", forgeConfig.lbPort());
        }
        startupConfig.blueprint()
                     .onPresent(coords -> log.info("  Blueprint: {}", coords));
        startupConfig.loadConfig()
                     .onPresent(p -> log.info("  Load config: {}", p));
        if (startupConfig.autoStart()) {
            log.info("  Auto-start: enabled");
        }
        log.info("=".repeat(60));
    }

    public void start() {
        log.info("Starting Forge server...");
        var configProvider = buildConfigurationProvider();
        initializeComponents(configProvider);
        startCluster();
        startLoadBalancer();
        startMetricsCollection();
        deployAndStartLoad();
        apiHandler.onPresent(h -> h.addEvent("CLUSTER_STARTED",
                                             "Forge cluster started with " + forgeConfig.nodes() + " nodes"));
        startHttpServer();
        openBrowser("http://localhost:" + forgeConfig.dashboardPort());
        log.info("Forge server running. Press Ctrl+C to stop.");
        joinMainThread();
    }

    private void initializeComponents(Option<ConfigurationProvider> configProvider) {
        var metricsInstance = ForgeMetrics.forgeMetrics();
        var clusterInstance = EmberCluster.emberCluster(forgeConfig.nodes(),
                                                        EmberCluster.DEFAULT_BASE_PORT,
                                                        forgeConfig.managementPort(),
                                                        forgeConfig.appHttpPort(),
                                                        "node",
                                                        configProvider,
                                                        forgeConfig.observability(),
                                                        forgeConfig.coreMax());
        var entryPointMetrics = EntryPointMetrics.entryPointMetrics();
        Supplier<List<Integer>> portSupplier = forgeConfig.lbEnabled()
                                               ? () -> List.of(forgeConfig.lbPort())
                                               : clusterInstance::getAvailableAppHttpPorts;
        var configurableLoadRunnerInstance = ConfigurableLoadRunner.configurableLoadRunner(portSupplier,
                                                                                           metricsInstance,
                                                                                           entryPointMetrics);
        var apiHandlerInstance = ForgeApiHandler.forgeApiHandler(clusterInstance,
                                                                 metricsInstance,
                                                                 configurableLoadRunnerInstance);
        metrics = Option.some(metricsInstance);
        cluster = Option.some(clusterInstance);
        configurableLoadRunner = Option.some(configurableLoadRunnerInstance);
        apiHandler = Option.some(apiHandlerInstance);
        staticHandler = Option.some(StaticFileHandler.staticFileHandler());
        var wsPublisherInstance = StatusWebSocketPublisher.statusWebSocketPublisher(wsHandler,
                                                                                    () -> serializeStatus(clusterInstance,
                                                                                                          metricsInstance,
                                                                                                          startTime,
                                                                                                          configurableLoadRunnerInstance));
        wsPublisher = Option.some(wsPublisherInstance);
    }

    private static String serializeStatus(EmberCluster cluster,
                                          ForgeMetrics metrics,
                                          long startTime,
                                          ConfigurableLoadRunner loadRunner) {
        var status = StatusRoutes.buildFullStatus(cluster, metrics, startTime, loadRunner);
        return CODEC.serialize(status)
                    .map(byteBuf -> {
                             try{
                                 var bytes = new byte[byteBuf.readableBytes()];
                                 byteBuf.readBytes(bytes);
                                 return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                             } finally{
                                 byteBuf.release();
                             }
                         })
                    .or("{}");
    }

    private void startCluster() {
        log.info("Starting {} node cluster...", forgeConfig.nodes());
        cluster.onPresent(c -> c.start()
                                .await(TimeSpan.timeSpan(60)
                                               .seconds())
                                .onFailure(cause -> {
                                               throw new IllegalStateException("Failed to start cluster: " + cause.message());
                                           }));
        TimeSpan.timeSpan(2)
                .seconds()
                .sleep();
    }

    private void startLoadBalancer() {
        if (!forgeConfig.lbEnabled()) {
            return;
        }
        var clusterNodeInfos = cluster.map(EmberCluster::getNodeInfos)
                                      .or(List.of());
        if (clusterNodeInfos.isEmpty()) {
            log.warn("No cluster nodes available, skipping load balancer start");
            return;
        }
        var selfNodeId = NodeId.nodeId("lb-passive")
                               .unwrap();
        var lbClusterPort = EmberCluster.DEFAULT_BASE_PORT + forgeConfig.nodes() + 10;
        var selfInfo = NodeInfo.nodeInfo(selfNodeId,
                                         NodeAddress.nodeAddress("localhost", lbClusterPort)
                                                    .unwrap(),
                                         NodeRole.PASSIVE);
        var lbConfig = PassiveLBConfig.passiveLBConfig(forgeConfig.lbPort(),
                                                       selfInfo,
                                                       clusterNodeInfos,
                                                       forgeConfig.nodes());
        var lb = AetherPassiveLB.aetherPassiveLB(lbConfig);
        lb.start()
          .await(TimeSpan.timeSpan(30)
                         .seconds())
          .onSuccess(_ -> registerLoadBalancer(lb, lbClusterPort))
          .onFailure(cause -> log.error("Failed to start passive LB: {}",
                                        cause.message()));
    }

    private void registerLoadBalancer(AetherPassiveLB lb, int lbClusterPort) {
        loadBalancer = Option.some(lb);
        log.info("Passive LB started on port {} (cluster port {})", forgeConfig.lbPort(), lbClusterPort);
    }

    private void startMetricsCollection() {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        metrics.onPresent(m -> scheduler.scheduleAtFixedRate(m::snapshot, 500, 500, TimeUnit.MILLISECONDS));
        scheduler.scheduleAtFixedRate(this::pollNodeEvents, 2, 2, TimeUnit.SECONDS);
        metricsScheduler = Option.some(scheduler);
        wsPublisher.onPresent(StatusWebSocketPublisher::start);
    }

    private void pollNodeEvents() {
        try{
            var port = cluster.flatMap(EmberCluster::getLeaderManagementPort)
                              .or(forgeConfig.managementPort());
            var uriStr = "http://localhost:" + port + "/api/events";
            if (!lastEventTimestamp.isEmpty()) {
                uriStr += "?since=" + java.net.URLEncoder.encode(lastEventTimestamp,
                                                                 java.nio.charset.StandardCharsets.UTF_8);
            }
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create(uriStr))
                                     .GET()
                                     .timeout(java.time.Duration.ofSeconds(2))
                                     .build();
            http.sendString(request)
                .await(TimeSpan.timeSpan(3)
                               .seconds())
                .flatMap(org.pragmatica.http.HttpResult::toResult)
                .onSuccess(this::parseAndMergeEvents);
        } catch (Exception e) {
            log.trace("Event polling failed: {}", e.getMessage());
        }
    }

    private void parseAndMergeEvents(String json) {
        if (json == null || json.length() < 3 || !json.startsWith("[")) {
            return;
        }
        var content = json.substring(1,
                                     json.length() - 1)
                          .trim();
        if (content.isEmpty()) {
            return;
        }
        var events = splitJsonObjects(content);
        for (var eventJson : events) {
            mergeEvent(eventJson);
        }
    }

    private void mergeEvent(String eventJson) {
        extractJsonString(eventJson, "timestamp")
        .onPresent(timestamp -> extractJsonString(eventJson, "type")
        .onPresent(type -> {
                       var severity = extractJsonString(eventJson, "severity").or("INFO");
                       var summary = extractJsonString(eventJson, "summary").or("");
                       apiHandler.onPresent(h -> h.addNodeEvent(timestamp, type, severity, summary));
                       lastEventTimestamp = timestamp;
                   }));
    }

    private static List<String> splitJsonObjects(String content) {
        var objects = new java.util.ArrayList<String>();
        var depth = 0;
        var start = - 1;
        for (int i = 0; i < content.length(); i++) {
            var ch = content.charAt(i);
            if (ch == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(content.substring(start, i + 1));
                    start = - 1;
                }
            }
        }
        return objects;
    }

    private static Option<String> extractJsonString(String json, String key) {
        var search = "\"" + key + "\":\"";
        var idx = json.indexOf(search);
        if (idx < 0) {
            return Option.empty();
        }
        var valStart = idx + search.length();
        var valEnd = json.indexOf("\"", valStart);
        if (valEnd < 0) {
            return Option.empty();
        }
        return Option.some(json.substring(valStart, valEnd));
    }

    private void deployAndStartLoad() {
        startupConfig.blueprint()
                     .onPresent(this::deployBlueprintFromArtifact);
        startupConfig.loadConfig()
                     .onPresent(this::loadLoadConfig);
        if (startupConfig.autoStart() && startupConfig.loadConfig()
                                                      .isPresent()) {
            log.info("Auto-starting load generation...");
            configurableLoadRunner.onPresent(ConfigurableLoadRunner::start);
            apiHandler.onPresent(h -> h.addEvent("LOAD_STARTED", "Load generation auto-started"));
        }
    }

    private static void joinMainThread() {
        try{
            Thread.currentThread()
                  .join();
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
    }

    /// Deploy a blueprint by posting artifact coordinates to the cluster.
    /// The cluster's BlueprintService resolves the artifact via its configured Repository chain
    /// (local Maven repo, builtin, etc.) before falling back to ArtifactStore (DHT).
    private void deployBlueprintFromArtifact(String artifactCoords) {
        log.info("Deploying blueprint artifact: {}...", artifactCoords);
        var leaderPort = cluster.flatMap(EmberCluster::getLeaderManagementPort)
                                .or(forgeConfig.managementPort());
        var body = "{\"artifact\":\"" + artifactCoords + "\"}";
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + leaderPort + "/api/blueprint/deploy"))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .build();
        log.info("Deploying blueprint by coordinates: POST /api/blueprint/deploy — {}", artifactCoords);
        http.sendString(request)
            .await(TimeSpan.timeSpan(10)
                           .seconds())
            .onSuccess(result -> handleDeployResponse(result, artifactCoords))
            .onFailure(cause -> log.error("Failed to deploy blueprint: {}",
                                          cause.message()));
    }

    private void handleDeployResponse(org.pragmatica.http.HttpResult<String> result, String artifactCoords) {
        if (result.isSuccess()) {
            log.info("Blueprint deployed from artifact: {}", artifactCoords);
            apiHandler.onPresent(h -> h.addEvent("BLUEPRINT_DEPLOYED",
                                                 "Blueprint deployed from artifact " + artifactCoords));
            TimeSpan.timeSpan(1)
                    .seconds()
                    .sleep();
        } else {
            log.error("Blueprint deploy failed (HTTP {}): {}", result.statusCode(), result.body());
        }
    }

    private void loadLoadConfig(Path loadConfigPath) {
        log.info("Loading load configuration from {}...", loadConfigPath);
        LoadConfigLoader.load(loadConfigPath)
                        .onSuccess(config -> {
                                       configurableLoadRunner.onPresent(r -> r.applyConfig(config));
                                       log.info("Load configuration loaded: {} targets",
                                                config.targets()
                                                      .size());
                                       apiHandler.onPresent(h -> h.addEvent("LOAD_CONFIG_LOADED",
                                                                            "Loaded " + config.targets()
                                                                                              .size() + " targets from " + loadConfigPath.getFileName()));
                                   })
                        .onFailure(cause -> log.error("Failed to load configuration: {}",
                                                      cause.message()));
    }

    public void stop() {
        log.info("Stopping Forge server...");
        wsPublisher.onPresent(StatusWebSocketPublisher::stop);
        metricsScheduler.onPresent(ScheduledExecutorService::shutdownNow);
        httpServer.onPresent(server -> server.stop()
                                             .await(TimeSpan.timeSpan(10)
                                                            .seconds())
                                             .onFailure(cause -> log.warn("Error stopping HTTP server: {}",
                                                                          cause.message())));
        loadBalancer.onPresent(lb -> lb.stop()
                                       .await(TimeSpan.timeSpan(10)
                                                      .seconds())
                                       .onFailure(cause -> log.warn("Error stopping load balancer: {}",
                                                                    cause.message())));
        cluster.onPresent(c -> c.stop()
                                .await(TimeSpan.timeSpan(30)
                                               .seconds())
                                .onFailure(cause -> log.warn("Error stopping cluster: {}",
                                                             cause.message())));
        log.info("Forge server stopped.");
    }

    /// Build ConfigurationProvider with layered configuration.
    ///
    /// Priority (highest to lowest):
    /// <ol>
    ///   - Environment variables (AETHER_*)
    ///   - System properties (-Daether.*)
    ///   - forge.toml (if specified)
    ///   - aether.toml (if exists)
    /// </ol>
    ///
    /// @return ConfigurationProvider for all nodes, or empty if no config needed
    private Option<ConfigurationProvider> buildConfigurationProvider() {
        var builder = ConfigurationProvider.builder();
        // Add TOML files (lowest priority)
        startupConfig.forgeConfig()
                     .map(path -> path.resolveSibling("aether.toml"))
                     .filter(path -> path.toFile()
                                         .exists())
                     .onPresent(builder::withTomlFile);
        startupConfig.forgeConfig()
                     .onPresent(builder::withTomlFile);
        // Add system properties and environment (higher priority)
        builder.withSystemProperties("aether.")
               .withEnvironment("AETHER_");
        return Option.some(builder.build());
    }

    private void startHttpServer() {
        Option.all(apiHandler, staticHandler)
              .map(ForgeRequestHandler::forgeRequestHandler)
              .onPresent(this::launchHttpServer);
    }

    private void launchHttpServer(ForgeRequestHandler requestHandler) {
        var wsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/status", wsHandler);
        var dashboardWsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/dashboard", dashboardWsHandler);
        var eventWsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/events", eventWsHandler);
        var config = HttpServerConfig.httpServerConfig("forge-dashboard",
                                                       forgeConfig.dashboardPort())
                                     .withMaxContentLength(MAX_CONTENT_LENGTH)
                                     .withChunkedWrite()
                                     .withWebSocket(wsEndpoint)
                                     .withWebSocket(dashboardWsEndpoint)
                                     .withWebSocket(eventWsEndpoint);
        HttpServer.httpServer(config, requestHandler::handle)
                  .await(TimeSpan.timeSpan(10)
                                 .seconds())
                  .onSuccess(server -> {
                                 httpServer = Option.some(server);
                                 log.info("HTTP server started on port {}",
                                          server.port());
                             })
                  .onFailure(cause -> {
                                 throw new IllegalStateException("Failed to start HTTP server: " + cause.message());
                             });
    }

    private void openBrowser(String url) {
        try{
            if (Desktop.isDesktopSupported() && Desktop.getDesktop()
                                                       .isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop()
                       .browse(new URI(url));
                log.info("Opened browser to {}", url);
            } else {
                log.info("Could not open browser automatically. Please navigate to: {}", url);
            }
        } catch (Exception e) {
            log.info("Could not open browser automatically. Please navigate to: {}", url);
        }
    }
}
