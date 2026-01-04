package org.pragmatica.aether.setup.generators;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.Environment;
import org.pragmatica.lang.Result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates Docker Compose deployment for Aether cluster.
 *
 * <p>Generates:
 * <ul>
 *   <li>docker-compose.yml - Service definitions for all nodes</li>
 *   <li>.env - Environment variables</li>
 *   <li>aether.toml - Resolved configuration for reference</li>
 *   <li>start.sh - Cluster start script</li>
 *   <li>stop.sh - Cluster stop script</li>
 *   <li>status.sh - Cluster status script</li>
 * </ul>
 */
public final class DockerGenerator implements Generator {
    @Override
    public boolean supports(AetherConfig config) {
        return config.environment() == Environment.DOCKER;
    }

    @Override
    public Result<GeneratorOutput> generate(AetherConfig config, Path outputDir) {
        try{
            Files.createDirectories(outputDir);
            var generatedFiles = new ArrayList<Path>();
            // Generate docker-compose.yml
            var composePath = outputDir.resolve("docker-compose.yml");
            Files.writeString(composePath, generateDockerCompose(config));
            generatedFiles.add(Path.of("docker-compose.yml"));
            // Generate .env
            var envPath = outputDir.resolve(".env");
            Files.writeString(envPath, generateEnvFile(config));
            generatedFiles.add(Path.of(".env"));
            // Generate scripts
            var startPath = outputDir.resolve("start.sh");
            Files.writeString(startPath, generateStartScript());
            makeExecutable(startPath);
            generatedFiles.add(Path.of("start.sh"));
            var stopPath = outputDir.resolve("stop.sh");
            Files.writeString(stopPath, generateStopScript());
            makeExecutable(stopPath);
            generatedFiles.add(Path.of("stop.sh"));
            var statusPath = outputDir.resolve("status.sh");
            Files.writeString(statusPath, generateStatusScript());
            makeExecutable(statusPath);
            generatedFiles.add(Path.of("status.sh"));
            var instructions = String.format("""
                Docker Compose cluster generated in: %s

                To start the cluster:
                  cd %s && ./start.sh

                To stop the cluster:
                  ./stop.sh

                To check status:
                  ./status.sh

                Nodes: %d
                Management ports: %d-%d
                Cluster ports: %d-%d
                """,
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
            return Result.success(GeneratorOutput.withScripts(
            outputDir, generatedFiles, startPath, stopPath, instructions));
        } catch (IOException e) {
            return GeneratorError.ioError(e.getMessage())
                                 .result();
        }
    }

    private String generateDockerCompose(AetherConfig config) {
        var nodes = config.cluster()
                          .nodes();
        var network = config.docker()
                            .network();
        var image = config.docker()
                          .image();
        var mgmtPort = config.cluster()
                             .ports()
                             .management();
        var clusterPort = config.cluster()
                                .ports()
                                .cluster();
        var nodeNames = IntStream.range(0, nodes)
                                 .mapToObj(i -> "aether-node-" + i)
                                 .toList();
        var peerList = String.join(",",
                                   nodeNames.stream()
                                            .map(name -> name + ":" + clusterPort)
                                            .toList());
        var services = IntStream.range(0, nodes)
                                .mapToObj(i -> generateServiceDefinition(i,
                                                                         nodeNames.get(i),
                                                                         image,
                                                                         mgmtPort,
                                                                         clusterPort,
                                                                         peerList,
                                                                         config))
                                .collect(Collectors.joining("\n"));
        return String.format("""
            # Aether Cluster - Docker Compose
            # Generated by aether-up

            services:
            %s

            networks:
              %s:
                driver: bridge
            """,
                             services,
                             network);
    }

    private String generateServiceDefinition(int index,
                                             String nodeName,
                                             String image,
                                             int mgmtPort,
                                             int clusterPort,
                                             String peerList,
                                             AetherConfig config) {
        var network = config.docker()
                            .network();
        var hostMgmtPort = mgmtPort + index;
        var hostClusterPort = clusterPort + index;
        return String.format("""
              %s:
                image: %s
                container_name: %s
                hostname: %s
                environment:
                  - NODE_ID=%d
                  - CLUSTER_PEERS=%s
                  - MANAGEMENT_PORT=%d
                  - CLUSTER_PORT=%d
                  - JAVA_OPTS=-Xmx%s -XX:+Use%s
                ports:
                  - "%d:%d"
                  - "%d:%d"
                networks:
                  - %s
                healthcheck:
                  test: ["CMD", "curl", "-f", "http://localhost:%d/health"]
                  interval: 10s
                  timeout: 5s
                  retries: 3
                  start_period: 30s
            """,
                             nodeName,
                             image,
                             nodeName,
                             nodeName,
                             index,
                             peerList,
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
                             hostMgmtPort,
                             mgmtPort,
                             hostClusterPort,
                             clusterPort,
                             network,
                             mgmtPort);
    }

    private String generateEnvFile(AetherConfig config) {
        return String.format("""
            # Aether Cluster Environment
            NODES=%d
            MANAGEMENT_PORT=%d
            CLUSTER_PORT=%d
            NETWORK=%s
            IMAGE=%s
            HEAP=%s
            GC=%s
            """,
                             config.cluster()
                                   .nodes(),
                             config.cluster()
                                   .ports()
                                   .management(),
                             config.cluster()
                                   .ports()
                                   .cluster(),
                             config.docker()
                                   .network(),
                             config.docker()
                                   .image(),
                             config.node()
                                   .heap(),
                             config.node()
                                   .gc());
    }

    private String generateStartScript() {
        return """
            #!/bin/bash
            set -e

            echo "Starting Aether cluster..."
            docker compose up -d

            echo ""
            echo "Waiting for nodes to become healthy..."
            sleep 5

            docker compose ps
            echo ""
            echo "Cluster started. Use './status.sh' to check health."
            """;
    }

    private String generateStopScript() {
        return """
            #!/bin/bash
            set -e

            echo "Stopping Aether cluster..."
            docker compose down
            echo "Cluster stopped."
            """;
    }

    private String generateStatusScript() {
        return """
            #!/bin/bash

            echo "=== Aether Cluster Status ==="
            echo ""
            docker compose ps
            echo ""
            echo "=== Node Health ==="
            for port in $(seq $MANAGEMENT_PORT $((MANAGEMENT_PORT + NODES - 1))); do
                echo -n "Node on port $port: "
                curl -s http://localhost:$port/health 2>/dev/null || echo "unreachable"
            done
            """;
    }

    private void makeExecutable(Path path) throws IOException {
        try{
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException e) {}
    }
}
