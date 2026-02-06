package org.pragmatica.aether.forge;

import org.pragmatica.aether.config.ConfigurationProvider;
import org.pragmatica.aether.config.source.MapConfigSource;
import org.pragmatica.aether.forge.load.ConfigurableLoadRunner;
import org.pragmatica.aether.forge.load.LoadConfigLoader;
import org.pragmatica.aether.forge.simulator.EntryPointMetrics;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.util.HashMap;
import java.util.Map;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for Aether Forge.
 * Starts a cluster, load generator, and web dashboard on a single JVM.
 * <p>
 * CLI arguments:
 * <pre>
 * --config &lt;forge.toml&gt;       Forge cluster configuration
 * --blueprint &lt;file.toml&gt;     Blueprint to deploy on startup
 * --load-config &lt;file.toml&gt;   Load test configuration
 * --auto-start                Start load generation after config loaded
 * </pre>
 * <p>
 * Environment variables (override CLI args):
 * <pre>
 * FORGE_CONFIG        - Path to forge.toml
 * FORGE_BLUEPRINT     - Path to blueprint file
 * FORGE_LOAD_CONFIG   - Path to load config file
 * FORGE_AUTO_START    - Set to "true" to auto-start load
 * FORGE_PORT          - Dashboard port (backwards compatible)
 * CLUSTER_SIZE        - Number of nodes (backwards compatible)
 * LOAD_RATE           - Initial load rate (backwards compatible)
 * </pre>
 */
public final class ForgeServer {
    private static final Logger log = LoggerFactory.getLogger(ForgeServer.class);

    private static final int MAX_CONTENT_LENGTH = 65536;

    private final StartupConfig startupConfig;
    private final ForgeConfig forgeConfig;

    private ForgeCluster cluster;
    private LoadGenerator loadGenerator;
    private ConfigurableLoadRunner configurableLoadRunner;
    private ForgeMetrics metrics;
    private ForgeApiHandler apiHandler;
    private StaticFileHandler staticHandler;
    private Option<ForgeH2Server> h2Server = Option.empty();

    private Option<HttpServer> httpServer = Option.empty();
    private ScheduledExecutorService metricsScheduler;

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
        // Load forge config if specified
        var forgeConfig = startupConfig.forgeConfig()
                                       .map(ForgeConfig::load)
                                       .map(r -> r.onFailure(c -> log.error("Failed to load forge config: {}",
                                                                            c.message()))
                                                  .or(ForgeConfig.defaultConfig()))
                                       .or(createDefaultForgeConfig(startupConfig));
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

    private static ForgeConfig createDefaultForgeConfig(StartupConfig startupConfig) {
        // Validation already done in StartupConfig, safe to unwrap
        return ForgeConfig.forgeConfig(startupConfig.clusterSize(),
                                       ForgeConfig.DEFAULT_MANAGEMENT_PORT,
                                       startupConfig.port())
                          .or(ForgeConfig.defaultConfig());
    }

    private static void printBanner(ForgeConfig forgeConfig, StartupConfig startupConfig) {
        log.info("=".repeat(60));
        log.info("    AETHER FORGE");
        log.info("=".repeat(60));
        log.info("  Dashboard: http://localhost:{}", forgeConfig.dashboardPort());
        log.info("  Cluster size: {} nodes", forgeConfig.nodes());
        log.info("  App HTTP port: {} (load target)", forgeConfig.appHttpPort());
        if (forgeConfig.autoHealEnabled()) {
            log.info("  Auto-heal: enabled");
        }
        if (forgeConfig.h2Config().enabled()) {
            log.info("  H2 Database: port {} ({})",
                     forgeConfig.h2Config().port(),
                     forgeConfig.h2Config().persistent()
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

    public void start() throws Exception {
        log.info("Starting Forge server...");
        // Start H2 database if enabled
        startH2Server();
        // Build ConfigurationProvider with H2 URL if enabled
        var configProvider = buildConfigurationProvider();
        // Initialize components
        metrics = ForgeMetrics.forgeMetrics();
        cluster = ForgeCluster.forgeCluster(forgeConfig.nodes(),
                                            ForgeCluster.DEFAULT_BASE_PORT,
                                            forgeConfig.managementPort(),
                                            forgeConfig.appHttpPort(),
                                            "node",
                                            forgeConfig.autoHealEnabled(),
                                            configProvider);
        var entryPointMetrics = EntryPointMetrics.entryPointMetrics();
        // Load generators send to app HTTP port where slice routes are registered
        loadGenerator = LoadGenerator.loadGenerator(forgeConfig.appHttpPort(), metrics, entryPointMetrics);
        configurableLoadRunner = ConfigurableLoadRunner.configurableLoadRunner(cluster::getAvailableAppHttpPorts,
                                                                               metrics,
                                                                               entryPointMetrics);
        apiHandler = ForgeApiHandler.forgeApiHandler(cluster, loadGenerator, metrics, configurableLoadRunner);
        staticHandler = StaticFileHandler.staticFileHandler();
        // Start the cluster
        log.info("Starting {} node cluster...", forgeConfig.nodes());
        cluster.start()
               .await(TimeSpan.timeSpan(60)
                              .seconds())
               .onFailure(cause -> {
                   log.error("Failed to start cluster: {}",
                             cause.message());
                   System.exit(1);
               });
        // Wait for cluster to stabilize
        TimeSpan.timeSpan(2).seconds().sleep();
        // Start metrics collection
        metricsScheduler = Executors.newSingleThreadScheduledExecutor();
        metricsScheduler.scheduleAtFixedRate(metrics::snapshot, 500, 500, TimeUnit.MILLISECONDS);
        // Deploy blueprint if specified
        startupConfig.blueprint()
                     .onPresent(this::deployBlueprint);
        // Load config if specified
        startupConfig.loadConfig()
                     .onPresent(this::loadLoadConfig);
        // Start load generator (legacy or auto-start)
        if (startupConfig.autoStart() && startupConfig.loadConfig()
                                                      .isPresent()) {
            log.info("Auto-starting load generation...");
            configurableLoadRunner.start();
            apiHandler.addEvent("LOAD_STARTED", "Load generation auto-started");
        } else if (startupConfig.loadRate() > 0 && startupConfig.loadConfig()
                                                                .isEmpty()) {
            log.info("Starting load generator at {} req/sec", startupConfig.loadRate());
            loadGenerator.start(startupConfig.loadRate());
        }
        // Add initial event
        apiHandler.addEvent("CLUSTER_STARTED", "Forge cluster started with " + forgeConfig.nodes() + " nodes");
        // Start HTTP server
        startHttpServer();
        // Open browser
        openBrowser("http://localhost:" + forgeConfig.dashboardPort());
        log.info("Forge server running. Press Ctrl+C to stop.");
        // Keep main thread alive
        Thread.currentThread()
              .join();
    }

    private void deployBlueprint(Path blueprintPath) {
        log.info("Deploying blueprint from {}...", blueprintPath);
        try{
            var content = Files.readString(blueprintPath);
            var leaderPort = cluster.getLeaderManagementPort()
                                    .or(forgeConfig.managementPort());
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + leaderPort + "/api/blueprint"))
                                     .header("Content-Type", "application/toml")
                                     .POST(HttpRequest.BodyPublishers.ofString(content))
                                     .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Blueprint deployed successfully");
                apiHandler.addEvent("BLUEPRINT_DEPLOYED", "Blueprint deployed from " + blueprintPath.getFileName());
                // Wait for deployment to propagate
                TimeSpan.timeSpan(1).seconds().sleep();
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
                                       configurableLoadRunner.setConfig(config);
                                       log.info("Load configuration loaded: {} targets",
                                                config.targets()
                                                      .size());
                                       apiHandler.addEvent("LOAD_CONFIG_LOADED",
                                                           "Loaded " + config.targets()
                                                                             .size() + " targets from " + loadConfigPath.getFileName());
                                   })
                        .onFailure(cause -> log.error("Failed to load configuration: {}",
                                                      cause.message()));
    }

    public void stop() {
        log.info("Stopping Forge server...");
        if (loadGenerator != null) {
            loadGenerator.stop();
        }
        if (metricsScheduler != null) {
            metricsScheduler.shutdownNow();
        }
        httpServer.onPresent(server -> server.stop()
                                             .await(TimeSpan.timeSpan(10)
                                                            .seconds())
                                             .onFailure(cause -> log.warn("Error stopping HTTP server: {}",
                                                                          cause.message())));
        if (cluster != null) {
            cluster.stop()
                   .await(TimeSpan.timeSpan(30)
                                  .seconds())
                   .onFailure(cause -> log.warn("Error stopping cluster: {}",
                                                cause.message()));
        }
        stopH2Server();
        log.info("Forge server stopped.");
    }

    private void startH2Server() {
        if (!forgeConfig.h2Config().enabled()) {
            return;
        }
        var server = ForgeH2Server.forgeH2Server(forgeConfig.h2Config());
        server.start()
              .await(TimeSpan.timeSpan(10).seconds())
              .onSuccess(_ -> {
                  h2Server = Option.some(server);
                  log.info("H2 database available at: {}", server.jdbcUrl());
              })
              .onFailure(cause -> {
                  log.error("Failed to start H2 server: {}", cause.message());
                  System.exit(1);
              });
    }

    /**
     * Build ConfigurationProvider with layered configuration.
     * <p>
     * Priority (highest to lowest):
     * <ol>
     *   <li>Runtime values (H2 URL if enabled)</li>
     *   <li>Environment variables (AETHER_*)</li>
     *   <li>System properties (-Daether.*)</li>
     *   <li>forge.toml (if specified)</li>
     *   <li>aether.toml (if exists)</li>
     * </ol>
     *
     * @return ConfigurationProvider for all nodes, or empty if no config needed
     */
    private Option<ConfigurationProvider> buildConfigurationProvider() {
        var builder = ConfigurationProvider.builder();

        // Add TOML files (lowest priority)
        startupConfig.forgeConfig()
                     .map(path -> path.resolveSibling("aether.toml"))
                     .filter(path -> path.toFile().exists())
                     .onPresent(builder::withTomlFile);

        startupConfig.forgeConfig()
                     .onPresent(builder::withTomlFile);

        // Add system properties and environment (higher priority)
        builder.withSystemProperties("aether.")
               .withEnvironment("AETHER_");

        // Inject H2 runtime values (highest priority)
        // Keys must match toSnakeCase(recordComponentName) from DatabaseConnectorConfig
        h2Server.onPresent(server -> {
            Map<String, String> runtimeValues = new HashMap<>();
            runtimeValues.put("database.name", "forge-h2");
            runtimeValues.put("database.type", "H2");
            runtimeValues.put("database.host", "localhost");
            runtimeValues.put("database.port", "0");
            runtimeValues.put("database.database", "forge");
            runtimeValues.put("database.jdbc_url", server.jdbcUrl());
            runtimeValues.put("database.username", "sa");
            runtimeValues.put("database.password", "");
            builder.withSource(MapConfigSource.mapConfigSource("runtime", runtimeValues, 500));
            log.info("Injected H2 configuration into ConfigurationProvider");
        });

        return Option.some(builder.build());
    }

    private void stopH2Server() {
        h2Server.onPresent(server -> server.stop()
                                           .await(TimeSpan.timeSpan(5).seconds())
                                           .onFailure(cause -> log.warn("Error stopping H2 server: {}",
                                                                        cause.message())));
    }

    /**
     * Get the H2 JDBC URL if H2 is enabled and running.
     */
    public Option<String> h2JdbcUrl() {
        return h2Server.filter(ForgeH2Server::isRunning)
                       .map(ForgeH2Server::jdbcUrl);
    }

    private void startHttpServer() {
        var config = HttpServerConfig.httpServerConfig("forge-dashboard",
                                                       forgeConfig.dashboardPort())
                                     .withMaxContentLength(MAX_CONTENT_LENGTH)
                                     .withChunkedWrite();
        var requestHandler = ForgeRequestHandler.forgeRequestHandler(apiHandler, staticHandler);
        HttpServer.httpServer(config, requestHandler::handle)
                  .await(TimeSpan.timeSpan(10)
                                 .seconds())
                  .onSuccess(server -> {
                                 httpServer = Option.some(server);
                                 log.info("HTTP server started on port {}",
                                          server.port());
                             })
                  .onFailure(cause -> {
                      log.error("Failed to start HTTP server: {}",
                                cause.message());
                      System.exit(1);
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
