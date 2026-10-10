// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.NodeUserDataRenderer;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.SshConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.CloudProviderSupport;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.aether.cli.cluster.ApplyResult.applyResult;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-PAT-01"})
public final class WaveExecutor {
    private WaveExecutor() {}

    public static Result<ApplyResult> execute(DiffPlan plan,
                                              ClusterBootstrapConfig stored,
                                              ClusterBootstrapConfig desired) {
        var managementPort = desired.operations().ports().management();
        var created = new CreatedNodes();

        return executeAdditions(plan.additions(),
                                desired,
                                created).flatMap(added -> executeModifications(plan.modifications(),
                                                                               stored,
                                                                               desired,
                                                                               created).flatMap(modified -> executeRemovals(plan.removals(),
                                                                                                                            stored,
                                                                                                                            managementPort).map(removed -> applyResult(plan,
                                                                                                                                                                       added,
                                                                                                                                                                       removed,
                                                                                                                                                                       modified))))
                               .mapError(cause -> WaveNodeProvisioning.PartiallyProvisioned.partiallyProvisioned(created.snapshot(),
                                                                                                                 cause));
    }

    private static Result<Integer> executeAdditions(List<DiffAction> additions,
                                                    ClusterBootstrapConfig desired,
                                                    CreatedNodes created) {
        var totalAdded = 0;

        for (var action : additions) {
            var result = executeAddition(action, desired, created);

            if (result.isFailure()) {
                return result;
            }

            totalAdded += result.or(0);
        }

        return success(totalAdded);
    }

    private static Result<Integer> executeAddition(DiffAction action,
                                                   ClusterBootstrapConfig desired,
                                                   CreatedNodes created) {
        return switch (action) {
            case DiffAction.AddSource a -> logNewSource(a.sourceName());
            case DiffAction.AddRole a -> provisionRole(a.sourceName(), a.role(), a.count(), desired, created);
            case DiffAction.ScaleUp a -> provisionScaleUp(a.sourceName(), a.role(), a.from(), a.to(), desired, created);
            default -> success(0);
        };
    }

    private static Result<Integer> logNewSource(SourceName sourceName) {
        logAction("+", sourceName + ": new source added (roles provisioned individually)");

        return success(0);
    }

    private static Result<Integer> provisionRole(SourceName sourceName,
                                                 NodeRole role,
                                                 int count,
                                                 ClusterBootstrapConfig desired,
                                                 CreatedNodes created) {
        return lookupSource(sourceName,
                            desired.sources()).flatMap(source -> dispatchProvision(sourceName,
                                                                                   source,
                                                                                   role,
                                                                                   count,
                                                                                   desired,
                                                                                   created))
                           .map(nodes -> logAndCount("+",
                                                     sourceName
                                                    + "." + role.value()
                                                    + ": provisioned " + nodes.size()
                                                    + " node(s)",
                                                     nodes.size()));
    }

    private static Result<Integer> provisionScaleUp(SourceName sourceName,
                                                    NodeRole role,
                                                    int from,
                                                    int to,
                                                    ClusterBootstrapConfig desired,
                                                    CreatedNodes created) {
        var delta = to - from;

        return lookupSource(sourceName,
                            desired.sources()).flatMap(source -> rejectSshScaleUp(source, sourceName))
                           .flatMap(source -> dispatchProvision(sourceName, source, role, delta, desired, created))
                           .map(nodes -> logAndCount("~",
                                                     sourceName
                                                    + "." + role.value()
                                                    + ": scaled up by " + delta
                                                    + " node(s)",
                                                     nodes.size()));
    }

    private static Result<SourceProfile> rejectSshScaleUp(SourceProfile source, SourceName sourceName) {
        return source.type() == SourceType.SSH
               ? new ApplyError.SshScaleNotSupported(sourceName).result()
               : success(source);
    }

    private static Result<List<ProvisionedNode>> dispatchProvision(SourceName sourceName,
                                                                   SourceProfile source,
                                                                   NodeRole role,
                                                                   int count,
                                                                   ClusterBootstrapConfig desired,
                                                                   CreatedNodes created) {
        return provisionBySourceType(sourceName, source, role, count, desired).onSuccess(nodes -> created.record(nodes,
                                                                                                                 source));
    }

    static Result<List<ProvisionedNode>> provisionBySourceType(SourceName sourceName,
                                                               SourceProfile source,
                                                               NodeRole role,
                                                               int count,
                                                               ClusterBootstrapConfig desired) {
        return switch (source.type()) {
            case CLOUD -> resolveCloudAndProvision(source, role, count, desired);
            case DOCKER -> resolveDockerAndProvision(sourceName, role, count, source, desired);
            case FORGE -> forgeProvisionPlaceholder(sourceName, role, count);
            case SSH -> sshProvisionPlaceholder(sourceName, role, source);
        };
    }

    /// #1695 / #1027: a CLOUD node is provisioned through [WaveNodeProvisioning] — rendered user-data carrying the
    /// cluster identity and live peers, a freshly minted unique node id, and a zone placement only when the source
    /// names one — so it boots able to join.
    private static Result<List<ProvisionedNode>> resolveCloudAndProvision(SourceProfile source,
                                                                          NodeRole role,
                                                                          int count,
                                                                          ClusterBootstrapConfig desired) {
        var clusterName = desired.cluster().name();

        return WaveNodeProvisioning.resolveJoinInputs(clusterName).flatMap(inputs -> ProviderResolver.resolveCloudCompute(source).flatMap(compute -> provisionCloudNodes(compute,
                                                                                                                                                                         desired,
                                                                                                                                                                         source,
                                                                                                                                                                         role,
                                                                                                                                                                         count,
                                                                                                                                                                         inputs)));
    }

    /// The CLOUD wave provisioning itself, with the provider and the join inputs already resolved. Package-private
    /// so a test drives the real composition — minted ids, rendered user-data, optional zone — against a fake
    /// provider.
    static Result<List<ProvisionedNode>> provisionCloudNodes(ComputeProvider compute,
                                                             ClusterBootstrapConfig desired,
                                                             SourceProfile source,
                                                             NodeRole role,
                                                             int count,
                                                             WaveNodeProvisioning.JoinInputs inputs) {
        return WaveNodeProvisioning.provisionCloud(compute,
                                                   desired,
                                                   source,
                                                   role,
                                                   count,
                                                   inputs,
                                                   WaveNodeProvisioning.mintedIds(desired.cluster().name()));
    }

    private static Result<List<ProvisionedNode>> resolveDockerAndProvision(SourceName sourceName,
                                                                           NodeRole role,
                                                                           int count,
                                                                           SourceProfile source,
                                                                           ClusterBootstrapConfig desired) {
        var image = NodeUserDataRenderer.pinnedImageFor(desired, source, role);

        return ProviderResolver.resolveDockerCompute(source, image).flatMap(compute -> provisionViaCompute(compute,
                                                                                                           sourceName,
                                                                                                           role,
                                                                                                           count,
                                                                                                           source,
                                                                                                           desired.cluster()
                                                                                                                  .name()));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<List<ProvisionedNode>> provisionViaCompute(ComputeProvider compute,
                                                                     SourceName sourceName,
                                                                     NodeRole role,
                                                                     int count,
                                                                     SourceProfile source,
                                                                     ClusterName clusterName) {
        var instanceType = option(source.roles().get(role)).flatMap(rt -> rt.instanceType()).or("default");
        var zone = source.zone().or("default");
        // #442 v2b — carry the real cluster name in the group tags so `CloudProviderSupport.toContext`
        // seeds `ProvisionContext.clusterName()`, and the provider stamps `aether-cluster=<real>` on
        // the VM. Mirrors `BootstrapPhaseProvision.provisionRoleGroup`. Without this the tags were
        // empty and the label fell back to the provider config / env / "unknown", breaking the
        // harness's label-scoped cloud enumeration on scale/reprovision-provisioned nodes.
        var group = NodeGroupConfig.nodeGroupConfig(sourceName,
                                                    role.value(),
                                                    count,
                                                    instanceType,
                                                    zone,
                                                    provisionTags(clusterName, sourceName, role));

        return CloudProviderSupport.provisionVia(compute, group).await();
    }

    /// #442 v2b — the native metadata tags a provisioned VM must carry so the harness's label-scoped
    /// cloud enumeration can find and reap it. `aether-cluster` is the load-bearing one: it seeds
    /// `ProvisionContext.clusterName()` via `CloudProviderSupport.toContext`, which the provider
    /// stamps onto the VM. Package-private so the wiring test asserts it directly.
    static Map<String, String> provisionTags(ClusterName clusterName, SourceName sourceName, NodeRole role) {
        return Map.of("aether-cluster",
                      clusterName.value(),
                      "aether-source",
                      sourceName.value(),
                      "aether-role",
                      role.value());
    }

    private static Result<List<ProvisionedNode>> forgeProvisionPlaceholder(SourceName sourceName,
                                                                           NodeRole role,
                                                                           int count) {
        logAction("+",
                  sourceName
                 + "." + role.value()
                 + "/" + SourceType.FORGE.value()
                 + ": " + count
                 + " in-process node(s) will be started by Forge");
        var nodes = new ArrayList<ProvisionedNode>();

        for (int i = 0; i < count; i++) {
            nodes.add(ProvisionedNode.provisionedNode(sourceName.value() + "-" + role.value() + "-" + i,
                                                      "forge",
                                                      "127.0.0.1"));
        }

        return success(List.copyOf(nodes));
    }

    private static Result<List<ProvisionedNode>> sshProvisionPlaceholder(SourceName sourceName,
                                                                         NodeRole role,
                                                                         SourceProfile source) {
        var hosts = option(source.roles().get(role)).flatMap(rt -> rt.hosts()).or(List.of());

        logAction("+",
                  sourceName + "." + role.value() + "/ssh: " + hosts.size() + " pre-existing host(s) registered");
        var nodes = new ArrayList<ProvisionedNode>();

        for (int i = 0; i < hosts.size(); i++) {
            nodes.add(ProvisionedNode.provisionedNode(sourceName.value() + "-" + role.value() + "-" + i,
                                                      "ssh",
                                                      hosts.get(i)));
        }

        return success(List.copyOf(nodes));
    }

    static final long DRAIN_TIMEOUT_MS = 120_000;
    static final long READY_TIMEOUT_MS = 300_000;

    private static Result<Integer> executeModifications(List<DiffAction> modifications,
                                                        ClusterBootstrapConfig stored,
                                                        ClusterBootstrapConfig desired,
                                                        CreatedNodes created) {
        var totalModified = 0;

        for (var action : modifications) {
            var result = executeModification(action, stored, desired, created);

            if (result.isFailure()) {
                return result;
            }

            totalModified += result.or(0);
        }

        return success(totalModified);
    }

    private static Result<Integer> executeModification(DiffAction action,
                                                       ClusterBootstrapConfig stored,
                                                       ClusterBootstrapConfig desired,
                                                       CreatedNodes created) {
        return switch (action) {
            case DiffAction.RuntimeChange a -> executeRuntimeChange(a, stored, desired, created);
            case DiffAction.SourceFieldChange a -> executeSourceFieldChange(a, stored, desired, created);
            case DiffAction.ClusterLevelChange a -> logClusterLevelChange(a);
            default -> logUnknownModification(action);
        };
    }

    /// #1543 F: refused. Rolling a role onto a new runtime here stopped a node and started another in its place by deterministic
    /// id (the SSH branch re-registered the SAME id on the same host), so a node came back under an id the cluster had retired. See
    /// [RuntimeChangeNotSupported] for the supported ways to change what a node runs.
    private static Result<Integer> executeRuntimeChange(DiffAction.RuntimeChange change,
                                                        ClusterBootstrapConfig stored,
                                                        ClusterBootstrapConfig desired,
                                                        CreatedNodes created) {
        return new RuntimeChangeNotSupported(change.sourceName(),
                                             change.role(),
                                             change.fromRuntime(),
                                             change.toRuntime()).result();
    }

    /// There is no supported way today to change the runtime profile of a source role on a running cluster: replacements are
    /// provisioned from the committed cluster config, and `cluster apply` does not change runtime-profile content. What IS supported
    /// is moving nodes onto a new VERSION by replacement.
    public record RuntimeChangeNotSupported(SourceName sourceName, NodeRole role, String from, String to) implements Cause {
        @Override
        public String message() {
            return "Changing the runtime of " + sourceName.value()
                 + "." + role.value()
                 + " (" + from
                 + " -> " + to
                 + ") by apply is not supported: "
                 + "it would stop a node and start another in its place under the same id, and a node id never returns. "
                 + "Nodes are moved onto new software by REPLACEMENT under a fresh id: `aether cluster upgrade --version X.Y.Z --wait` replaces every node "
                 + "one at a time, and `POST /api/v1/nodes/replace/{id}` replaces one. Replacements are provisioned from the committed cluster config, "
                 + "and `aether cluster apply` does not change runtime-profile content, so there is no supported way today to change a source role's "
                 + "runtime profile on a running cluster.";
        }
    }

    private static Result<Unit> sshStopNode(String host, SourceProfile source) {
        var sshConfig = buildSshConfig(source);

        logAction("~", "  SSH stop on " + host);

        return RemoteCommandRunner.ssh(host, "docker stop aether-node || true", sshConfig).mapToUnit();
    }

    private static Result<List<ProvisionedNode>> waitForNewNodes(List<ProvisionedNode> nodes, int managementPort) {
        for (var node : nodes) {
            var result = ClusterHttpClient.waitForNodeReady(node.publicIp(), managementPort, READY_TIMEOUT_MS);

            if (result.isFailure()) {
                return result.map(_ -> List.of());
            }
        }

        return success(nodes);
    }

    private static Result<Integer> executeSourceFieldChange(DiffAction.SourceFieldChange change,
                                                            ClusterBootstrapConfig stored,
                                                            ClusterBootstrapConfig desired,
                                                            CreatedNodes created) {
        logAction("~",
                  change.sourceName() + ": " + change.field() + " changed (replace-before-retire)");

        return lookupSource(change.sourceName(), stored.sources()).flatMap(oldSource -> lookupSource(change.sourceName(),
                                                                                                     desired.sources()).flatMap(newSource -> replaceBeforeRetire(change.sourceName(),
                                                                                                                                                                 oldSource,
                                                                                                                                                                 newSource,
                                                                                                                                                                 desired,
                                                                                                                                                                 created)));
    }

    private static Result<Integer> replaceBeforeRetire(SourceName sourceName,
                                                       SourceProfile oldSource,
                                                       SourceProfile newSource,
                                                       ClusterBootstrapConfig desired,
                                                       CreatedNodes created) {
        var managementPort = desired.operations().ports().management();
        var totalAffected = 0;

        for (var entry : oldSource.roles().entrySet()) {
            var role = entry.getKey();
            var count = entry.getValue().count().or(0);

            if (count <= 0) {
                continue;
            }

            var result = replaceBeforeRetireRole(sourceName, role, count, newSource, desired, managementPort, created);

            if (result.isFailure()) {
                return result;
            }

            totalAffected += result.or(0);
        }

        return success(totalAffected);
    }

    private static Result<Integer> replaceBeforeRetireRole(SourceName sourceName,
                                                           NodeRole role,
                                                           int count,
                                                           SourceProfile newSource,
                                                           ClusterBootstrapConfig desired,
                                                           int managementPort,
                                                           CreatedNodes created) {
        logAction("~", "  provisioning " + count + " new " + role.value() + " node(s)...");

        return dispatchProvision(sourceName, newSource, role, count, desired, created).flatMap(nodes -> waitForNewNodes(nodes,
                                                                                                                        managementPort))
                                .flatMap(_ -> drainOldNodes(sourceName, role, count, desired))
                                .map(count2 -> logAndCount("~",
                                                           "  " + sourceName
                                                          + "." + role.value()
                                                          + ": replaced " + count
                                                          + " node(s)",
                                                           count));
    }

    static Result<Unit> drainOldNodes(SourceName sourceName, NodeRole role, int count, ClusterBootstrapConfig desired) {
        var managementPort = desired.operations().ports().management();

        for (int i = 0; i < count; i++) {
            var nodeId = sourceName.value() + "-" + role.value() + "-old-" + i;
            var address = resolveNodeAddress(nodeId);
            Result<Unit> result = ClusterHttpClient.drainNodeAndAwait(address, managementPort, nodeId, DRAIN_TIMEOUT_MS);

            if (result.isFailure()) {
                return result;
            }
        }

        return Result.unitResult();
    }

    private static Result<Integer> logClusterLevelChange(DiffAction.ClusterLevelChange change) {
        logAction("~",
                  "cluster." + change.field() + ": " + change.from() + " -> " + change.to() + " (cluster-wide update)");

        return success(1);
    }

    private static Result<Integer> logUnknownModification(DiffAction action) {
        logAction("~", action.description());

        return success(0);
    }

    private static String resolveNodeAddress(String nodeId) {
        return nodeId;
    }

    private static SshConfig buildSshConfig(SourceProfile source) {
        var user = source.user().or("root");
        var keyPath = source.key().or("~/.ssh/id_rsa");
        var port = source.sshPort().or(22);

        return SshConfig.sshConfig(user, keyPath, port);
    }

    private static Result<Integer> executeRemovals(List<DiffAction> removals,
                                                   ClusterBootstrapConfig stored,
                                                   int managementPort) {
        var totalRemoved = 0;

        for (var action : removals) {
            var result = executeRemoval(action, stored, managementPort);

            if (result.isFailure()) {
                return result;
            }

            totalRemoved += result.or(0);
        }

        return success(totalRemoved);
    }

    private static Result<Integer> executeRemoval(DiffAction action,
                                                  ClusterBootstrapConfig stored,
                                                  int managementPort) {
        return switch (action) {
            case DiffAction.RemoveSource a -> destroyEntireSource(a.sourceName(), stored, managementPort);
            case DiffAction.RemoveRole a -> destroyRole(a.sourceName(), a.role(), a.count(), stored, managementPort);
            case DiffAction.ScaleDown a -> destroyScaleDown(a.sourceName(),
                                                            a.role(),
                                                            a.from(),
                                                            a.to(),
                                                            stored,
                                                            managementPort);
            default -> success(0);
        };
    }

    private static Result<Integer> destroyEntireSource(SourceName sourceName,
                                                       ClusterBootstrapConfig stored,
                                                       int managementPort) {
        return lookupSource(sourceName, stored.sources()).flatMap(source -> destroyAllRoles(sourceName,
                                                                                            source,
                                                                                            stored.cluster().name(),
                                                                                            managementPort));
    }

    private static Result<Integer> destroyAllRoles(SourceName sourceName,
                                                   SourceProfile source,
                                                   ClusterName cluster,
                                                   int managementPort) {
        var totalDestroyed = 0;

        for (var entry : source.roles().entrySet()) {
            var count = entry.getValue().count().or(0);

            if (count <= 0) {
                continue;
            }

            var result = dispatchDestroy(sourceName, source, cluster, entry.getKey(), count, managementPort);

            if (result.isFailure()) {
                return result.map(_ -> 0);
            }

            totalDestroyed += count;
        }

        logAction("-", sourceName + ": destroyed " + totalDestroyed + " node(s) across all roles");

        return success(totalDestroyed);
    }

    private static Result<Integer> destroyRole(SourceName sourceName,
                                               NodeRole role,
                                               int count,
                                               ClusterBootstrapConfig stored,
                                               int managementPort) {
        return lookupSource(sourceName,
                            stored.sources()).flatMap(source -> dispatchDestroy(sourceName,
                                                                                source,
                                                                                stored.cluster().name(),
                                                                                role,
                                                                                count,
                                                                                managementPort))
                           .map(_ -> logAndCount("-",
                                                 sourceName + "." + role.value() + ": destroyed " + count + " node(s)",
                                                 count));
    }

    private static Result<Integer> destroyScaleDown(SourceName sourceName,
                                                    NodeRole role,
                                                    int from,
                                                    int to,
                                                    ClusterBootstrapConfig stored,
                                                    int managementPort) {
        var excess = from - to;

        return lookupSource(sourceName,
                            stored.sources()).flatMap(source -> dispatchDestroy(sourceName,
                                                                                source,
                                                                                stored.cluster().name(),
                                                                                role,
                                                                                excess,
                                                                                managementPort))
                           .map(_ -> logAndCount("-",
                                                 sourceName
                                                + "." + role.value()
                                                + ": scaled down by " + excess
                                                + " node(s) (LIFO)",
                                                 excess));
    }

    private static Result<Unit> dispatchDestroy(SourceName sourceName,
                                                SourceProfile source,
                                                ClusterName cluster,
                                                NodeRole role,
                                                int count,
                                                int managementPort) {
        return switch (source.type()) {
            case CLOUD -> resolveCloudAndDestroy(source, sourceName, role, count);
            case DOCKER -> resolveDockerAndDestroy(cluster, sourceName, role, count);
            case FORGE -> forgeDestroyPlaceholder(sourceName, role, count);
            case SSH -> drainAndStopSshNodes(sourceName, role, count, source, managementPort);
        };
    }

    private static Result<Unit> resolveCloudAndDestroy(SourceProfile source,
                                                       SourceName sourceName,
                                                       NodeRole role,
                                                       int count) {
        return ProviderResolver.resolveCloudCompute(source).flatMap(compute -> destroyViaCompute(compute,
                                                                                                 sourceName,
                                                                                                 role,
                                                                                                 count));
    }

    private static Result<Unit> resolveDockerAndDestroy(ClusterName cluster,
                                                        SourceName sourceName,
                                                        NodeRole role,
                                                        int count) {
        return ProviderResolver.resolveDockerComputeWithoutBackup(cluster).flatMap(compute -> destroyViaCompute(compute,
                                                                                                                sourceName,
                                                                                                                role,
                                                                                                                count));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Unit> destroyViaCompute(ComputeProvider compute,
                                                  SourceName sourceName,
                                                  NodeRole role,
                                                  int count) {
        var nodeIds = buildNodeIds(sourceName, role, count);

        return CloudProviderSupport.destroyVia(compute, nodeIds).await();
    }

    private static List<String> buildNodeIds(SourceName sourceName, NodeRole role, int count) {
        var ids = new ArrayList<String>(count);

        for (int i = count - 1; i >= 0; i--) {
            ids.add(sourceName.value() + "-" + role.value() + "-" + i);
        }

        return List.copyOf(ids);
    }

    private static Result<Unit> forgeDestroyPlaceholder(SourceName sourceName, NodeRole role, int count) {
        logAction("-",
                  sourceName + "." + role.value() + "/forge: " + count + " in-process node(s) will be stopped by Forge");

        return Result.unitResult();
    }

    private static Result<Unit> drainAndStopSshNodes(SourceName sourceName,
                                                     NodeRole role,
                                                     int count,
                                                     SourceProfile source,
                                                     int managementPort) {
        logAction("-", sourceName + "." + role.value() + "/ssh: draining " + count + " node(s) (hosts remain)");
        var hosts = option(source.roles().get(role)).flatMap(RoleSubTable::hosts).or(List.of());
        var stopCount = Math.min(count, hosts.size());

        for (int i = 0; i < stopCount; i++) {
            var host = hosts.get(hosts.size() - 1 - i);
            var nodeId = sourceName.value() + "-" + role.value() + "-" + (hosts.size() - 1 - i);
            var result = ClusterHttpClient.drainNodeAndAwait(host, managementPort, nodeId, DRAIN_TIMEOUT_MS).flatMap(_ -> sshStopNode(host,
                                                                                                                                      source));

            if (result.isFailure()) {
                return result;
            }
        }

        return Result.unitResult();
    }

    /// Every cloud VM one apply creates, recorded as it is created, so an apply that fails later — in any step — names
    /// them all (#1695, CodeRabbit on #1716). The apply records nothing else about them: `ApplyState` holds no created
    /// resources and `--rollback` does not see them.
    ///
    /// Only CLOUD sources are recorded, because only there did a provider create a billed VM. An SSH "node" is one of
    /// the operator's own pre-existing hosts, so telling them to delete it would be harmful advice. A FORGE node is an
    /// in-process placeholder. A DOCKER node is a local container, not billed and not reported.
    static final class CreatedNodes {
        private final List<WaveNodeProvisioning.CreatedNode> nodes = new ArrayList<>();

        Unit record(List<ProvisionedNode> provisioned, SourceProfile source) {
            if (source.type() == SourceType.CLOUD) {
                nodes.addAll(WaveNodeProvisioning.created(provisioned, source));
            }

            return Unit.unit();
        }

        List<WaveNodeProvisioning.CreatedNode> snapshot() {
            return List.copyOf(nodes);
        }
    }

    private static Result<SourceProfile> lookupSource(SourceName sourceName, Map<String, SourceProfile> sources) {
        return option(sources.get(sourceName.value())).toResult(new ApplyError.SourceNotFound(sourceName));
    }

    private static int logAndCount(String symbol, String message, int count) {
        logAction(symbol, message);

        return count;
    }

    @Contract
    private static void logAction(String symbol, String message) {
        System.out.printf("  [%s] %s%n", symbol, message);
    }

    public sealed interface ApplyError extends Cause {
        record SourceNotFound(SourceName sourceName) implements ApplyError {
            @Override
            public String message() {
                return "Source '" + sourceName + "' not found in configuration";
            }
        }

        record SshScaleNotSupported(SourceName sourceName) implements ApplyError {
            @Override
            public String message() {
                return "SSH source '" + sourceName
                     + "' cannot scale up: hosts are fixed. Add hosts to the config and re-apply.";
            }
        }

        record ProvisionFailed(SourceName sourceName, String detail) implements ApplyError {
            @Override
            public String message() {
                return "Provisioning failed for source '" + sourceName + "': " + detail;
            }
        }

        record DestroyFailed(SourceName sourceName, String detail) implements ApplyError {
            @Override
            public String message() {
                return "Destroy failed for source '" + sourceName + "': " + detail;
            }
        }
    }
}
