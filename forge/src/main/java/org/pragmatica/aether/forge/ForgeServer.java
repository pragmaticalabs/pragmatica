package org.pragmatica.aether.forge;

import org.pragmatica.aether.forge.load.ConfigurableLoadRunner;
import org.pragmatica.aether.forge.load.LoadConfigLoader;
import org.pragmatica.aether.forge.simulator.EntryPointMetrics;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedWriteHandler;
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

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
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
                                                  .or(ForgeConfig.defaults()))
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
                          .or(ForgeConfig.defaults());
    }

    private static void printBanner(ForgeConfig forgeConfig, StartupConfig startupConfig) {
        log.info("=".repeat(60));
        log.info("    AETHER FORGE");
        log.info("=".repeat(60));
        log.info("  Dashboard: http://localhost:{}", forgeConfig.dashboardPort());
        log.info("  Cluster size: {} nodes", forgeConfig.nodes());
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
        // Initialize components
        metrics = ForgeMetrics.forgeMetrics();
        cluster = ForgeCluster.forgeCluster(forgeConfig.nodes());
        var entryPointMetrics = EntryPointMetrics.entryPointMetrics();
        loadGenerator = LoadGenerator.loadGenerator(forgeConfig.dashboardPort(), metrics, entryPointMetrics);
        configurableLoadRunner = ConfigurableLoadRunner.configurableLoadRunner(forgeConfig.dashboardPort(),
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
        Thread.sleep(2000);
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
                Thread.sleep(1000);
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
        if (serverChannel != null) {
            serverChannel.close()
                         .syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (cluster != null) {
            cluster.stop()
                   .await(TimeSpan.timeSpan(30)
                                  .seconds())
                   .onFailure(cause -> log.warn("Error stopping cluster: {}",
                                                cause.message()));
        }
        log.info("Forge server stopped.");
    }

    private void startHttpServer() throws InterruptedException {
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        var bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                 .channel(NioServerSocketChannel.class)
                 .childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                                   var pipeline = ch.pipeline();
                                   pipeline.addLast(new HttpServerCodec());
                                   pipeline.addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
                                   pipeline.addLast(new ChunkedWriteHandler());
                                   pipeline.addLast(new ForgeHttpHandler(apiHandler, staticHandler));
                               }
        })
                 .option(ChannelOption.SO_BACKLOG, 128)
                 .childOption(ChannelOption.SO_KEEPALIVE, true);
        serverChannel = bootstrap.bind(forgeConfig.dashboardPort())
                                 .sync()
                                 .channel();
        log.info("HTTP server started on port {}", forgeConfig.dashboardPort());
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

    /**
     * Routes requests to API handler or static file handler.
     */
    @ChannelHandler.Sharable
    private static class ForgeHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final ForgeApiHandler apiHandler;
        private final StaticFileHandler staticHandler;

        ForgeHttpHandler(ForgeApiHandler apiHandler, StaticFileHandler staticHandler) {
            this.apiHandler = apiHandler;
            this.staticHandler = staticHandler;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
            var path = request.uri();
            // Handle CORS preflight
            if (request.method() == HttpMethod.OPTIONS) {
                handleCors(ctx);
                return;
            }
            // Route to appropriate handler
            if (path.startsWith("/api/")) {
                apiHandler.channelRead(ctx, request.retain());
            } else {
                staticHandler.channelRead(ctx, request.retain());
            }
        }

        private void handleCors(ChannelHandlerContext ctx) {
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers()
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            response.headers()
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
            response.headers()
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
            response.headers()
                    .set(HttpHeaderNames.CONTENT_LENGTH, 0);
            ctx.writeAndFlush(response)
               .addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Error in HTTP request handler", cause);
            ctx.close();
        }
    }
}
