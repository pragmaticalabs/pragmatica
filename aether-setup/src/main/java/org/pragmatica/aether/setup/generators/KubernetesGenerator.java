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

/**
 * Generates Kubernetes manifests for Aether cluster deployment.
 *
 * <p>Generates:
 * <ul>
 *   <li>namespace.yaml - Namespace definition</li>
 *   <li>configmap.yaml - Cluster configuration</li>
 *   <li>statefulset.yaml - StatefulSet for ordered node deployment</li>
 *   <li>service.yaml - LoadBalancer/ClusterIP service</li>
 *   <li>service-headless.yaml - Headless service for pod discovery</li>
 *   <li>pdb.yaml - PodDisruptionBudget for availability</li>
 *   <li>apply.sh - Script to apply all manifests</li>
 * </ul>
 */
public final class KubernetesGenerator implements Generator {
    @Override
    public boolean supports(AetherConfig config) {
        return config.environment() == Environment.KUBERNETES;
    }

    @Override
    public Result<GeneratorOutput> generate(AetherConfig config, Path outputDir) {
        try{
            var manifestsDir = outputDir.resolve("manifests");
            Files.createDirectories(manifestsDir);
            var generatedFiles = new ArrayList<Path>();
            // Generate namespace
            var namespacePath = manifestsDir.resolve("namespace.yaml");
            Files.writeString(namespacePath, generateNamespace(config));
            generatedFiles.add(Path.of("manifests/namespace.yaml"));
            // Generate configmap
            var configmapPath = manifestsDir.resolve("configmap.yaml");
            Files.writeString(configmapPath, generateConfigMap(config));
            generatedFiles.add(Path.of("manifests/configmap.yaml"));
            // Generate statefulset
            var statefulsetPath = manifestsDir.resolve("statefulset.yaml");
            Files.writeString(statefulsetPath, generateStatefulSet(config));
            generatedFiles.add(Path.of("manifests/statefulset.yaml"));
            // Generate headless service
            var headlessPath = manifestsDir.resolve("service-headless.yaml");
            Files.writeString(headlessPath, generateHeadlessService(config));
            generatedFiles.add(Path.of("manifests/service-headless.yaml"));
            // Generate service
            var servicePath = manifestsDir.resolve("service.yaml");
            Files.writeString(servicePath, generateService(config));
            generatedFiles.add(Path.of("manifests/service.yaml"));
            // Generate PDB
            var pdbPath = manifestsDir.resolve("pdb.yaml");
            Files.writeString(pdbPath, generatePdb(config));
            generatedFiles.add(Path.of("manifests/pdb.yaml"));
            // Generate apply script
            var applyPath = outputDir.resolve("apply.sh");
            Files.writeString(applyPath, generateApplyScript(config));
            makeExecutable(applyPath);
            generatedFiles.add(Path.of("apply.sh"));
            // Generate delete script
            var deletePath = outputDir.resolve("delete.sh");
            Files.writeString(deletePath, generateDeleteScript(config));
            makeExecutable(deletePath);
            generatedFiles.add(Path.of("delete.sh"));
            var instructions = String.format("""
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
                                             config.kubernetes()
                                                   .namespace(),
                                             config.cluster()
                                                   .nodes(),
                                             config.kubernetes()
                                                   .serviceType());
            return Result.success(GeneratorOutput.of(
            outputDir, generatedFiles, instructions));
        } catch (IOException e) {
            return GeneratorError.ioError(e.getMessage())
                                 .result();
        }
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
                             config.kubernetes()
                                   .namespace());
    }

    private String generateConfigMap(AetherConfig config) {
        var namespace = config.kubernetes()
                              .namespace();
        var nodes = config.cluster()
                          .nodes();
        var clusterPort = config.cluster()
                                .ports()
                                .cluster();
        // Generate peer list for cluster formation
        var peerList = IntStream.range(0, nodes)
                                .mapToObj(i -> String.format("aether-node-%d.aether-headless.%s.svc.cluster.local:%d",
                                                             i,
                                                             namespace,
                                                             clusterPort))
                                .reduce((a, b) -> a + "," + b)
                                .orElse("");
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
                             config.cluster()
                                   .ports()
                                   .management(),
                             clusterPort,
                             config.node()
                                   .heap(),
                             config.node()
                                   .gc()
                                   .toUpperCase()
                                   .equals("ZGC")
                             ? "ZGC"
                             : "G1GC");
    }

    private String generateStatefulSet(AetherConfig config) {
        var namespace = config.kubernetes()
                              .namespace();
        var nodes = config.cluster()
                          .nodes();
        var resources = config.node()
                              .resources();
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
                             config.cluster()
                                   .ports()
                                   .management(),
                             config.cluster()
                                   .ports()
                                   .cluster(),
                             resources.cpuRequest(),
                             resources.memoryRequest(),
                             resources.cpuLimit(),
                             resources.memoryLimit());
    }

    private String generateHeadlessService(AetherConfig config) {
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
                             config.kubernetes()
                                   .namespace(),
                             config.cluster()
                                   .ports()
                                   .management(),
                             config.cluster()
                                   .ports()
                                   .cluster());
    }

    private String generateService(AetherConfig config) {
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
                             config.kubernetes()
                                   .namespace(),
                             config.kubernetes()
                                   .serviceType(),
                             config.cluster()
                                   .ports()
                                   .management());
    }

    private String generatePdb(AetherConfig config) {
        // Allow at most one node to be unavailable (maintain quorum)
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
                             config.kubernetes()
                                   .namespace(),
                             maxUnavailable);
    }

    private String generateApplyScript(AetherConfig config) {
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
                             config.kubernetes()
                                   .namespace(),
                             config.kubernetes()
                                   .namespace(),
                             config.kubernetes()
                                   .namespace());
    }

    private String generateDeleteScript(AetherConfig config) {
        return String.format("""
            #!/bin/bash
            set -e

            SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

            echo "Deleting Aether cluster from Kubernetes..."

            kubectl delete -f "$SCRIPT_DIR/manifests/" --ignore-not-found

            echo "Cluster deleted."
            """);
    }

    private void makeExecutable(Path path) throws IOException {
        try{
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException e) {}
    }
}
