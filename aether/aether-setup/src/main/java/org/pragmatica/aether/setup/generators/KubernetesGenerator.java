package org.pragmatica.aether.setup.generators;

import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.Environment;
import org.pragmatica.aether.config.KubernetesConfig;
import org.pragmatica.aether.config.ResourcesConfig;
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

/// Generates Kubernetes manifests for Aether cluster deployment.
///
///
/// Generates:
///
///   - namespace.yaml - Namespace definition
///   - configmap.yaml - Cluster configuration
///   - statefulset.yaml - StatefulSet for ordered node deployment
///   - service.yaml - LoadBalancer/ClusterIP service
///   - service-headless.yaml - Headless service for pod discovery
///   - pdb.yaml - PodDisruptionBudget for availability
///   - apply.sh - Script to apply all manifests
///
public final class KubernetesGenerator implements Generator {
    private static final Logger log = LoggerFactory.getLogger(KubernetesGenerator.class);

    @Override
    public boolean supports(AetherConfig config) {
        return config.environment() == Environment.KUBERNETES;
    }

    @Override
    public Result<GeneratorOutput> generate(AetherConfig config, Path outputDir) {
        return Result.lift(KubernetesGenerator::toIoError, () -> generateManifests(config, outputDir));
    }

    @SuppressWarnings("JBCT-EX-01")
    private GeneratorOutput generateManifests(AetherConfig config, Path outputDir) throws Exception {
        var manifestsDir = outputDir.resolve("manifests");
        Files.createDirectories(manifestsDir);
        var generatedFiles = new ArrayList<Path>();
        writeManifest(manifestsDir, "namespace.yaml", generateNamespace(config), generatedFiles);
        writeManifest(manifestsDir, "configmap.yaml", generateConfigMap(config), generatedFiles);
        writeManifest(manifestsDir, "statefulset.yaml", generateStatefulSet(config), generatedFiles);
        writeManifest(manifestsDir, "service-headless.yaml", generateHeadlessService(config), generatedFiles);
        writeManifest(manifestsDir, "service.yaml", generateService(config), generatedFiles);
        writeManifest(manifestsDir, "pdb.yaml", generatePdb(config), generatedFiles);
        writeScript(outputDir, "apply.sh", generateApplyScript(config), generatedFiles);
        writeScript(outputDir, "delete.sh", generateDeleteScript(), generatedFiles);
        return buildOutput(config, outputDir, generatedFiles);
    }

    @SuppressWarnings("JBCT-EX-01")
    private void writeManifest(Path dir, String name, String content, List<Path> files) throws Exception {
        Files.writeString(dir.resolve(name), content);
        files.add(Path.of("manifests/" + name));
    }

    @SuppressWarnings("JBCT-EX-01")
    private void writeScript(Path dir, String name, String content, List<Path> files) throws Exception {
        var path = dir.resolve(name);
        Files.writeString(path, content);
        makeExecutable(path);
        files.add(Path.of(name));
    }

    private GeneratorOutput buildOutput(AetherConfig config, Path outputDir, List<Path> generatedFiles) {
        var k8s = k8sConfig(config);
        var nodes = config.cluster()
                          .nodes();
        var instructions = formatInstructions(outputDir, k8s.namespace(), nodes, k8s.serviceType());
        return GeneratorOutput.generatorOutput(outputDir, generatedFiles, instructions);
    }

    private String formatInstructions(Path outputDir, String namespace, int nodes, String serviceType) {
        return String.format("""
            Kubernetes manifests generated in: %s

            To deploy the cluster:
              cd %s && ./apply.sh

            To delete the cluster:
              ./delete.sh

            Or manually:
              kubectl apply -f manifests/

            Namespace: %s
            Nodes: %d
            Service type: %s
            """,
                             outputDir,
                             outputDir,
                             namespace,
                             nodes,
                             serviceType);
    }

    private KubernetesConfig k8sConfig(AetherConfig config) {
        return config.kubernetes()
                     .expect("Kubernetes config expected");
    }

    private String generateNamespace(AetherConfig config) {
        return String.format("""
            apiVersion: v1
            kind: Namespace
            metadata:
              name: %s
              labels:
                app.kubernetes.io/name: aether
                app.kubernetes.io/component: runtime
            """,
                             k8sConfig(config).namespace());
    }

    private String generateConfigMap(AetherConfig config) {
        var namespace = k8sConfig(config).namespace();
        var nodes = config.cluster()
                          .nodes();
        var clusterPort = config.cluster()
                                .ports()
                                .cluster();
        var mgmtPort = config.cluster()
                             .ports()
                             .management();
        var peerList = buildPeerList(nodes, namespace, clusterPort);
        return formatConfigMap(namespace,
                               peerList,
                               mgmtPort,
                               clusterPort,
                               config.node()
                                     .heap(),
                               gcFlag(config));
    }

    private String buildPeerList(int nodes, String namespace, int clusterPort) {
        return IntStream.range(0, nodes)
                        .mapToObj(i -> peerAddress(i, namespace, clusterPort))
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
    }

    private String peerAddress(int index, String namespace, int clusterPort) {
        return "aether-node-%d.aether-headless.%s.svc.cluster.local:%d".formatted(index, namespace, clusterPort);
    }

    private String formatConfigMap(String namespace,
                                   String peerList,
                                   int mgmtPort,
                                   int clusterPort,
                                   String heap,
                                   String gc) {
        return String.format("""
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: aether-config
              namespace: %s
            data:
              CLUSTER_PEERS: "%s"
              MANAGEMENT_PORT: "%d"
              CLUSTER_PORT: "%d"
              JAVA_OPTS: "-Xmx%s -XX:+Use%s"
            """,
                             namespace,
                             peerList,
                             mgmtPort,
                             clusterPort,
                             heap,
                             gc);
    }

    private String gcFlag(AetherConfig config) {
        return config.node()
                     .gc()
                     .toUpperCase()
                     .equals("ZGC")
               ? "ZGC"
               : "G1GC";
    }

    private String generateStatefulSet(AetherConfig config) {
        var namespace = k8sConfig(config).namespace();
        var resources = config.node()
                              .resources()
                              .or(ResourcesConfig.resourcesConfig());
        var nodes = config.cluster()
                          .nodes();
        var mgmtPort = config.cluster()
                             .ports()
                             .management();
        var clusterPort = config.cluster()
                                .ports()
                                .cluster();
        return formatStatefulSet(namespace, nodes, mgmtPort, clusterPort, resources);
    }

    private String formatStatefulSet(String namespace,
                                     int nodes,
                                     int mgmtPort,
                                     int clusterPort,
                                     ResourcesConfig resources) {
        return String.format("""
            apiVersion: apps/v1
            kind: StatefulSet
            metadata:
              name: aether-node
              namespace: %s
            spec:
              serviceName: aether-headless
              replicas: %d
              podManagementPolicy: Parallel
              selector:
                matchLabels:
                  app: aether-node
              template:
                metadata:
                  labels:
                    app: aether-node
                spec:
                  terminationGracePeriodSeconds: 30
                  containers:
                  - name: aether-node
                    image: ghcr.io/siy/aether-node:latest
                    ports:
                    - containerPort: %d
                      name: management
                    - containerPort: %d
                      name: cluster
                    envFrom:
                    - configMapRef:
                        name: aether-config
                    env:
                    - name: NODE_ID
                      valueFrom:
                        fieldRef:
                          fieldPath: metadata.name
                    resources:
                      requests:
                        cpu: "%s"
                        memory: "%s"
                      limits:
                        cpu: "%s"
                        memory: "%s"
                    readinessProbe:
                      httpGet:
                        path: /health
                        port: management
                      initialDelaySeconds: 10
                      periodSeconds: 5
                    livenessProbe:
                      httpGet:
                        path: /health
                        port: management
                      initialDelaySeconds: 30
                      periodSeconds: 10
            """,
                             namespace,
                             nodes,
                             mgmtPort,
                             clusterPort,
                             resources.cpuRequest(),
                             resources.memoryRequest(),
                             resources.cpuLimit(),
                             resources.memoryLimit());
    }

    private String generateHeadlessService(AetherConfig config) {
        var k8s = k8sConfig(config);
        var mgmtPort = config.cluster()
                             .ports()
                             .management();
        var clusterPort = config.cluster()
                                .ports()
                                .cluster();
        return String.format("""
            apiVersion: v1
            kind: Service
            metadata:
              name: aether-headless
              namespace: %s
              labels:
                app: aether-node
            spec:
              clusterIP: None
              selector:
                app: aether-node
              ports:
              - name: management
                port: %d
                targetPort: management
              - name: cluster
                port: %d
                targetPort: cluster
            """,
                             k8s.namespace(),
                             mgmtPort,
                             clusterPort);
    }

    private String generateService(AetherConfig config) {
        var k8s = k8sConfig(config);
        var mgmtPort = config.cluster()
                             .ports()
                             .management();
        return String.format("""
            apiVersion: v1
            kind: Service
            metadata:
              name: aether
              namespace: %s
            spec:
              type: %s
              selector:
                app: aether-node
              ports:
              - name: management
                port: %d
                targetPort: management
            """,
                             k8s.namespace(),
                             k8s.serviceType(),
                             mgmtPort);
    }

    private String generatePdb(AetherConfig config) {
        var k8s = k8sConfig(config);
        var maxUnavailable = 1;
        return String.format("""
            apiVersion: policy/v1
            kind: PodDisruptionBudget
            metadata:
              name: aether-pdb
              namespace: %s
            spec:
              maxUnavailable: %d
              selector:
                matchLabels:
                  app: aether-node
            """,
                             k8s.namespace(),
                             maxUnavailable);
    }

    private String generateApplyScript(AetherConfig config) {
        var namespace = k8sConfig(config).namespace();
        return String.format("""
            #!/bin/bash
            set -e

            SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

            echo "Deploying Aether cluster to Kubernetes..."
            echo "Namespace: %s"
            echo ""

            kubectl apply -f "$SCRIPT_DIR/manifests/"

            echo ""
            echo "Waiting for pods to be ready..."
            kubectl -n %s rollout status statefulset/aether-node --timeout=120s

            echo ""
            echo "Cluster deployed successfully!"
            kubectl -n %s get pods
            """,
                             namespace,
                             namespace,
                             namespace);
    }

    private String generateDeleteScript() {
        return """
            #!/bin/bash
            set -e

            SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

            echo "Deleting Aether cluster from Kubernetes..."

            kubectl delete -f "$SCRIPT_DIR/manifests/" --ignore-not-found

            echo "Cluster deleted."
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
