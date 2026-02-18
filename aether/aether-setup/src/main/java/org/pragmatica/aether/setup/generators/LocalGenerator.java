package org.pragmatica.aether.setup.generators;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.Environment;
import org.pragmatica.lang.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;

/// Generates shell scripts for local (single-machine) Aether cluster.
///
///
/// Generates:
///
///   - start.sh - Starts all nodes as background processes
///   - stop.sh - Stops all running nodes
///   - status.sh - Shows status of all nodes
///   - logs/ - Directory for node logs
///
public final class LocalGenerator implements Generator {
    private static final Logger log = LoggerFactory.getLogger(LocalGenerator.class);

    @Override
    public boolean supports(AetherConfig config) {
        return config.environment() == Environment.LOCAL;
    }

    @Override
    public Result<GeneratorOutput> generate(AetherConfig config, Path outputDir) {
        return Result.lift(LocalGenerator::toIoError, () -> generateScripts(config, outputDir));
    }

    @SuppressWarnings("JBCT-EX-01")
    private GeneratorOutput generateScripts(AetherConfig config, Path outputDir) throws Exception {
        Files.createDirectories(outputDir);
        Files.createDirectories(outputDir.resolve("logs"));
        var generatedFiles = new ArrayList<Path>();
        var startPath = writeScript(outputDir, "start.sh", generateStartScript(config), generatedFiles);
        var stopPath = writeScript(outputDir, "stop.sh", generateStopScript(config), generatedFiles);
        writeScript(outputDir, "status.sh", generateStatusScript(config), generatedFiles);
        generatedFiles.add(Path.of("logs/"));
        var instructions = formatInstructions(config, outputDir);
        return GeneratorOutput.generatorOutput(outputDir, generatedFiles, startPath, stopPath, instructions);
    }

    @SuppressWarnings("JBCT-EX-01")
    private Path writeScript(Path dir, String name, String content, List<Path> files) throws Exception {
        var path = dir.resolve(name);
        Files.writeString(path, content);
        makeExecutable(path);
        files.add(Path.of(name));
        return path;
    }

    private String formatInstructions(AetherConfig config, Path outputDir) {
        var nodes = config.cluster()
                          .nodes();
        var mgmtPort = config.cluster()
                             .ports()
                             .management();
        var clusterPort = config.cluster()
                                .ports()
                                .cluster();
        return formatInstructionsText(outputDir, nodes, mgmtPort, clusterPort);
    }

    private String formatInstructionsText(Path outputDir, int nodes, int mgmtPort, int clusterPort) {
        return String.format("""
            Local cluster scripts generated in: %s

            Prerequisites:
              - Java 21+ installed
              - aether-node JAR available (set AETHER_JAR environment variable)

            To start the cluster:
              cd %s && ./start.sh

            To stop the cluster:
              ./stop.sh

            To check status:
              ./status.sh

            Logs are written to: %s/logs/

            Nodes: %d
            Management ports: %d-%d
            Cluster ports: %d-%d
            """,
                             outputDir,
                             outputDir,
                             outputDir,
                             nodes,
                             mgmtPort,
                             mgmtPort + nodes - 1,
                             clusterPort,
                             clusterPort + nodes - 1);
    }

    private String generateStartScript(AetherConfig config) {
        var nodes = config.cluster()
                          .nodes();
        var clusterPort = config.cluster()
                                .ports()
                                .cluster();
        var peerList = buildPeerList(nodes, clusterPort);
        var nodeStarts = buildNodeStarts(nodes, config, peerList);
        return String.format("""
            #!/bin/bash
            set -e

            SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
            AETHER_JAR="${AETHER_JAR:-aether-node.jar}"

            if [ ! -f "$AETHER_JAR" ]; then
                echo "Error: aether-node JAR not found at: $AETHER_JAR"
                echo "Set AETHER_JAR environment variable to the correct path."
                exit 1
            fi

            echo "Starting Aether cluster (%d nodes)..."

            mkdir -p "$SCRIPT_DIR/logs"

            %s

            echo ""
            echo "Cluster started. Logs in: $SCRIPT_DIR/logs/"
            echo "Use './status.sh' to check health."
            """,
                             nodes,
                             nodeStarts);
    }

    private String buildPeerList(int nodes, int clusterPort) {
        return IntStream.range(0, nodes)
                        .mapToObj(i -> "localhost:" + (clusterPort + i))
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
    }

    private String buildNodeStarts(int nodes, AetherConfig config, String peerList) {
        return IntStream.range(0, nodes)
                        .mapToObj(i -> nodeStartScript(i, config, peerList))
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");
    }

    private String nodeStartScript(int index, AetherConfig config, String peerList) {
        var mgmtPort = config.cluster()
                             .ports()
                             .management() + index;
        var clusterPort = config.cluster()
                                .ports()
                                .cluster() + index;
        var heap = config.node()
                         .heap();
        var gc = gcFlag(config);
        return formatNodeStart(index, mgmtPort, clusterPort, heap, gc, peerList);
    }

    private String formatNodeStart(int index, int mgmtPort, int clusterPort, String heap, String gc, String peerList) {
        return String.format("""
            echo "Starting node %d (management: %d, cluster: %d)..."
            java -Xmx%s -XX:+Use%s \\
                -DNODE_ID=%d \\
                -DMANAGEMENT_PORT=%d \\
                -DCLUSTER_PORT=%d \\
                -DCLUSTER_PEERS=%s \\
                -jar "$AETHER_JAR" \\
                > "$SCRIPT_DIR/logs/node-%d.log" 2>&1 &
            echo $! > "$SCRIPT_DIR/logs/node-%d.pid"
            """,
                             index,
                             mgmtPort,
                             clusterPort,
                             heap,
                             gc,
                             index,
                             mgmtPort,
                             clusterPort,
                             peerList,
                             index,
                             index);
    }

    private String gcFlag(AetherConfig config) {
        return config.node()
                     .gc()
                     .toUpperCase()
                     .equals("ZGC")
               ? "ZGC"
               : "G1GC";
    }

    private String generateStopScript(AetherConfig config) {
        var lastNode = config.cluster()
                             .nodes() - 1;
        return String.format("""
            #!/bin/bash

            SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

            echo "Stopping Aether cluster..."

            for i in $(seq 0 %d); do
                PID_FILE="$SCRIPT_DIR/logs/node-$i.pid"
                if [ -f "$PID_FILE" ]; then
                    PID=$(cat "$PID_FILE")
                    if kill -0 "$PID" 2>/dev/null; then
                        echo "Stopping node $i (PID: $PID)..."
                        kill "$PID"
                    fi
                    rm -f "$PID_FILE"
                fi
            done

            echo "Cluster stopped."
            """,
                             lastNode);
    }

    private String generateStatusScript(AetherConfig config) {
        var mgmtPort = config.cluster()
                             .ports()
                             .management();
        var lastNode = config.cluster()
                             .nodes() - 1;
        return String.format("""
            #!/bin/bash

            SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

            echo "=== Aether Cluster Status ==="
            echo ""

            for i in $(seq 0 %d); do
                PORT=$((${%d} + i))
                PID_FILE="$SCRIPT_DIR/logs/node-$i.pid"

                echo -n "Node $i (port $PORT): "

                if [ -f "$PID_FILE" ]; then
                    PID=$(cat "$PID_FILE")
                    if kill -0 "$PID" 2>/dev/null; then
                        HEALTH=$(curl -s http://localhost:$PORT/health 2>/dev/null || echo "unreachable")
                        echo "running (PID: $PID) - $HEALTH"
                    else
                        echo "dead (stale PID file)"
                    fi
                else
                    echo "not running"
                fi
            done
            """,
                             lastNode,
                             mgmtPort);
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private void makeExecutable(Path path) {
        try{
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException e) {
            // POSIX permissions not supported on this filesystem (e.g., Windows)
            log.debug("Cannot set POSIX permissions on {}: {}", path, e.getMessage());
        } catch (Exception e) {
            log.debug("Failed to set permissions on {}: {}", path, e.getMessage());
        }
    }

    private static GeneratorError toIoError(Throwable throwable) {
        return GeneratorError.ioError(throwable.getMessage());
    }
}
