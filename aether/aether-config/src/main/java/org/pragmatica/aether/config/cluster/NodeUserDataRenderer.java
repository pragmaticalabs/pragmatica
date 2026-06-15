// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.aether.environment.ClusterIdentityEnv;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlWriter;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.regex.Pattern;


/// Single source of truth for the Aether node cloud-init user-data script.
///
/// Renders the bash payload a cloud provider runs on a freshly-minted instance: identity
/// variables, the composed `aether.toml`, the runtime-specific launch (container `docker run`
/// or JVM `java -jar`), and the cluster-identity env allow-list ([ClusterIdentityEnv]). It is
/// shared by BOTH provisioning paths so a CTM auto-heal replacement boots IDENTICALLY to a
/// bootstrap-minted node and the two scripts can never drift:
///  - bootstrap (`aether cluster bootstrap`) via the CLI `UserDataTemplate` wrapper, and
///  - CTM auto-heal (`ClusterTopologyManagerRecord#provisionReplacement`) directly.
///
/// Lives in `aether-config` because that module already depends on `toml` (TomlDocument /
/// TomlWriter) and `environment-integration` ([ClusterIdentityEnv]) and is itself depended on by
/// both `cli` and `aether-deployment` — extracting the renderer here adds no new dependency and
/// introduces no module cycle (`cli` cannot be imported by `aether-deployment`).
///
/// SSH authorized keys are passed as raw `String` lines (not a CLI value object) so this module
/// stays free of CLI types; the CLI wrapper maps its `SshPublicKey::value` before calling here.
public sealed interface NodeUserDataRenderer {
    record unused() implements NodeUserDataRenderer {}

    Pattern PLAIN_SEMVER = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");
    String JAR_REPO_PATH = "pragmaticalabs/pragmatica";

    static String deriveJarTag(String version) {
        if (version == null || version.isBlank()) {
            return "vunknown";
        }

        if (PLAIN_SEMVER.matcher(version).matches()) {
            return "v" + version;
        }

        return "v" + version + "-candidate";
    }

    static String resolveJarUrl(Option<RuntimeProfile> profile, String version) {
        return profile.flatMap(RuntimeProfile::jarUrl)
                      .or("https://github.com/" + JAR_REPO_PATH
                         + "/releases/download/" + deriveJarTag(version)
                         + "/aether-node.jar");
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
                         List<String> sshAuthorizedKeys,
                         List<String> peers) {
        var ports = config.operations().ports();
        var runtimeProfile = resolveRuntimeProfile(config, source, role);
        var isContainer = isContainerRuntime(runtimeProfile);
        var image = runtimeProfile.flatMap(RuntimeProfile::image).or("ghcr.io/pragmaticalabs/aether-node:" + config.cluster().version());
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
        appendSshAuthorizedKeys(sb, sshAuthorizedKeys);
        if (isContainer) {
            appendDockerInstall(sb);
            appendComposedConfig(sb, composedConfig);
            appendContainerRun(sb, clusterName, nodeId, role);
        } else {
            appendJvmInstall(sb,
                             resolveJarUrl(runtimeProfile,
                                           config.cluster().version()));
            appendComposedConfig(sb, composedConfig);
            appendJvmRun(sb,
                         clusterName,
                         role,
                         runtimeProfile.flatMap(RuntimeProfile::jvmArgs).or(""));
        }

        appendReadinessSignal(sb, nodeId, ports.cluster(), ports.management());

        return sb.toString();
    }

    private static void appendSshAuthorizedKeys(StringBuilder sb, List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }

        sb.append("# --- Provision operator SSH access ---\n");
        sb.append("install -d -m 0700 /root/.ssh\n");
        sb.append("id -u aether >/dev/null 2>&1 || useradd -m -s /bin/bash aether || true\n");
        sb.append("install -d -m 0700 -o aether -g aether /home/aether/.ssh\n");
        sb.append("cat >> /root/.ssh/authorized_keys <<'AETHER_SSH_KEYS'\n");
        for (var key : keys) {
            sb.append(key).append('\n');
        }

        sb.append("AETHER_SSH_KEYS\n");
        sb.append("chmod 0600 /root/.ssh/authorized_keys\n");
        sb.append("cat >> /home/aether/.ssh/authorized_keys <<'AETHER_SSH_KEYS'\n");
        for (var key : keys) {
            sb.append(key).append('\n');
        }

        sb.append("AETHER_SSH_KEYS\n");
        sb.append("chown aether:aether /home/aether/.ssh/authorized_keys\n");
        sb.append("chmod 0600 /home/aether/.ssh/authorized_keys\n\n");
    }

    private static Option<RuntimeProfile> resolveRuntimeProfile(ClusterBootstrapConfig config,
                                                                SourceProfile source,
                                                                NodeRole role) {
        var roleTable = Option.option(source.roles().get(role));

        return roleTable.map(RoleSubTable::runtimeRef)
                        .flatMap(ref -> Option.option(config.runtimes().get(ref)));
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
        sb.append("# Cluster: ").append(clusterName).append('\n');
        sb.append("# Node ID: ").append(nodeId).append('\n');
        sb.append("# Role: ").append(role.value()).append("\n\n");
    }

    private static void appendVariables(StringBuilder sb,
                                        String version,
                                        String image,
                                        String nodeId,
                                        String clusterSecret,
                                        int clusterPort,
                                        int managementPort,
                                        String peers) {
        sb.append("AETHER_VERSION=\"").append(version).append("\"\n");
        sb.append("AETHER_IMAGE=\"").append(image).append("\"\n");
        sb.append("AETHER_NODE_ID=\"").append(nodeId).append("\"\n");
        sb.append("AETHER_CLUSTER_SECRET=\"").append(clusterSecret).append("\"\n");
        sb.append("AETHER_CLUSTER_PORT=\"").append(clusterPort).append("\"\n");
        sb.append("AETHER_MANAGEMENT_PORT=\"").append(managementPort).append("\"\n");
        sb.append("AETHER_PEERS=\"").append(peers).append("\"\n\n");
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
        // #287: aether.toml carries cluster_secret. Restrict it to owner-only (0600) rather than
        // world-readable 0644, and chown it to the in-container aether user (uid 1000) so the
        // read-only bind-mount stays readable to the node process without exposing the secret to
        // every local user / on-box process.
        sb.append("chown 1000:1000 /opt/aether/config/aether.toml\n");
        sb.append("chmod 600 /opt/aether/config/aether.toml\n\n");
    }

    private static void appendContainerRun(StringBuilder sb, String clusterName, String nodeId, NodeRole role) {
        sb.append("# --- Pull and run ---\n");
        sb.append("if ! docker image inspect \"${AETHER_IMAGE}\" >/dev/null 2>&1; then\n");
        sb.append("    docker pull \"${AETHER_IMAGE}\"\n");
        sb.append("fi\n");
        sb.append("docker run -d \\\n");
        sb.append("    --name aether-node \\\n");
        sb.append("    --restart no \\\n");
        sb.append("    --network host \\\n");
        sb.append("    -l aether-cluster=").append(clusterName).append(" \\\n");
        sb.append("    -l aether-node-id=").append(nodeId).append(" \\\n");
        sb.append("    -l aether-role=").append(role.value()).append(" \\\n");
        sb.append("    -v /opt/aether/config/aether.toml:/app/aether.toml:ro \\\n");
        sb.append("    -e NODE_ID=\"${AETHER_NODE_ID}\" \\\n");
        sb.append("    -e CLUSTER_PORT=\"${AETHER_CLUSTER_PORT}\" \\\n");
        sb.append("    -e MANAGEMENT_PORT=\"${AETHER_MANAGEMENT_PORT}\" \\\n");
        sb.append("    -e PEERS=\"${AETHER_PEERS}\" \\\n");
        appendEnv(sb, clusterName, role, true);
        sb.append("    \"${AETHER_IMAGE}\"\n\n");
    }

    /// Bake the cluster-identity allow-list ([ClusterIdentityEnv#IDENTITY_VARS]) into the
    /// cloud-init script so a provider-minted replacement inherits the same identity its
    /// compose-fixed siblings receive. AETHER_CLUSTER_NAME is sourced from the threaded
    /// `clusterName` param (the bootstrap-known cluster name); AETHER_ROLE is sourced from the
    /// threaded `role` param (the node's INTENDED role — Wave 2 / W4 of the
    /// cluster-topology-overhaul spec: never inherited from the bootstrapping host's env, which
    /// carries the HOST's role, not this node's); the rest are read from the bootstrapping
    /// host's env and emitted only when non-empty. AETHER_CLUSTER_SECRET is emitted HERE
    /// (single source of truth), not separately, so the allow-list is the only place identity
    /// vars are listed.
    ///
    /// `dockerRun==true` emits `-e VAR="value" \` (a `docker run` line-continuation form);
    /// `false` emits `export VAR="value"` for the JVM-run path.
    private static void appendEnv(StringBuilder sb, String clusterName, NodeRole role, boolean dockerRun) {
        Fn2<Unit, String, String> emit = dockerRun
                                         ? (name, value) -> appendDockerRunEnvLine(sb, name, value)
                                         : (name, value) -> appendExportEnvLine(sb, name, value);

        emitIdentityEnv(emit, clusterName, role, Option.some("${AETHER_CLUSTER_SECRET}"), System::getenv);
    }

    /// Single source of truth for the cluster-identity env allow-list emission, shared by the
    /// cloud-init user-data start ([#appendEnv]) and the finalized-PEERS SSH re-launch
    /// (`BootstrapPhaseDeploy#buildRestartCommand` / `buildJvmRestartCommand`). Without this the
    /// re-launch dropped AETHER_INSECURE_DEV_MODE and the rest of the allow-list that the initial
    /// start set, so the actually-running (re-launched) container lost its cluster identity and
    /// dev-mode posture — the C2 security gate then refused to serve the management API and the
    /// health poll never succeeded.
    ///
    /// `clusterSecretRef` controls whether AETHER_CLUSTER_SECRET is emitted from this pass:
    /// `some(ref)` emits it (cloud-init uses the `${AETHER_CLUSTER_SECRET}` shell ref);
    /// `none()` excludes it so the re-launch can emit the finalized secret explicitly without a
    /// duplicate `-e AETHER_CLUSTER_SECRET`. `envLookup` is injectable so the re-launch can be
    /// unit-tested without mutating the real process env (mirrors `buildCloudSshConfig`).
    ///
    /// AETHER_INSECURE_DEV_MODE is ISOLATED — it rides a standalone block, never the identity
    /// allow-list, so dev-mode can never silently inherit into a production deploy. Every value is
    /// emitted only when present (prod-safe: unset host env → not emitted).
    @Contract
    static void emitIdentityEnv(Fn2<Unit, String, String> emit,
                                String clusterName,
                                NodeRole role,
                                Option<String> clusterSecretRef,
                                Fn1<String, String> envLookup) {
        for (var name : ClusterIdentityEnv.IDENTITY_VARS) {
            resolveEnvValue(name, clusterName, role, clusterSecretRef, envLookup).onPresent(v -> emit.apply(name, v));
        }
        // --- Dev-mode (ISOLATED — never part of IDENTITY_VARS) ---
        // Emit AETHER_INSECURE_DEV_MODE only when present in the (injected) host env so a healed
        // node inherits its siblings' dev-mode posture. Standalone so dev-mode can never silently
        // ride the identity allow-list into a production deploy.
        lookupNonEmpty(ClusterIdentityEnv.INSECURE_DEV_MODE, envLookup).onPresent(v -> emit.apply(ClusterIdentityEnv.INSECURE_DEV_MODE,
                                                                                                  v));
    }

    private static Option<String> resolveEnvValue(String name,
                                                  String clusterName,
                                                  NodeRole role,
                                                  Option<String> clusterSecretRef,
                                                  Fn1<String, String> envLookup) {
        return switch (name) {
            // Sourced from the threaded bootstrap cluster name, not host env.
            case "AETHER_CLUSTER_NAME" -> Option.option(clusterName).filter(s -> !s.isBlank());
            // Sourced from the threaded INTENDED role, not host env (Wave 2 / W4 — the
            // bootstrapping host's AETHER_ROLE is the host's own role, not this node's).
            case "AETHER_ROLE" -> Option.some(role.value());
            // Sourced from the supplied ref (cloud-init: the script's own
            // ${AETHER_CLUSTER_SECRET} shell var); none() for the re-launch which emits the
            // finalized secret explicitly to avoid a duplicate -e AETHER_CLUSTER_SECRET.
            case "AETHER_CLUSTER_SECRET" -> clusterSecretRef;
            default -> lookupNonEmpty(name, envLookup);
        };
    }

    private static Option<String> lookupNonEmpty(String name, Fn1<String, String> envLookup) {
        return Option.option(envLookup.apply(name)).filter(s -> !s.isEmpty());
    }

    private static Unit appendDockerRunEnvLine(StringBuilder sb, String name, String value) {
        sb.append("    -e ").append(name).append("=\"").append(value).append("\" \\\n");

        return Unit.unit();
    }

    private static Unit appendExportEnvLine(StringBuilder sb, String name, String value) {
        sb.append("export ").append(name).append("=\"").append(value).append("\"\n");

        return Unit.unit();
    }

    private static void appendJvmInstall(StringBuilder sb, String jarUrl) {
        sb.append("# --- Install Java and Aether ---\n");
        sb.append("# JVM-mode jar URL: ").append(jarUrl).append('\n');
        sb.append("# Override via [runtime.<name>] jar_url = \"...\" for prereleases or private mirrors.\n");
        sb.append("# Aether-node is built with Java 25 (class file 69); Ubuntu 22.04 ships JDK 11/17/21,\n");
        sb.append("# so we install Temurin 25 from Adoptium's apt repo to match the JAR's bytecode.\n");
        sb.append("if ! command -v java &> /dev/null || ! java -version 2>&1 | grep -q '\"25'; then\n");
        sb.append("    apt-get update -qq\n");
        sb.append("    apt-get install -y -qq wget gnupg ca-certificates apt-transport-https\n");
        sb.append("    mkdir -p /etc/apt/keyrings\n");
        sb.append("    wget -qO /etc/apt/keyrings/adoptium.asc https://packages.adoptium.net/artifactory/api/gpg/key/public\n");
        sb.append("    CODENAME=$(. /etc/os-release && echo \"${VERSION_CODENAME}\")\n");
        sb.append("    echo \"deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb ${CODENAME} main\" > /etc/apt/sources.list.d/adoptium.list\n");
        sb.append("    apt-get update -qq\n");
        sb.append("    apt-get install -y -qq temurin-25-jre\n");
        sb.append("fi\n");
        sb.append("mkdir -p /opt/aether\n");
        sb.append("if [ ! -s /opt/aether/aether-node.jar ]; then\n");
        sb.append("    curl -fsSL -o /opt/aether/aether-node.jar \\\n");
        sb.append("        \"").append(jarUrl).append("\"\n");
        sb.append("fi\n\n");
    }

    private static void appendJvmRun(StringBuilder sb, String clusterName, NodeRole role, String jvmArgs) {
        sb.append("# --- Start Aether ---\n");
        // Export the cluster-identity allow-list (and isolated dev-mode) so the JVM picks
        // up the same identity a containerized sibling receives via -e flags.
        appendEnv(sb, clusterName, role, false);
        sb.append("PEERS_ARG=\"\"\n");
        sb.append("if [ -n \"${AETHER_PEERS}\" ]; then PEERS_ARG=\"--peers=${AETHER_PEERS}\"; fi\n");
        sb.append("AETHER_CLUSTER_SECRET=\"${AETHER_CLUSTER_SECRET}\" java ");
        if (!jvmArgs.isEmpty()) {
            sb.append(jvmArgs).append(' ');
        }

        sb.append("-jar /opt/aether/aether-node.jar --config=/opt/aether/config/aether.toml ");
        sb.append("--node-id=\"${AETHER_NODE_ID}\" ");
        sb.append("--port=\"${AETHER_CLUSTER_PORT}\" ");
        sb.append("--management-port=\"${AETHER_MANAGEMENT_PORT}\" ");
        sb.append("${PEERS_ARG} &\n\n");
    }

    private static void appendReadinessSignal(StringBuilder sb, String nodeId, int clusterPort, int managementPort) {
        sb.append("# --- Signal readiness ---\n");
        sb.append("echo \"Aether node ").append(nodeId).append(" starting on ports: cluster=").append(clusterPort).append(", mgmt=").append(managementPort).append("\"\n");
    }
}
