package org.pragmatica.aether.forge;

import org.pragmatica.lang.io.TimeSpan;

import java.awt.*;
import java.net.URI;
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
 */
public final class ForgeServer {
    private static final Logger log = LoggerFactory.getLogger(ForgeServer.class);

    private static final int DEFAULT_PORT = 8888;
    private static final int DEFAULT_CLUSTER_SIZE = 5;
    private static final int DEFAULT_LOAD_RATE = 1000;
    private static final int MAX_CONTENT_LENGTH = 65536;

    // 64KB
    private final int port;
    private final int clusterSize;
    private final int loadRate;

    private ForgeCluster cluster;
    private LoadGenerator loadGenerator;
    private ForgeMetrics metrics;
    private LocalSliceInvoker sliceInvoker;
    private ForgeApiHandler apiHandler;
    private StaticFileHandler staticHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private ScheduledExecutorService metricsScheduler;

    private ForgeServer(int port, int clusterSize, int loadRate) {
        this.port = port;
        this.clusterSize = clusterSize;
        this.loadRate = loadRate;
    }

    public static void main(String[] args) {
        var port = getEnvInt("FORGE_PORT", DEFAULT_PORT);
        var clusterSize = getEnvInt("CLUSTER_SIZE", DEFAULT_CLUSTER_SIZE);
        var loadRate = getEnvInt("LOAD_RATE", DEFAULT_LOAD_RATE);
        log.info("=".repeat(60));
        log.info("    AETHER FORGE");
        log.info("=".repeat(60));
        log.info("  Dashboard: http://localhost:{}", port);
        log.info("  Cluster size: {} nodes", clusterSize);
        log.info("  Initial load: {} req/sec", loadRate);
        log.info("=".repeat(60));
        var server = new ForgeServer(port, clusterSize, loadRate);
        // Handle shutdown gracefully
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

    public void start() throws Exception {
        log.info("Starting Forge server...");
        // Initialize components
        metrics = ForgeMetrics.forgeMetrics();
        cluster = ForgeCluster.forgeCluster(clusterSize);
        loadGenerator = LoadGenerator.loadGenerator(port, metrics);
        sliceInvoker = LocalSliceInvoker.localSliceInvoker();
        apiHandler = ForgeApiHandler.forgeApiHandler(cluster, loadGenerator, metrics, sliceInvoker);
        staticHandler = StaticFileHandler.staticFileHandler();
        log.info("Forge running in standalone mode (no slices loaded)");
        // Start the cluster
        log.info("Starting {} node cluster...", clusterSize);
        cluster.start()
               .await(TimeSpan.timeSpan(60)
                              .seconds())
               .onFailure(cause -> {
                              log.error("Failed to start cluster: {}",
                                        cause.message());
                              throw new RuntimeException("Cluster start failed: " + cause.message());
                          });
        // Wait for cluster to stabilize
        Thread.sleep(2000);
        // Start metrics collection
        metricsScheduler = Executors.newSingleThreadScheduledExecutor();
        metricsScheduler.scheduleAtFixedRate(metrics::snapshot, 500, 500, TimeUnit.MILLISECONDS);
        // Start load generator
        log.info("Starting load generator at {} req/sec", loadRate);
        loadGenerator.start(loadRate);
        // Add initial event
        apiHandler.addEvent("CLUSTER_STARTED", "Forge cluster started with " + clusterSize + " nodes");
        // Start HTTP server
        startHttpServer();
        // Open browser
        openBrowser("http://localhost:" + port);
        log.info("Forge server running. Press Ctrl+C to stop.");
        // Keep main thread alive
        Thread.currentThread()
              .join();
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
        serverChannel = bootstrap.bind(port)
                                 .sync()
                                 .channel();
        log.info("HTTP server started on port {}", port);
    }

    private void openBrowser(String url) {
        try{
            if (Desktop.isDesktopSupported() && Desktop.getDesktop()
                                                       .isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop()
                       .browse(new URI(url));
                log.info("Opened browser to {}", url);
            }else {
                log.info("Could not open browser automatically. Please navigate to: {}", url);
            }
        } catch (Exception e) {
            log.info("Could not open browser automatically. Please navigate to: {}", url);
        }
    }

    private static int getEnvInt(String name, int defaultValue) {
        var value = System.getenv(name);
        if (value != null) {
            try{
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                log.warn("Invalid {} value: {}, using default: {}", name, value, defaultValue);
            }
        }
        return defaultValue;
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
            }else {
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
            cause.printStackTrace();
            ctx.close();
        }
    }
}
