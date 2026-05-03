// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RuntimeProfile;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlWriter;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.regex.Pattern;


sealed interface UserDataTemplate {
    record unused() implements UserDataTemplate{}

    Pattern PLAIN_SEMVER = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");

    String JAR_REPO_PATH = "pragmaticalabs/pragmatica";

    static String deriveJarTag(String version) {
        if (version == null || version.isBlank()) {return "vunknown";}
        if (PLAIN_SEMVER.matcher(version).matches()) {return "v" + version;}
        return "v" + version + "-candidate";
    }

    static String resolveJarUrl(Option<RuntimeProfile> profile, String version) {
        return profile.flatMap(RuntimeProfile::jarUrl)
                              .or("https://github.com/" + JAR_REPO_PATH + "/releases/download/" + deriveJarTag(version) + "/aether-node.jar");
    }

    static String render(ClusterBootstrapConfig config,
                         SourceProfile source,
                         NodeRole role,
                         String nodeId,
                         int nodeIndex,
                         String clusterSecret,
                         String clusterName,
                         TomlDocument composedConfig) {
        return render(config,
                      source,
                      role,
                      nodeId,
                      nodeIndex,
                      clusterSecret,
                      clusterName,
                      composedConfig,
                      List.of(),
                      List.of());
    }

    static String render(ClusterBootstrapConfig config,
                         SourceProfile source,
                         NodeRole role,
                         String nodeId,
                         int nodeIndex,
                         String clusterSecret,
                         String clusterName,
                         TomlDocument composedConfig,
                         List<SshPublicKey> sshPublicKeys) {
        return render(config,
                      source,
                      role,
                      nodeId,
                      nodeIndex,
                      clusterSecret,
                      clusterName,
                      composedConfig,
                      sshPublicKeys,
                      List.of());
    }

    static String render(ClusterBootstrapConfig config,
                         SourceProfile source,
                         NodeRole role,
                         String nodeId,
                         int nodeIndex,
                         String clusterSecret,
                         String clusterName,
                         TomlDocument composedConfig,
                         List<SshPublicKey> sshPublicKeys,
                         List<String> peers) {
        var ports = config.operations().ports();
        var runtimeProfile = resolveRuntimeProfile(config, source, role);
        var isContainer = isContainerRuntime(runtimeProfile);
        var image = runtimeProfile.flatMap(RuntimeProfile::image)
                                          .or("ghcr.io/pragmaticalabs/aether-node:" + config.cluster().version());
        var peersValue = String.join(",", peers);
        var sb = new StringBuilder();
        appendHeader(sb, clusterName, nodeId, role);
        appendVariables(sb,
                        config.cluster().version(),
                        image,
                        nodeId,
                        clusterSecret,
                        ports.cluster(),
                        ports.management(),
                        peersValue);
        appendSshAuthorizedKeys(sb, sshPublicKeys);
        if (isContainer) {
            appendDockerInstall(sb);
            appendComposedConfig(sb, composedConfig);
            appendContainerRun(sb, clusterName, nodeId);
        } else {
            appendJvmInstall(sb,
                             resolveJarUrl(runtimeProfile,
                                           config.cluster().version()));
            appendComposedConfig(sb, composedConfig);
            appendJvmRun(sb,
                         runtimeProfile.flatMap(RuntimeProfile::jvmArgs).or(""));
        }
        appendReadinessSignal(sb, nodeId, ports.cluster(), ports.management());
        return sb.toString();
    }

    private static void appendSshAuthorizedKeys(StringBuilder sb, List<SshPublicKey> keys) {
        if (keys.isEmpty()) {return;}
        sb.append("# --- Provision operator SSH access ---\n");
        sb.append("install -d -m 0700 /root/.ssh\n");
        sb.append("id -u aether >/dev/null 2>&1 || useradd -m -s /bin/bash aether || true\n");
        sb.append("install -d -m 0700 -o aether -g aether /home/aether/.ssh\n");
        sb.append("cat >> /root/.ssh/authorized_keys <<'AETHER_SSH_KEYS'\n");
        for (var key : keys) {sb.append(key.value()).append('\n');}
        sb.append("AETHER_SSH_KEYS\n");
        sb.append("chmod 0600 /root/.ssh/authorized_keys\n");
        sb.append("cat >> /home/aether/.ssh/authorized_keys <<'AETHER_SSH_KEYS'\n");
        for (var key : keys) {sb.append(key.value()).append('\n');}
        sb.append("AETHER_SSH_KEYS\n");
        sb.append("chown aether:aether /home/aether/.ssh/authorized_keys\n");
        sb.append("chmod 0600 /home/aether/.ssh/authorized_keys\n\n");
    }

    private static Option<RuntimeProfile> resolveRuntimeProfile(ClusterBootstrapConfig config,
                                                                SourceProfile source,
                                                                NodeRole role) {
        var roleTable = Option.option(source.roles().get(role));
        return roleTable.map(rt -> rt.runtimeRef()).flatMap(ref -> Option.option(config.runtimes().get(ref)));
    }

    private static boolean isContainerRuntime(Option<RuntimeProfile> profile) {
        return profile.map(p -> p.type() == RuntimeType.CONTAINER || p.type() == RuntimeType.DOCKER || p.type() == RuntimeType.MANAGED_CONTAINER)
                          .or(true);
    }

    private static void appendHeader(StringBuilder sb, String clusterName, String nodeId, NodeRole role) {
        sb.append("#!/bin/bash\n");
        sb.append("set -euo pipefail\n\n");
        sb.append("# --- Aether Node Cloud-Init ---\n");
        sb.append("# Generated by: aether cluster bootstrap\n");
        sb.append("# Cluster: ").append(clusterName)
                 .append('\n');
        sb.append("# Node ID: ").append(nodeId)
                 .append('\n');
        sb.append("# Role: ").append(role.value())
                 .append("\n\n");
    }

    private static void appendVariables(StringBuilder sb,
                                        String version,
                                        String image,
                                        String nodeId,
                                        String clusterSecret,
                                        int clusterPort,
                                        int managementPort,
                                        String peers) {
        sb.append("AETHER_VERSION=\"").append(version)
                 .append("\"\n");
        sb.append("AETHER_IMAGE=\"").append(image)
                 .append("\"\n");
        sb.append("AETHER_NODE_ID=\"").append(nodeId)
                 .append("\"\n");
        sb.append("AETHER_CLUSTER_SECRET=\"").append(clusterSecret)
                 .append("\"\n");
        sb.append("AETHER_CLUSTER_PORT=\"").append(clusterPort)
                 .append("\"\n");
        sb.append("AETHER_MANAGEMENT_PORT=\"").append(managementPort)
                 .append("\"\n");
        sb.append("AETHER_PEERS=\"").append(peers)
                 .append("\"\n\n");
    }

    private static void appendDockerInstall(StringBuilder sb) {
        sb.append("# --- Install Docker (if not present) ---\n");
        sb.append("if ! command -v docker &> /dev/null; then\n");
        sb.append("    curl -fsSL https://get.docker.com | sh\n");
        sb.append("fi\n\n");
    }

    private static void appendComposedConfig(StringBuilder sb, TomlDocument composedConfig) {
        sb.append("# --- Write Aether config (composed: defaults + source-type + operator + CLI overlay) ---\n");
        sb.append("mkdir -p /opt/aether/config\n");
        sb.append("cat > /opt/aether/config/aether.toml <<'AETHER_CONFIG'\n");
        sb.append(TomlWriter.toToml(composedConfig));
        sb.append("AETHER_CONFIG\n");
        sb.append("chmod 644 /opt/aether/config/aether.toml\n\n");
    }

    private static void appendContainerRun(StringBuilder sb, String clusterName, String nodeId) {
        sb.append("# --- Pull and run ---\n");
        sb.append("docker pull \"${AETHER_IMAGE}\"\n");
        sb.append("docker run -d \\\n");
        sb.append("    --name aether-node \\\n");
        sb.append("    --restart unless-stopped \\\n");
        sb.append("    --network host \\\n");
        sb.append("    -l aether-cluster=").append(clusterName)
                 .append(" \\\n");
        sb.append("    -l aether-node-id=").append(nodeId)
                 .append(" \\\n");
        sb.append("    -l aether-role=core \\\n");
        sb.append("    -v /opt/aether/config/aether.toml:/app/aether.toml:ro \\\n");
        sb.append("    -e NODE_ID=\"${AETHER_NODE_ID}\" \\\n");
        sb.append("    -e CLUSTER_PORT=\"${AETHER_CLUSTER_PORT}\" \\\n");
        sb.append("    -e MANAGEMENT_PORT=\"${AETHER_MANAGEMENT_PORT}\" \\\n");
        sb.append("    -e PEERS=\"${AETHER_PEERS}\" \\\n");
        sb.append("    -e AETHER_CLUSTER_SECRET=\"${AETHER_CLUSTER_SECRET}\" \\\n");
        sb.append("    \"${AETHER_IMAGE}\"\n\n");
    }

    private static void appendJvmInstall(StringBuilder sb, String jarUrl) {
        sb.append("# --- Install Java and Aether ---\n");
        sb.append("# JVM-mode jar URL: ").append(jarUrl)
                 .append('\n');
        sb.append("# Override via [runtime.<name>] jar_url = \"...\" for prereleases or private mirrors.\n");
        sb.append("if ! command -v java &> /dev/null; then\n");
        sb.append("    apt-get update -qq && apt-get install -y -qq openjdk-21-jre-headless\n");
        sb.append("fi\n");
        sb.append("mkdir -p /opt/aether\n");
        sb.append("curl -fsSL -o /opt/aether/aether-node.jar \\\n");
        sb.append("    \"").append(jarUrl)
                 .append("\"\n\n");
    }

    private static void appendJvmRun(StringBuilder sb, String jvmArgs) {
        sb.append("# --- Start Aether ---\n");
        sb.append("PEERS_ARG=\"\"\n");
        sb.append("if [ -n \"${AETHER_PEERS}\" ]; then PEERS_ARG=\"--peers=${AETHER_PEERS}\"; fi\n");
        sb.append("AETHER_CLUSTER_SECRET=\"${AETHER_CLUSTER_SECRET}\" java ");
        if (!jvmArgs.isEmpty()) {sb.append(jvmArgs).append(' ');}
        sb.append("-jar /opt/aether/aether-node.jar --config=/opt/aether/config/aether.toml ");
        sb.append("--node-id=\"${AETHER_NODE_ID}\" ");
        sb.append("--port=\"${AETHER_CLUSTER_PORT}\" ");
        sb.append("--management-port=\"${AETHER_MANAGEMENT_PORT}\" ");
        sb.append("${PEERS_ARG} &\n\n");
    }

    private static void appendReadinessSignal(StringBuilder sb, String nodeId, int clusterPort, int managementPort) {
        sb.append("# --- Signal readiness ---\n");
        sb.append("echo \"Aether node ").append(nodeId)
                 .append(" starting on ports: cluster=")
                 .append(clusterPort)
                 .append(", mgmt=")
                 .append(managementPort)
                 .append("\"\n");
    }
}
