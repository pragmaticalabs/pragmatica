package org.pragmatica.aether.setup.generators;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.DockerConfig;
import org.pragmatica.aether.config.Environment;
import org.pragmatica.lang.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;

/// Generates Docker Compose deployment for Aether cluster.
///
///
/// Generates:
///
///   - docker-compose.yml - Service definitions for all nodes
///   - .env - Environment variables
///   - aether.toml - Resolved configuration for reference
///   - start.sh - Cluster start script
///   - stop.sh - Cluster stop script
///   - status.sh - Cluster status script
///
public final class DockerGenerator implements Generator {
    private static final Logger log = LoggerFactory.getLogger(DockerGenerator.class);

    @Override
    public boolean supports(AetherConfig config) {
        return config.environment() == Environment.DOCKER;
    }

    @Override
    public Result<GeneratorOutput> generate(AetherConfig config, Path outputDir) {
        return Result.lift(DockerGenerator::toIoError, () -> generateArtifacts(config, outputDir));
    }

    @SuppressWarnings("JBCT-EX-01")
    private GeneratorOutput generateArtifacts(AetherConfig config, Path outputDir) throws Exception {
        Files.createDirectories(outputDir);
        var generatedFiles = new ArrayList<Path>();
        writeFile(outputDir, "docker-compose.yml", generateDockerCompose(config), generatedFiles);
        writeFile(outputDir, ".env", generateEnvFile(config), generatedFiles);
        var startPath = writeScript(outputDir, "start.sh", generateStartScript(), generatedFiles);
        var stopPath = writeScript(outputDir, "stop.sh", generateStopScript(), generatedFiles);
        writeScript(outputDir, "status.sh", generateStatusScript(), generatedFiles);
        var instructions = formatInstructions(config, outputDir);
        return GeneratorOutput.generatorOutput(outputDir, generatedFiles, startPath, stopPath, instructions);
    }

    @SuppressWarnings("JBCT-EX-01")
    private void writeFile(Path dir, String name, String content, List<Path> files) throws Exception {
        Files.writeString(dir.resolve(name), content);
        files.add(Path.of(name));
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
                             nodes,
                             mgmtPort,
                             mgmtPort + nodes - 1,
                             clusterPort,
                             clusterPort + nodes - 1);
    }

    private DockerConfig dockerConfig(AetherConfig config) {
        return config.docker()
                     .expect("Docker config expected");
    }

    private String generateDockerCompose(AetherConfig config) {
        var dockerConf = dockerConfig(config);
        var nodes = config.cluster()
                          .nodes();
        var mgmtPort = config.cluster()
                             .ports()
                             .management();
        var clusterPort = config.cluster()
                                .ports()
                                .cluster();
        var heap = config.node()
                         .heap();
        var gc = gcFlag(config);
        var nodeNames = buildNodeNames(nodes);
        var peerList = buildPeerList(nodeNames, clusterPort);
        var services = buildServices(nodes, nodeNames, dockerConf, mgmtPort, clusterPort, peerList, heap, gc);
        return formatComposeFile(services, dockerConf.network());
    }

    private List<String> buildNodeNames(int nodes) {
        return IntStream.range(0, nodes)
                        .mapToObj(i -> "aether-node-" + i)
                        .toList();
    }

    private String buildPeerList(List<String> nodeNames, int clusterPort) {
        return nodeNames.stream()
                        .map(name -> name + ":" + clusterPort)
                        .collect(Collectors.joining(","));
    }

    private String buildServices(int nodes,
                                 List<String> nodeNames,
                                 DockerConfig dockerConf,
                                 int mgmtPort,
                                 int clusterPort,
                                 String peerList,
                                 String heap,
                                 String gc) {
        return IntStream.range(0, nodes)
                        .mapToObj(i -> serviceYaml(i,
                                                   nodeNames.get(i),
                                                   dockerConf,
                                                   mgmtPort,
                                                   clusterPort,
                                                   peerList,
                                                   heap,
                                                   gc))
                        .collect(Collectors.joining("\n"));
    }

    private String formatComposeFile(String services, String network) {
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

    private String serviceYaml(int index,
                               String nodeName,
                               DockerConfig dockerConf,
                               int mgmtPort,
                               int clusterPort,
                               String peerList,
                               String heap,
                               String gc) {
        var hostMgmtPort = mgmtPort + index;
        var hostClusterPort = clusterPort + index;
        return formatServiceYaml(nodeName,
                                 dockerConf.image(),
                                 index,
                                 peerList,
                                 mgmtPort,
                                 clusterPort,
                                 hostMgmtPort,
                                 hostClusterPort,
                                 dockerConf.network(),
                                 heap,
                                 gc);
    }

    private String formatServiceYaml(String nodeName,
                                     String image,
                                     int index,
                                     String peerList,
                                     int mgmtPort,
                                     int clusterPort,
                                     int hostMgmtPort,
                                     int hostClusterPort,
                                     String network,
                                     String heap,
                                     String gc) {
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
                             heap,
                             gc,
                             hostMgmtPort,
                             mgmtPort,
                             hostClusterPort,
                             clusterPort,
                             network,
                             mgmtPort);
    }

    private String gcFlag(AetherConfig config) {
        return config.node()
                     .gc()
                     .toUpperCase()
                     .equals("ZGC")
               ? "ZGC"
               : "G1GC";
    }

    private String generateEnvFile(AetherConfig config) {
        var dockerConf = dockerConfig(config);
        var nodes = config.cluster()
                          .nodes();
        var mgmtPort = config.cluster()
                             .ports()
                             .management();
        var clusterPort = config.cluster()
                                .ports()
                                .cluster();
        return formatEnvFile(nodes,
                             mgmtPort,
                             clusterPort,
                             dockerConf,
                             config.node()
                                   .heap(),
                             config.node()
                                   .gc());
    }

    private String formatEnvFile(int nodes,
                                 int mgmtPort,
                                 int clusterPort,
                                 DockerConfig dockerConf,
                                 String heap,
                                 String gc) {
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
                             nodes,
                             mgmtPort,
                             clusterPort,
                             dockerConf.network(),
                             dockerConf.image(),
                             heap,
                             gc);
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
