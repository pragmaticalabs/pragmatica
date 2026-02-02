package org.pragmatica.aether.setup.generators;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.Environment;
import org.pragmatica.lang.Result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates shell scripts for local (single-machine) Aether cluster.
 *
 * <p>Generates:
 * <ul>
 *   <li>start.sh - Starts all nodes as background processes</li>
 *   <li>stop.sh - Stops all running nodes</li>
 *   <li>status.sh - Shows status of all nodes</li>
 *   <li>logs/ - Directory for node logs</li>
 * </ul>
 */
public final class LocalGenerator implements Generator {
    private static final Logger log = LoggerFactory.getLogger(LocalGenerator.class);

    @Override
    public boolean supports(AetherConfig config) {
        return config.environment() == Environment.LOCAL;
    }

    @Override
    public Result<GeneratorOutput> generate(AetherConfig config, Path outputDir) {
        try{
            Files.createDirectories(outputDir);
            Files.createDirectories(outputDir.resolve("logs"));
            var generatedFiles = new ArrayList<Path>();
            // Generate start script
            var startPath = outputDir.resolve("start.sh");
            Files.writeString(startPath, generateStartScript(config));
            makeExecutable(startPath);
            generatedFiles.add(Path.of("start.sh"));
            // Generate stop script
            var stopPath = outputDir.resolve("stop.sh");
            Files.writeString(stopPath, generateStopScript(config));
            makeExecutable(stopPath);
            generatedFiles.add(Path.of("stop.sh"));
            // Generate status script
            var statusPath = outputDir.resolve("status.sh");
            Files.writeString(statusPath, generateStatusScript(config));
            makeExecutable(statusPath);
            generatedFiles.add(Path.of("status.sh"));
            generatedFiles.add(Path.of("logs/"));
            var instructions = String.format("""
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
                                             config.cluster()
                                                   .nodes(),
                                             config.cluster()
                                                   .ports()
                                                   .management(),
                                             config.cluster()
                                                   .ports()
                                                   .management() + config.cluster()
                                                                        .nodes() - 1,
                                             config.cluster()
                                                   .ports()
                                                   .cluster(),
                                             config.cluster()
                                                   .ports()
                                                   .cluster() + config.cluster()
                                                                     .nodes() - 1);
            return Result.success(GeneratorOutput.generatorOutput(outputDir,
                                                                  generatedFiles,
                                                                  startPath,
                                                                  stopPath,
                                                                  instructions));
        } catch (IOException e) {
            return GeneratorError.ioError(e.getMessage())
                                 .result();
        }
    }

    private String generateStartScript(AetherConfig config) {
        var nodes = config.cluster()
                          .nodes();
        var mgmtPort = config.cluster()
                             .ports()
                             .management();
        var clusterPort = config.cluster()
                                .ports()
                                .cluster();
        var peerList = IntStream.range(0, nodes)
                                .mapToObj(i -> "localhost:" + (clusterPort + i))
                                .reduce((a, b) -> a + "," + b)
                                .orElse("");
        var nodeStarts = IntStream.range(0, nodes)
                                  .mapToObj(i -> generateNodeStart(i, config, peerList))
                                  .reduce((a, b) -> a + "\n" + b)
                                  .orElse("");
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

    private String generateNodeStart(int index, AetherConfig config, String peerList) {
        var mgmtPort = config.cluster()
                             .ports()
                             .management() + index;
        var clusterPort = config.cluster()
                                .ports()
                                .cluster() + index;
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
                             config.node()
                                   .heap(),
                             config.node()
                                   .gc()
                                   .toUpperCase()
                                   .equals("ZGC")
                             ? "ZGC"
                             : "G1GC",
                             index,
                             mgmtPort,
                             clusterPort,
                             peerList,
                             index,
                             index);
    }

    private String generateStopScript(AetherConfig config) {
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
                             config.cluster()
                                   .nodes() - 1);
    }

    private String generateStatusScript(AetherConfig config) {
        var mgmtPort = config.cluster()
                             .ports()
                             .management();
        var nodes = config.cluster()
                          .nodes();
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
                             nodes - 1,
                             mgmtPort);
    }

    private void makeExecutable(Path path) throws IOException {
        try{
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException e) {
            // POSIX permissions not supported on this filesystem (e.g., Windows)
            log.debug("Cannot set POSIX permissions on {}: {}", path, e.getMessage());
        }
    }
}
