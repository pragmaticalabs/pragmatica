package org.pragmatica.aether.forge;

import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.aether.forge.load.ConfigurableLoadRunner;
import org.pragmatica.aether.forge.load.LoadConfigLoader;
import org.pragmatica.aether.forge.simulator.EntryPointMetrics;
import org.pragmatica.aether.api.StatusWebSocketHandler;
import org.pragmatica.aether.api.StatusWebSocketPublisher;
import org.pragmatica.aether.forge.api.StatusRoutes;
import org.pragmatica.http.routing.JsonCodec;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.websocket.WebSocketEndpoint;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
/// --blueprint &lt;file.toml&gt;     Blueprint to deploy on startup
/// --load-config &lt;file.toml&gt;   Load test configuration
/// --auto-start                Start load generation after config loaded
/// ```
///
/// Environment variables (override CLI args):
/// ```
/// FORGE_CONFIG        - Path to forge.toml
/// FORGE_BLUEPRINT     - Path to blueprint file
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

    // Forge-only dev tooling credentials for embedded H2 database
    private static final String FORGE_H2_USERNAME = "sa";
    private static final String FORGE_H2_PASSWORD = "";

    private final StartupConfig startupConfig;
    private final ForgeConfig forgeConfig;

    private volatile Option<ForgeCluster> cluster = Option.empty();
    private volatile Option<ConfigurableLoadRunner> configurableLoadRunner = Option.empty();
    private volatile Option<ForgeMetrics> metrics = Option.empty();
    private volatile Option<ForgeApiHandler> apiHandler = Option.empty();
    private volatile Option<StaticFileHandler> staticHandler = Option.empty();
    private volatile Option<ForgeH2Server> h2Server = Option.empty();
    private volatile Option<HttpServer> httpServer = Option.empty();
    private volatile Option<ScheduledExecutorService> metricsScheduler = Option.empty();
    private volatile Option<StatusWebSocketPublisher> wsPublisher = Option.empty();
    private final StatusWebSocketHandler wsHandler = new StatusWebSocketHandler();
    private final long startTime = System.currentTimeMillis();

    private ForgeServer(StartupConfig startupConfig, ForgeConfig forgeConfig) {
        this.startupConfig = startupConfig;
        this.forgeConfig = forgeConfig;
    }

    public static void main(String[] args) {
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

    private static ForgeConfig loadForgeConfig(StartupConfig startupConfig) {
        return startupConfig.forgeConfig()
                            .map(ForgeConfig::load)
                            .map(r -> r.onFailure(c -> log.error("Failed to load forge config: {}",
                                                                 c.message()))
                                       .or(ForgeConfig.DEFAULT))
                            .or(createDefaultForgeConfig(startupConfig));
    }

    private static ForgeConfig createDefaultForgeConfig(StartupConfig startupConfig) {
        // Validation already done in StartupConfig, safe to unwrap
        return ForgeConfig.forgeConfig(startupConfig.clusterSize(),
                                       ForgeConfig.DEFAULT_MANAGEMENT_PORT,
                                       startupConfig.port())
                          .or(ForgeConfig.DEFAULT);
    }

    private static void printBanner(ForgeConfig forgeConfig, StartupConfig startupConfig) {
        log.info("=".repeat(60));
        log.info("    AETHER FORGE");
        log.info("=".repeat(60));
        log.info("  Dashboard: http://localhost:{}", forgeConfig.dashboardPort());
        log.info("  Cluster size: {} nodes", forgeConfig.nodes());
        log.info("  App HTTP port: {} (load target)", forgeConfig.appHttpPort());
        if (forgeConfig.h2Config()
                       .enabled()) {
            log.info("  H2 Database: port {} ({})",
                     forgeConfig.h2Config()
                                .port(),
                     forgeConfig.h2Config()
                                .persistent()
                     ? "persistent"
                     : "in-memory");
        }
        startupConfig.blueprint()
                     .onPresent(p -> log.info("  Blueprint: {}", p));
        startupConfig.loadConfig()
                     .onPresent(p -> log.info("  Load config: {}", p));
        if (startupConfig.autoStart()) {
            log.info("  Auto-start: enabled");
        }
        log.info("=".repeat(60));
    }

    public void start() {
        log.info("Starting Forge server...");
        startH2Server();
        var configProvider = buildConfigurationProvider();
        initializeComponents(configProvider);
        startCluster();
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
        var clusterInstance = ForgeCluster.forgeCluster(forgeConfig.nodes(),
                                                        ForgeCluster.DEFAULT_BASE_PORT,
                                                        forgeConfig.managementPort(),
                                                        forgeConfig.appHttpPort(),
                                                        "node",
                                                        configProvider);
        var entryPointMetrics = EntryPointMetrics.entryPointMetrics();
        var configurableLoadRunnerInstance = ConfigurableLoadRunner.configurableLoadRunner(clusterInstance::getAvailableAppHttpPorts,
                                                                                           metricsInstance,
                                                                                           entryPointMetrics);
        var apiHandlerInstance = ForgeApiHandler.forgeApiHandler(clusterInstance,
                                                                 metricsInstance,
                                                                 configurableLoadRunnerInstance,
                                                                 startupConfig.loadConfig());
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

    private static String serializeStatus(ForgeCluster cluster,
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

    private void startMetricsCollection() {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        metrics.onPresent(m -> scheduler.scheduleAtFixedRate(m::snapshot, 500, 500, TimeUnit.MILLISECONDS));
        metricsScheduler = Option.some(scheduler);
        wsPublisher.onPresent(StatusWebSocketPublisher::start);
    }

    private void deployAndStartLoad() {
        startupConfig.blueprint()
                     .onPresent(this::deployBlueprint);
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

    private void deployBlueprint(Path blueprintPath) {
        log.info("Deploying blueprint from {}...", blueprintPath);
        try (var client = HttpClient.newHttpClient()) {
            var content = Files.readString(blueprintPath);
            var leaderPort = cluster.flatMap(c -> c.getLeaderManagementPort())
                                    .or(forgeConfig.managementPort());
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + leaderPort + "/api/blueprint"))
                                     .header("Content-Type", "application/toml")
                                     .POST(HttpRequest.BodyPublishers.ofString(content))
                                     .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Blueprint deployed");
                apiHandler.onPresent(h -> h.addEvent("BLUEPRINT_DEPLOYED",
                                                     "Blueprint deployed from " + blueprintPath.getFileName()));
                // Wait for deployment to propagate
                TimeSpan.timeSpan(1)
                        .seconds()
                        .sleep();
            } else {
                log.error("Failed to deploy blueprint: {} - {}", response.statusCode(), response.body());
            }
        } catch (IOException | InterruptedException e) {
            log.error("Failed to deploy blueprint: {}", e.getMessage());
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
        cluster.onPresent(c -> c.stop()
                                .await(TimeSpan.timeSpan(30)
                                               .seconds())
                                .onFailure(cause -> log.warn("Error stopping cluster: {}",
                                                             cause.message())));
        stopH2Server();
        log.info("Forge server stopped.");
    }

    private void startH2Server() {
        if (!forgeConfig.h2Config()
                        .enabled()) {
            return;
        }
        var server = ForgeH2Server.forgeH2Server(forgeConfig.h2Config());
        server.start()
              .await(TimeSpan.timeSpan(10)
                             .seconds())
              .onSuccess(_ -> {
                             h2Server = Option.some(server);
                             log.info("H2 database available at: {}",
                                      server.jdbcUrl());
                         })
              .onFailure(cause -> {
                  throw new IllegalStateException("Failed to start H2 server: " + cause.message());
              });
    }

    /// Build ConfigurationProvider with layered configuration.
    ///
    /// Priority (highest to lowest):
    /// <ol>
    ///   - Runtime values (H2 URL if enabled)
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
        // Inject H2 runtime values (highest priority)
        // Keys must match toSnakeCase(recordComponentName) from DatabaseConnectorConfig
        h2Server.onPresent(server -> injectH2Config(server, builder));
        return Option.some(builder.build());
    }

    private void injectH2Config(ForgeH2Server server, ConfigurationProvider.Builder builder) {
        var runtimeValues = Map.of("database.name",
                                   "forge-h2",
                                   "database.type",
                                   "H2",
                                   "database.host",
                                   "localhost",
                                   "database.port",
                                   "0",
                                   "database.database",
                                   "forge",
                                   "database.jdbc_url",
                                   server.jdbcUrl(),
                                   "database.username",
                                   FORGE_H2_USERNAME,
                                   "database.password",
                                   FORGE_H2_PASSWORD);
        builder.withSource(MapConfigSource.mapConfigSource("runtime", runtimeValues, 500)
                                          .unwrap());
        log.info("Injected H2 configuration into ConfigurationProvider");
    }

    private void stopH2Server() {
        h2Server.onPresent(server -> server.stop()
                                           .await(TimeSpan.timeSpan(5)
                                                          .seconds())
                                           .onFailure(cause -> log.warn("Error stopping H2 server: {}",
                                                                        cause.message())));
    }

    /// Get the H2 JDBC URL if H2 is enabled and running.
    public Option<String> h2JdbcUrl() {
        return h2Server.filter(ForgeH2Server::isRunning)
                       .map(ForgeH2Server::jdbcUrl);
    }

    private void startHttpServer() {
        Option.all(apiHandler, staticHandler)
              .map(ForgeRequestHandler::forgeRequestHandler)
              .onPresent(this::launchHttpServer);
    }

    private void launchHttpServer(ForgeRequestHandler requestHandler) {
        var wsEndpoint = WebSocketEndpoint.webSocketEndpoint("/ws/status", wsHandler);
        var config = HttpServerConfig.httpServerConfig("forge-dashboard",
                                                       forgeConfig.dashboardPort())
                                     .withMaxContentLength(MAX_CONTENT_LENGTH)
                                     .withChunkedWrite()
                                     .withWebSocket(wsEndpoint);
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
