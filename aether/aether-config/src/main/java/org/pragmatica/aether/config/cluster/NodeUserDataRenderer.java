// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.aether.environment.ClusterSecretSource;
import org.pragmatica.aether.environment.ClusterIdentityEnv;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlWriter;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;


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
    /// #1021 — the JVM-mode launch surface. Shared with `BootstrapPhaseDeploy`, whose finalized-PEERS
    /// start rewrites [#JVM_ENV_FILE_PATH] and starts [#JVM_UNIT_NAME] rather than pattern-matching
    /// the process with `pkill -f`.
    String JVM_UNIT_NAME = "aether-node.service";
    String JVM_UNIT_PATH = "/etc/systemd/system/aether-node.service";
    String JVM_ENV_DIR = "/etc/aether";
    String JVM_ENV_FILE_PATH = "/etc/aether/node.env";
    /// #828 — where the cluster secret lives on the node's host, as a file only the node's user can read. A container node
    /// bind-mounts [#CONTAINER_SECRET_HOST_FILE] read-only at [#CONTAINER_SECRET_MOUNT] and is pointed at it by
    /// `AETHER_CLUSTER_SECRET_FILE`; a JVM node reads [#JVM_SECRET_FILE] through the same variable. The value never appears in a
    /// process argv or in a container's environment (`docker inspect`). The file is KEPT for the life of the node: the bind
    /// mount re-resolves the host path on every container start, so removing it breaks a restart (loudly, with `--mount`).
    String CONTAINER_SECRET_HOST_FILE = "/opt/aether/config/cluster-secret";
    String CONTAINER_SECRET_MOUNT = "/run/secrets/aether-cluster-secret";
    String JVM_SECRET_FILE = JVM_ENV_DIR + "/cluster-secret";
    String JVM_LAUNCHER_PATH = "/opt/aether/run-node.sh";

    static String deriveJarTag(String version) {
        if (!Verify.Is.present(version)) {
            return "vunknown";
        }

        if (PLAIN_SEMVER.matcher(version).matches()) {
            return "v" + version;
        }

        return "v" + version + "-candidate";
    }

    /// Placeholder a runtime profile's `image` / `jar_url` may carry to follow `[cluster] version` (#1543 part C).
    String VERSION_PLACEHOLDER = "{version}";

    static String withVersion(String pin, String clusterVersion) {
        return pin.replace(VERSION_PLACEHOLDER, clusterVersion);
    }

    /// The profile's `image` with [#VERSION_PLACEHOLDER] replaced by the cluster version.
    static Option<String> pinnedImage(RuntimeProfile profile, String clusterVersion) {
        return profile.image()
                      .map(image -> withVersion(image, clusterVersion));
    }

    static String resolveJarUrl(Option<RuntimeProfile> profile, String version) {
        return profile.flatMap(p -> p.jarUrl()
                                     .map(url -> withVersion(url, version)))
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
                         ClusterName clusterName,
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
                         ClusterName clusterName,
                         TomlDocument composedConfig,
                         List<String> sshAuthorizedKeys,
                         List<String> peers) {
        return render(config,
                      source,
                      role,
                      nodeId,
                      nodeIndex,
                      clusterSecret,
                      clusterName,
                      composedConfig,
                      sshAuthorizedKeys,
                      peers,
                      true);
    }

    /// #1543 — `startNode == false` renders an INSTALL-ONLY script: docker/JVM, image or jar, composed
    /// config, env file and unit are all laid down, but the node process is not started. The CLI
    /// bootstrap uses it when cores span several sources: PEERS are not final at create time, so the
    /// finalized-PEERS SSH push performs the node's one and only start instead of re-launching a node
    /// that already ran under the same id.
    static String render(ClusterBootstrapConfig config,
                         SourceProfile source,
                         NodeRole role,
                         String nodeId,
                         int nodeIndex,
                         String clusterSecret,
                         ClusterName clusterName,
                         TomlDocument composedConfig,
                         List<String> sshAuthorizedKeys,
                         List<String> peers,
                         boolean startNode) {
        var ports = config.operations().ports();
        var runtimeProfile = resolveRuntimeProfile(config, source, role);
        var isContainer = isContainerRuntime(runtimeProfile);
        var image = runtimeProfile.flatMap(p -> pinnedImage(p,
                                                            config.cluster().version()))
                                  .or("ghcr.io/pragmaticalabs/aether-node:" + config.cluster().version());
        var peersValue = String.join(",", peers);
        var sb = new StringBuilder();

        appendHeader(sb, clusterName, nodeId, role);
        appendVariables(sb,
                        config.cluster().version(),
                        image,
                        nodeId,
                        ports.cluster(),
                        ports.management(),
                        peersValue);
        appendClusterSecretFile(sb, clusterSecret, isContainer);
        appendSshAuthorizedKeys(sb, sshAuthorizedKeys);
        appendAdvertiseHostResolution(sb);
        var backupPath = backupPath(composedConfig);

        if (isContainer) {
            appendDockerInstall(sb);
            appendComposedConfig(sb, composedConfig);
            appendBackupDirectory(sb, backupPath, true);
            if (startNode) {
                appendContainerRun(sb, clusterName, nodeId, role, source, backupPath);
            } else {
                appendContainerPullOnly(sb);
            }
        } else {
            appendJvmInstall(sb,
                             resolveJarUrl(runtimeProfile,
                                           config.cluster().version()));
            appendComposedConfig(sb, composedConfig);
            appendBackupDirectory(sb, backupPath, false);
            appendJvmRun(sb,
                         clusterName,
                         role,
                         source,
                         runtimeProfile.flatMap(RuntimeProfile::jvmArgs).or(""),
                         startNode);
        }

        appendReadinessSignal(sb, nodeId, ports.cluster(), ports.management(), startNode);

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

    /// Resolve this VM's ROUTABLE IPv4 on the HOST (outside any container) and expose it as the
    /// `AETHER_ADVERTISE_HOST` shell var, so a CTM auto-heal replacement advertises an IP its peers
    /// can route to — not the container/VM hostname, which is unresolvable across hosts and gets the
    /// node suspected and killed by SWIM (the silent multi-minute flap [SelfAddressResolver] guards).
    ///
    /// The leader cannot know the replacement VM's IP at provision time, but the VM trivially can:
    /// `ip route get 1.1.1.1` reports the source IP the kernel would use to reach an off-link
    /// destination, which on a public-IP VM is the public IP. Provider-agnostic — no metadata
    /// endpoint is hardcoded.
    ///
    /// ROBUST by construction: `2>/dev/null` + `|| true` so a missing `ip`/`sed` or a routing
    /// failure never aborts the `set -euo pipefail` boot; an empty result leaves the var unset, and
    /// the node's own resolution chain (SWIM WhoAmI reflection + loud hostname fallback) takes over.
    /// Each runtime then passes the var through ONLY when non-empty (a runtime test, since the value
    /// is resolved on the box, not at render time).
    ///
    /// Emitted on BOTH provisioning paths since the renderer is shared, but harmless for bootstrap:
    /// a bootstrap node carries itself in its 3-part PEERS, so [SelfAddressResolver] short-circuits
    /// (self PRESENT → peers returned unchanged) and never consults the override.
    private static void appendAdvertiseHostResolution(StringBuilder sb) {
        sb.append("# --- Resolve routable advertise host (on the VM, outside any container) ---\n");
        sb.append("# CTM replacements must advertise the VM's routable IP, not its hostname (which\n");
        sb.append("# peers cannot resolve). 'ip route get' is provider-agnostic; never fail the boot.\n");
        sb.append("AETHER_ADVERTISE_HOST=\"$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.*src \\([0-9.]*\\).*/\\1/p' | head -n1)\" || true\n\n");
    }

    private static Option<RuntimeProfile> resolveRuntimeProfile(ClusterBootstrapConfig config,
                                                                SourceProfile source,
                                                                NodeRole role) {
        var roleTable = Option.option(source.roles().get(role));

        return roleTable.map(RoleSubTable::runtimeRef)
                        .flatMap(ref -> Option.option(config.runtimes().get(ref)));
    }

    private static boolean isContainerRuntime(Option<RuntimeProfile> profile) {
        return profile.map(RuntimeProfile::isContainer)
                      .or(true);
    }

    private static void appendHeader(StringBuilder sb, ClusterName clusterName, String nodeId, NodeRole role) {
        sb.append("#!/bin/bash\n");
        sb.append("set -euo pipefail\n\n");
        sb.append("# --- Aether Node Cloud-Init ---\n");
        sb.append("# Generated by: aether cluster bootstrap\n");
        sb.append("# Cluster: ").append(clusterName.value()).append('\n');
        sb.append("# Node ID: ").append(nodeId).append('\n');
        sb.append("# Role: ").append(role.value()).append("\n\n");
    }

    private static void appendVariables(StringBuilder sb,
                                        String version,
                                        String image,
                                        String nodeId,
                                        int clusterPort,
                                        int managementPort,
                                        String peers) {
        sb.append("AETHER_VERSION=\"").append(version).append("\"\n");
        sb.append("AETHER_IMAGE=\"").append(image).append("\"\n");
        sb.append("AETHER_NODE_ID=\"").append(nodeId).append("\"\n");
        sb.append("AETHER_CLUSTER_PORT=\"").append(clusterPort).append("\"\n");
        sb.append("AETHER_MANAGEMENT_PORT=\"").append(managementPort).append("\"\n");
        sb.append("AETHER_PEERS=\"").append(peers).append("\"\n\n");
    }

    /// #828 — the user-data is one script, so the secret is written to its file by shell builtins (`printf` is not an exec, so
    /// the value is on no process argv) under `umask 077`, then given to the node's user: uid 1000 for the container, root for
    /// the JVM unit. STATED LIMIT: this user-data itself still contains the secret in clear text. It is readable through the
    /// cloud provider's metadata/API by the account holder, and from `/var/lib/cloud` and the metadata service by processes
    /// on the VM. No file mode on the node can change that; the only fix is a secret store the VM fetches from.
    private static void appendClusterSecretFile(StringBuilder sb, String clusterSecret, boolean container) {
        var path = container
                   ? CONTAINER_SECRET_HOST_FILE
                   : JVM_SECRET_FILE;

        sb.append("# --- Stage the cluster secret as a file (never an env var or a command line) ---\n");
        sb.append("(umask 077; install -d -m 0755 ")
          .append(container
                  ? "/opt/aether/config"
                  : JVM_ENV_DIR)
          .append(" && printf '%s' ")
          .append(shellQuote(clusterSecret))
          .append(" > ")
          .append(path)
          .append(")\n");
        sb.append(container
                  ? "chown 1000:1000 " + path + "\n"
                  : "").append("chmod 0400 ").append(path).append("\n\n");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void appendDockerInstall(StringBuilder sb) {
        sb.append("# --- Install Docker (if not present) ---\n");
        sb.append("if ! command -v docker &> /dev/null; then\n");
        sb.append("    curl -fsSL https://get.docker.com | sh\n");
        sb.append("fi\n\n");
    }

    /// Host directory that holds the `[backup]` repository `<path>/kv-backup` of a container node (#1968).
    String BACKUP_HOST_DIRECTORY = "/opt/aether/backups";

    /// The `[backup] path` of the composed config when the backup is enabled and has a path, else empty. A node whose composed
    /// config carries `[backup]` (the operator's `node_config`, the same for every seed and replacement of the source) needs
    /// that path to exist and be writable on the host it boots on, which the TOML alone does not give it.
    static Option<String> backupPath(TomlDocument composedConfig) {
        var enabled = composedConfig.getString("backup", "enabled")
                                    .map(value -> Boolean.parseBoolean(value.strip()))
                                    .or(false);

        return enabled
               ? composedConfig.getString("backup", "path")
                               .filter(path -> !path.isBlank())
               : Option.none();
    }

    /// Creates the backup directory before the node starts. A container node gets it as a host directory owned by the
    /// in-container `aether` user (uid 1000), bind-mounted at the configured path by [#appendContainerRun], so the repository
    /// survives the container; a JVM node runs on the host as the unit's user and only needs the directory to exist.
    private static void appendBackupDirectory(StringBuilder sb, Option<String> backupPath, boolean container) {
        backupPath.onPresent(path -> {
            sb.append("# --- Backup repository directory ([backup] path, #1968) ---\n");
            sb.append(container
                      ? "install -d -m 0750 -o 1000 -g 1000 " + BACKUP_HOST_DIRECTORY + "\n\n"
                      : "install -d -m 0750 " + path + "\n\n");
        });
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

    private static void appendContainerPullOnly(StringBuilder sb) {
        sb.append("# --- Pull only: the CLI's finalized-PEERS push starts the node (#1543) ---\n");
        sb.append("if ! docker image inspect \"${AETHER_IMAGE}\" >/dev/null 2>&1; then\n");
        sb.append("    docker pull \"${AETHER_IMAGE}\"\n");
        sb.append("fi\n\n");
    }

    private static void appendContainerRun(StringBuilder sb,
                                           ClusterName clusterName,
                                           String nodeId,
                                           NodeRole role,
                                           SourceProfile source,
                                           Option<String> backupPath) {
        sb.append("# --- Pull and run ---\n");
        sb.append("if ! docker image inspect \"${AETHER_IMAGE}\" >/dev/null 2>&1; then\n");
        sb.append("    docker pull \"${AETHER_IMAGE}\"\n");
        sb.append("fi\n");
        // Pass the resolved routable IP into the container ONLY when non-empty (runtime test —
        // resolved on the host above). Assembled into a shell var here and expanded unquoted into
        // the docker run command so an unset host IP contributes no -e flag (the node then falls
        // back to its own SWIM-reflection chain rather than advertising an unroutable hostname).
        sb.append("ADVERTISE_ENV=\"\"\n");
        sb.append("if [ -n \"${AETHER_ADVERTISE_HOST}\" ]; then ADVERTISE_ENV=\"-e AETHER_ADVERTISE_HOST=${AETHER_ADVERTISE_HOST}\"; fi\n");
        sb.append("docker run -d \\\n");
        sb.append("    --name aether-node \\\n");
        sb.append("    --restart no \\\n");
        sb.append("    --network host \\\n");
        sb.append("    -l aether-cluster=").append(clusterName.value()).append(" \\\n");
        sb.append("    -l aether-node-id=").append(nodeId).append(" \\\n");
        sb.append("    -l aether-role=").append(role.value()).append(" \\\n");
        sb.append("    -v /opt/aether/config/aether.toml:/app/aether.toml:ro \\\n");
        sb.append("    --mount type=bind,src=")
          .append(CONTAINER_SECRET_HOST_FILE)
          .append(",dst=")
          .append(CONTAINER_SECRET_MOUNT)
          .append(",readonly \\\n");
        backupPath.onPresent(path -> sb.append("    -v ")
                                       .append(BACKUP_HOST_DIRECTORY)
                                       .append(':')
                                       .append(path)
                                       .append(" \\\n"));
        sb.append("    -e NODE_ID=\"${AETHER_NODE_ID}\" \\\n");
        sb.append("    -e CLUSTER_PORT=\"${AETHER_CLUSTER_PORT}\" \\\n");
        sb.append("    -e MANAGEMENT_PORT=\"${AETHER_MANAGEMENT_PORT}\" \\\n");
        sb.append("    -e PEERS=\"${AETHER_PEERS}\" \\\n");
        sb.append("    ${ADVERTISE_ENV} \\\n");
        appendEnv(sb, clusterName, role, source, true);
        sb.append("    \"${AETHER_IMAGE}\"\n\n");
    }

    /// Bake the cluster-identity allow-list ([ClusterIdentityEnv#IDENTITY_VARS]) into the
    /// cloud-init script so a provider-minted replacement inherits the same identity its
    /// compose-fixed siblings receive. AETHER_CLUSTER_NAME is sourced from the threaded
    /// `clusterName` param (the bootstrap-known cluster name); AETHER_ROLE is sourced from the
    /// threaded `role` param (the node's INTENDED role — Wave 2 / W4 of the
    /// cluster-topology-overhaul spec: never inherited from the bootstrapping host's env, which
    /// carries the HOST's role, not this node's); AETHER_SOURCE and AETHER_ZONE are the node's own,
    /// from `source` (#1650); the rest are read from the bootstrapping host's env and emitted only
    /// when non-empty. AETHER_CLUSTER_SECRET is emitted HERE
    /// (single source of truth), not separately, so the allow-list is the only place identity
    /// vars are listed.
    ///
    /// `dockerRun==true` emits `-e VAR="value" \` (a `docker run` line-continuation form);
    /// `false` emits `export VAR="value"` for the JVM-run path.
    private static void appendEnv(StringBuilder sb,
                                  ClusterName clusterName,
                                  NodeRole role,
                                  SourceProfile source,
                                  boolean dockerRun) {
        Fn2<Unit, String, String> emit = dockerRun
                                         ? (name, value) -> appendDockerRunEnvLine(sb, name, value)
                                         : (name, value) -> appendExportEnvLine(sb, name, value);

        emitIdentityEnv(emit,
                        clusterName,
                        role,
                        source.name(),
                        source.knownZone(),
                        Option.some(CONTAINER_SECRET_MOUNT),
                        System::getenv);
    }

    /// Single source of truth for the cluster-identity env allow-list emission, shared by the
    /// cloud-init user-data start ([#appendEnv]) and the finalized-PEERS SSH start
    /// (`BootstrapPhaseDeploy#buildStartCommand` / `buildJvmStartCommand`). Without this the
    /// old re-launch dropped AETHER_INSECURE_DEV_MODE and the rest of the allow-list that the initial
    /// start set, so the actually-running (relaunched) container lost its cluster identity and
    /// dev-mode posture — the C2 security gate then refused to serve the management API and the
    /// health poll never succeeded.
    ///
    /// `clusterSecretRef` controls whether AETHER_CLUSTER_SECRET is emitted from this pass:
    /// `some(ref)` emits it (cloud-init uses the `${AETHER_CLUSTER_SECRET}` shell ref);
    /// `none()` excludes it so the SSH start can emit the finalized secret explicitly without a
    /// duplicate `-e AETHER_CLUSTER_SECRET`. `envLookup` is injectable so the SSH start can be
    /// unit-tested without mutating the real process env (mirrors `buildCloudSshConfig`).
    ///
    /// AETHER_INSECURE_DEV_MODE is ISOLATED — it rides a standalone block, never the identity
    /// allow-list, so dev-mode can never silently inherit into a production deploy. Every value is
    /// emitted only when present (prod-safe: unset host env → not emitted).
    @Contract
    static void emitIdentityEnv(Fn2<Unit, String, String> emit,
                                ClusterName clusterName,
                                NodeRole role,
                                SourceName source,
                                Option<String> zone,
                                Option<String> clusterSecretRef,
                                Fn1<String, String> envLookup) {
        for (var name : ClusterIdentityEnv.IDENTITY_VARS) {
            resolveEnvValue(name, clusterName, role, source, zone, clusterSecretRef, envLookup).onPresent(v -> emit.apply(emittedName(name),
                                                                                                                          v));
        }
        // --- Dev-mode (ISOLATED — never part of IDENTITY_VARS) ---
        // Emit AETHER_INSECURE_DEV_MODE only when present in the (injected) host env so a healed
        // node inherits its siblings' dev-mode posture. Standalone so dev-mode can never silently
        // ride the identity allow-list into a production deploy.
        lookupNonEmpty(ClusterIdentityEnv.INSECURE_DEV_MODE, envLookup).onPresent(v -> emit.apply(ClusterIdentityEnv.INSECURE_DEV_MODE,
                                                                                                  v));
    }

    private static Option<String> resolveEnvValue(String name,
                                                  ClusterName clusterName,
                                                  NodeRole role,
                                                  SourceName source,
                                                  Option<String> zone,
                                                  Option<String> clusterSecretRef,
                                                  Fn1<String, String> envLookup) {
        return switch (name) {
            // Sourced from the threaded bootstrap cluster name, not host env.
            case "AETHER_CLUSTER_NAME" -> Option.some(clusterName.value());
            // Sourced from the threaded INTENDED role, not host env (Wave 2 / W4 — the
            // bootstrapping host's AETHER_ROLE is the host's own role, not this node's).
            case "AETHER_ROLE" -> Option.some(role.value());
            // #1650: the node's OWN source and zone, never the rendering host's. A node learns its source
            // only from this variable (`Main` → the SWIM `source` label → its community), and workers are
            // rendered on the LEADER, whose env names the leader's source or nothing -- so every
            // core-provisioned worker came up as `default`. The zone is stamped only when the node's
            // source names exactly one zone ([SourceProfile#knownZone]); otherwise it is left absent rather than
            // guessed or inherited.
            case "AETHER_SOURCE" -> Option.some(source.value());
            case "AETHER_ZONE" -> zone;
            // Sourced from the supplied ref (cloud-init: the script's own
            // ${AETHER_CLUSTER_SECRET} shell var); none() for the SSH start which emits the
            // finalized secret explicitly to avoid a duplicate -e AETHER_CLUSTER_SECRET.
            case "AETHER_CLUSTER_SECRET" -> clusterSecretRef;
            default -> lookupNonEmpty(name, envLookup);
        };
    }

    /// The cluster secret is emitted as the `_FILE` variable (#828): the reference supplied is the secret file's PATH.
    private static String emittedName(String identityVar) {
        return "AETHER_CLUSTER_SECRET".equals(identityVar)
               ? ClusterSecretSource.SECRET_FILE_ENV
               : identityVar;
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
        sb.append("# [backup] shells out to git (#2007): a host without it cannot back up or restore, and the node refuses to boot.\n");
        sb.append("if ! command -v git &> /dev/null; then\n");
        sb.append("    apt-get update -qq\n");
        sb.append("    apt-get install -y -qq --no-install-recommends git\n");
        sb.append("fi\n");
        sb.append("mkdir -p /opt/aether\n");
        sb.append("if [ ! -s /opt/aether/aether-node.jar ]; then\n");
        sb.append("    curl -fsSL -o /opt/aether/aether-node.jar \\\n");
        sb.append("        \"").append(jarUrl).append("\"\n");
        sb.append("fi\n\n");
    }

    /// #1021 — a JVM-mode node is started BY SYSTEMD, not as a bare backgrounded `java -jar … &`.
    ///
    /// The old launch left `systemctl list-units | grep -i aether` empty: no unit, no
    /// `systemctl status`, no `journalctl -u`, and a crashed node was a silent absence. Nothing about
    /// WHO RECOVERS the node changes — [SystemdUnitTemplate#RESTART_POLICY] is `Restart=no` and CTM
    /// auto-heal remains the recovery layer. What changes is that a dead node now leaves a queryable
    /// local trace: the unit sits in `failed` (or `inactive`) with its exit status and its last output
    /// in the journal, instead of nothing at all.
    ///
    /// Three files, in the order the unit needs them:
    ///  - [#JVM_ENV_FILE_PATH] — the identity allow-list plus the values resolved on the box. Written
    ///    BEFORE the unit is started, `0600`, because it carries AETHER_CLUSTER_SECRET (#287's reason
    ///    for the `aether.toml` mode, same secret).
    ///  - [#JVM_LAUNCHER_PATH] — the launcher, carrying the empty-PEERS conditional verbatim from the
    ///    old launch, so the invocation is unchanged and only its supervisor is new. It `exec`s the
    ///    JVM, so systemd's MAINPID is the JVM rather than a wrapper shell.
    ///  - the unit itself, from [SystemdUnitTemplate].
    private static void appendJvmRun(StringBuilder sb,
                                     ClusterName clusterName,
                                     NodeRole role,
                                     SourceProfile source,
                                     String jvmArgs,
                                     boolean startNode) {
        appendJvmEnvFile(sb, clusterName, role, source);
        appendJvmLauncher(sb, jvmArgs);
        appendJvmUnit(sb, startNode);
    }

    /// The env file systemd reads. Two heredocs on purpose:
    ///  - the LITERAL block (quoted delimiter) carries values already resolved at render time — the
    ///    identity allow-list read from the bootstrapping host's env. A quoted delimiter means a value
    ///    containing `$` is written as typed instead of being expanded by the boot shell.
    ///  - the EXPANDING block (unquoted delimiter) carries the values that only exist on the box: the
    ///    shell vars set at the top of this script, and the advertise host resolved by
    ///    [#appendAdvertiseHostResolution].
    ///
    /// AETHER_CLUSTER_SECRET rides the expanding block via the `none()` ref to [#emitIdentityEnv] —
    /// the same seam `BootstrapPhaseDeploy`'s start uses to avoid emitting the secret twice — so
    /// it is written once, from the script's own shell var.
    ///
    /// AETHER_ADVERTISE_HOST is written only when non-empty, preserving the old launch's runtime test:
    /// an unset value must leave the var ABSENT so the node's own SWIM-reflection chain takes over,
    /// rather than present-and-empty, which would advertise nothing.
    private static void appendJvmEnvFile(StringBuilder sb,
                                         ClusterName clusterName,
                                         NodeRole role,
                                         SourceProfile source) {
        sb.append("# --- Write the node env file systemd reads (0600: carries the cluster secret) ---\n");
        sb.append("install -d -m 0755 ").append(JVM_ENV_DIR).append('\n');
        sb.append("touch ").append(JVM_ENV_FILE_PATH).append('\n');
        sb.append("chmod 600 ").append(JVM_ENV_FILE_PATH).append('\n');
        sb.append("cat > ").append(JVM_ENV_FILE_PATH).append(" <<'AETHER_ENV_LITERAL'\n");
        emitIdentityEnv((name, value) -> appendEnvFileLine(sb, name, value),
                        clusterName,
                        role,
                        source.name(),
                        source.knownZone(),
                        Option.none(),
                        System::getenv);
        sb.append("AETHER_ENV_LITERAL\n");
        sb.append("cat >> ").append(JVM_ENV_FILE_PATH).append(" <<AETHER_ENV_RUNTIME\n");
        sb.append("AETHER_CLUSTER_SECRET_FILE=").append(JVM_SECRET_FILE).append('\n');
        sb.append("AETHER_NODE_ID=${AETHER_NODE_ID}\n");
        sb.append("AETHER_CLUSTER_PORT=${AETHER_CLUSTER_PORT}\n");
        sb.append("AETHER_MANAGEMENT_PORT=${AETHER_MANAGEMENT_PORT}\n");
        sb.append("AETHER_PEERS=${AETHER_PEERS}\n");
        sb.append("AETHER_ENV_RUNTIME\n");
        sb.append("if [ -n \"${AETHER_ADVERTISE_HOST}\" ]; then echo \"AETHER_ADVERTISE_HOST=${AETHER_ADVERTISE_HOST}\" >> ")
          .append(JVM_ENV_FILE_PATH)
          .append("; fi\n\n");
    }

    /// The launcher. `PEERS_ARG` reproduces the old launch's conditional exactly: an empty peer list —
    /// the normal state of a bootstrap node before `BootstrapPhaseDeploy` finalizes PEERS — must omit
    /// `--peers` ENTIRELY rather than pass it empty. Keeping that in a script rather than in ExecStart
    /// avoids resting the first boot of every cloud node on systemd's empty-variable word-splitting.
    ///
    /// `exec` so the JVM REPLACES the shell: with `Type=simple` systemd tracks the first process it
    /// spawns, and without `exec` that would be the wrapper, leaving `systemctl status` reporting on a
    /// shell rather than on the node.
    private static void appendJvmLauncher(StringBuilder sb, String jvmArgs) {
        sb.append("# --- Write the node launcher (systemd ExecStart) ---\n");
        sb.append("cat > ").append(JVM_LAUNCHER_PATH).append(" <<'AETHER_LAUNCHER'\n");
        sb.append("#!/bin/bash\n");
        sb.append("set -euo pipefail\n");
        sb.append("PEERS_ARG=\"\"\n");
        sb.append("if [ -n \"${AETHER_PEERS:-}\" ]; then PEERS_ARG=\"--peers=${AETHER_PEERS}\"; fi\n");
        sb.append("exec java -XX:+ExitOnOutOfMemoryError ");
        if (!jvmArgs.isEmpty()) {
            sb.append(jvmArgs).append(' ');
        }

        sb.append("-jar /opt/aether/aether-node.jar --config=/opt/aether/config/aether.toml ");
        sb.append("--node-id=\"${AETHER_NODE_ID}\" ");
        sb.append("--port=\"${AETHER_CLUSTER_PORT}\" ");
        sb.append("--management-port=\"${AETHER_MANAGEMENT_PORT}\" ");
        sb.append("${PEERS_ARG}\n");
        sb.append("AETHER_LAUNCHER\n");
        sb.append("chmod 0755 ").append(JVM_LAUNCHER_PATH).append("\n\n");
    }

    /// Install and START the unit — never `enable` it. The node id is fixed per VM, and membership is
    /// terminal-removal: once a node has been gone long enough to be removed, its id can never be admitted
    /// again. An enabled unit (linked into `multi-user.target`) relaunched a rebooted VM under that removed
    /// id, so the rejoin was refused and the VM kept billing without ever joining — the same-id hazard
    /// behind #1467 and #1543. A rebooted host is replaced by CTM auto-heal under a FRESH id, and a host-level
    /// unit must not start on boot while the id is fixed (`aether/docs/operators/deployment-recovery.md` §2.3 and
    /// §4.4). The container path already runs
    /// `docker run --restart no` for the same reason.
    private static void appendJvmUnit(StringBuilder sb, boolean startNode) {
        sb.append(startNode
                  ? "# --- Install and start the aether-node systemd unit ---\n"
                  : "# --- Install the aether-node systemd unit (the CLI push starts it, #1543) ---\n");
        sb.append("# Restart=no is deliberate: Aether uses terminal-removal membership and CTM auto-heal\n");
        sb.append("# owns recovery. The unit exists so a dead node is VISIBLE (systemctl status /\n");
        sb.append("# journalctl -u aether-node), not so it comes back. See docs/operators/deployment-recovery.md.\n");
        sb.append("cat > ").append(JVM_UNIT_PATH).append(" <<'AETHER_UNIT'\n");
        sb.append(SystemdUnitTemplate.generateDefault());
        sb.append("AETHER_UNIT\n");
        sb.append("systemctl daemon-reload\n");
        if (startNode) {
            sb.append("systemctl start ").append(JVM_UNIT_NAME).append("\n");
        }

        sb.append("\n");
    }

    private static Unit appendEnvFileLine(StringBuilder sb, String name, String value) {
        sb.append(name).append('=').append(value).append('\n');

        return Unit.unit();
    }

    private static void appendReadinessSignal(StringBuilder sb,
                                              String nodeId,
                                              int clusterPort,
                                              int managementPort,
                                              boolean startNode) {
        sb.append("# --- Signal readiness ---\n");
        sb.append("echo \"Aether node ")
          .append(nodeId)
          .append(startNode
                  ? " starting on ports: cluster="
                  : " installed, awaiting start on ports: cluster=")
          .append(clusterPort)
          .append(", mgmt=")
          .append(managementPort)
          .append("\"\n");
    }
}
