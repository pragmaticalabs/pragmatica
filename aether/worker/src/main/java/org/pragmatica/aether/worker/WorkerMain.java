package org.pragmatica.aether.worker;

import org.pragmatica.aether.config.WorkerConfig;
import org.pragmatica.aether.config.WorkerConfigLoader;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.config.SliceConfig;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Main entry point for starting an Aether worker node.
///
/// Usage: java -jar aether-worker.jar [options]
///
/// Options:
///   --config=path    Path to worker.toml config file
///   --node-id=id     Node identifier (default: random)
///   --peers=h:p,...  Comma-separated core node addresses
@SuppressWarnings("JBCT-RET-01")
public record WorkerMain(String[] args) {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerMain.class);

    public static void main(String[] args) {
        new WorkerMain(args).run();
    }

    private void run() {
        var config = loadConfig();
        var nodeId = parseNodeId();
        logStartupInfo(config, nodeId);
        var node = createNode(config, nodeId);
        registerShutdownHook(node);
        startNodeAndWait(node, nodeId);
    }

    private WorkerConfig loadConfig() {
        return findArg("--config=").map(Path::of)
                      .filter(p -> p.toFile()
                                    .exists())
                      .flatMap(this::loadConfigFile)
                      .or(this::defaultConfig);
    }

    private Option<WorkerConfig> loadConfigFile(Path path) {
        return WorkerConfigLoader.load(path)
                                 .onFailure(cause -> LOG.error("Failed to load config: {}",
                                                               cause.message()))
                                 .option();
    }

    private WorkerConfig defaultConfig() {
        var peers = findArg("--peers=").map(this::parsePeersList)
                           .or(List.of());
        return WorkerConfig.workerConfig(peers,
                                         WorkerConfig.DEFAULT_CLUSTER_PORT,
                                         WorkerConfig.DEFAULT_SWIM_PORT,
                                         WorkerConfig.SwimSettings.swimSettings(),
                                         SliceConfig.sliceConfig())
                           .unwrap();
    }

    private List<String> parsePeersList(String peersStr) {
        return Arrays.stream(peersStr.split(","))
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .toList();
    }

    private NodeId parseNodeId() {
        return findArg("--node-id=").flatMap(id -> NodeId.nodeId(id)
                                                         .option())
                      .or(NodeId::randomNodeId);
    }

    private void logStartupInfo(WorkerConfig config, NodeId nodeId) {
        LOG.info("Starting Aether worker node {}", nodeId.id());
        LOG.info("Core nodes: {}", config.coreNodes());
        LOG.info("Cluster port: {}, SWIM port: {}", config.clusterPort(), config.swimPort());
    }

    private WorkerNode createNode(WorkerConfig config, NodeId nodeId) {
        return WorkerNode.workerNode(config, nodeId)
                         .unwrap();
    }

    private void registerShutdownHook(WorkerNode node) {
        Runtime.getRuntime()
               .addShutdownHook(new Thread(() -> shutdownNode(node)));
    }

    private void shutdownNode(WorkerNode node) {
        LOG.info("Shutdown requested, stopping worker node...");
        node.stop()
            .await();
        LOG.info("Worker node stopped");
    }

    private void startNodeAndWait(WorkerNode node, NodeId nodeId) {
        node.start()
            .onSuccess(_ -> LOG.info("Worker node {} is running. Press Ctrl+C to stop.",
                                     nodeId.id()))
            .onFailure(cause -> exitWithError(cause.message()))
            .await();
        waitForInterrupt();
    }

    private void exitWithError(String message) {
        LOG.error("Failed to start worker node: {}", message);
        System.exit(1);
    }

    @SuppressWarnings("JBCT-EX-01")
    private void waitForInterrupt() {
        try{
            Thread.currentThread()
                  .join();
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
    }

    private Option<String> findArg(String prefix) {
        return Option.from(Arrays.stream(args)
                                 .filter(arg -> arg.startsWith(prefix))
                                 .map(arg -> arg.substring(prefix.length()))
                                 .findFirst());
    }
}
