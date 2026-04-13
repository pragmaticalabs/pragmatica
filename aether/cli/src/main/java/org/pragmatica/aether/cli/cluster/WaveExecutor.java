package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.SshConfig;
import org.pragmatica.aether.environment.CloudProviderSupport;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.pragmatica.aether.cli.cluster.ApplyResult.applyResult;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Executes diff plan waves sequentially: additions, modifications, removals. S9.3
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-PAT-01"}) public final class WaveExecutor {
    private WaveExecutor() {}

    public static Result<ApplyResult> execute(DiffPlan plan,
                                              ClusterBootstrapConfig stored,
                                              ClusterBootstrapConfig desired) {
        var managementPort = desired.operations().ports()
                                               .management();
        return executeAdditions(plan.additions(), desired).flatMap(added -> executeModifications(plan.modifications(),
                                                                                                 stored,
                                                                                                 desired).flatMap(modified -> executeRemovals(plan.removals(),
                                                                                                                                              stored,
                                                                                                                                              managementPort).map(removed -> applyResult(plan,
                                                                                                                                                                                         added,
                                                                                                                                                                                         removed,
                                                                                                                                                                                         modified))));
    }

    private static Result<Integer> executeAdditions(List<DiffAction> additions, ClusterBootstrapConfig desired) {
        var totalAdded = 0;
        for (var action : additions) {
            var result = executeAddition(action, desired);
            if (result.isFailure()) {return result;}
            totalAdded += result.or(0);
        }
        return success(totalAdded);
    }

    private static Result<Integer> executeAddition(DiffAction action, ClusterBootstrapConfig desired) {
        return switch (action){
            case DiffAction.AddSource a -> logNewSource(a.sourceName());
            case DiffAction.AddRole a -> provisionRole(a.sourceName(), a.role(), a.count(), desired);
            case DiffAction.ScaleUp a -> provisionScaleUp(a.sourceName(), a.role(), a.from(), a.to(), desired);
            default -> success(0);
        };
    }

    private static Result<Integer> logNewSource(String sourceName) {
        logAction("+", sourceName + ": new source added (roles provisioned individually)");
        return success(0);
    }

    private static Result<Integer> provisionRole(String sourceName,
                                                 NodeRole role,
                                                 int count,
                                                 ClusterBootstrapConfig desired) {
        return lookupSource(sourceName,
                            desired.sources()).flatMap(source -> dispatchProvision(sourceName, source, role, count))
                           .map(nodes -> logAndCount("+",
                                                     sourceName + "." + role.value() + ": provisioned " + nodes.size() + " node(s)",
                                                     nodes.size()));
    }

    private static Result<Integer> provisionScaleUp(String sourceName,
                                                    NodeRole role,
                                                    int from,
                                                    int to,
                                                    ClusterBootstrapConfig desired) {
        var delta = to - from;
        return lookupSource(sourceName,
                            desired.sources()).flatMap(source -> rejectSshScaleUp(source, sourceName))
                           .flatMap(source -> dispatchProvision(sourceName, source, role, delta))
                           .map(nodes -> logAndCount("~",
                                                     sourceName + "." + role.value() + ": scaled up by " + delta + " node(s)",
                                                     nodes.size()));
    }

    private static Result<SourceProfile> rejectSshScaleUp(SourceProfile source, String sourceName) {
        return source.type() == SourceType.SSH
              ? new ApplyError.SshScaleNotSupported(sourceName).result()
              : success(source);
    }

    private static Result<List<ProvisionedNode>> dispatchProvision(String sourceName,
                                                                   SourceProfile source,
                                                                   NodeRole role,
                                                                   int count) {
        return switch (source.type()){
            case CLOUD -> resolveCloudAndProvision(sourceName, source, role, count);
            case DOCKER -> resolveDockerAndProvision(sourceName, role, count, source);
            case FORGE -> forgeProvisionPlaceholder(sourceName, role, count);
            case SSH -> sshProvisionPlaceholder(sourceName, role, source);
        };
    }

    private static Result<List<ProvisionedNode>> resolveCloudAndProvision(String sourceName,
                                                                          SourceProfile source,
                                                                          NodeRole role,
                                                                          int count) {
        return ProviderResolver.resolveCloudCompute(source)
                                                   .flatMap(compute -> provisionViaCompute(compute,
                                                                                           sourceName,
                                                                                           role,
                                                                                           count,
                                                                                           source));
    }

    private static Result<List<ProvisionedNode>> resolveDockerAndProvision(String sourceName,
                                                                           NodeRole role,
                                                                           int count,
                                                                           SourceProfile source) {
        return ProviderResolver.resolveDockerCompute()
                                                    .flatMap(compute -> provisionViaCompute(compute,
                                                                                            sourceName,
                                                                                            role,
                                                                                            count,
                                                                                            source));
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<List<ProvisionedNode>> provisionViaCompute(ComputeProvider compute,
                                                                                                     String sourceName,
                                                                                                     NodeRole role,
                                                                                                     int count,
                                                                                                     SourceProfile source) {
        var instanceType = option(source.roles().get(role)).flatMap(rt -> rt.instanceType()).or("default");
        var zone = source.zone().or("default");
        var group = NodeGroupConfig.nodeGroupConfig(sourceName, role.value(), count, instanceType, zone, Map.of());
        return CloudProviderSupport.provisionVia(compute, group).await();
    }

    private static Result<List<ProvisionedNode>> forgeProvisionPlaceholder(String sourceName,
                                                                           NodeRole role,
                                                                           int count) {
        logAction("+",
                  sourceName + "." + role.value() + "/" + SourceType.FORGE.value() + ": " + count + " in-process node(s) will be started by Forge");
        var nodes = new ArrayList<ProvisionedNode>();
        for (int i = 0;i <count;i++) {nodes.add(ProvisionedNode.provisionedNode(sourceName + "-" + role.value() + "-" + i,
                                                                                "forge",
                                                                                "127.0.0.1"));}
        return success(List.copyOf(nodes));
    }

    private static Result<List<ProvisionedNode>> sshProvisionPlaceholder(String sourceName,
                                                                         NodeRole role,
                                                                         SourceProfile source) {
        var hosts = option(source.roles().get(role)).flatMap(rt -> rt.hosts()).or(List.of());
        logAction("+",
                  sourceName + "." + role.value() + "/ssh: " + hosts.size() + " pre-existing host(s) registered");
        var nodes = new ArrayList<ProvisionedNode>();
        for (int i = 0;i <hosts.size();i++) {nodes.add(ProvisionedNode.provisionedNode(sourceName + "-" + role.value() + "-" + i,
                                                                                       "ssh",
                                                                                       hosts.get(i)));}
        return success(List.copyOf(nodes));
    }

    static final long DRAIN_TIMEOUT_MS = 120_000;

    static final long READY_TIMEOUT_MS = 300_000;

    private static Result<Integer> executeModifications(List<DiffAction> modifications,
                                                        ClusterBootstrapConfig stored,
                                                        ClusterBootstrapConfig desired) {
        var totalModified = 0;
        for (var action : modifications) {
            var result = executeModification(action, stored, desired);
            if (result.isFailure()) {return result;}
            totalModified += result.or(0);
        }
        return success(totalModified);
    }

    private static Result<Integer> executeModification(DiffAction action,
                                                       ClusterBootstrapConfig stored,
                                                       ClusterBootstrapConfig desired) {
        return switch (action){
            case DiffAction.RuntimeChange a -> executeRuntimeChange(a, stored, desired);
            case DiffAction.SourceFieldChange a -> executeSourceFieldChange(a, stored, desired);
            case DiffAction.ClusterLevelChange a -> logClusterLevelChange(a);
            default -> logUnknownModification(action);
        };
    }

    private static Result<Integer> executeRuntimeChange(DiffAction.RuntimeChange change,
                                                        ClusterBootstrapConfig stored,
                                                        ClusterBootstrapConfig desired) {
        logAction("~",
                  change.sourceName() + "." + change.role().value() + ": runtime change " + change.fromRuntime() + " -> " + change.toRuntime());
        return lookupSource(change.sourceName(), stored.sources()).flatMap(source -> rollingRestart(change.sourceName(),
                                                                                                    change.role(),
                                                                                                    source,
                                                                                                    desired));
    }

    private static Result<Integer> rollingRestart(String sourceName,
                                                  NodeRole role,
                                                  SourceProfile source,
                                                  ClusterBootstrapConfig desired) {
        var managementPort = desired.operations().ports()
                                               .management();
        var maxUnavailable = resolveMaxUnavailable(role, desired);
        var nodeCount = option(source.roles().get(role)).flatMap(RoleSubTable::count).or(0);
        var modified = 0;
        for (int batch = 0;batch <nodeCount;batch += maxUnavailable) {
            var batchSize = Math.min(maxUnavailable, nodeCount - batch);
            var result = rollingRestartBatch(sourceName, role, source, desired, managementPort, batch, batchSize);
            if (result.isFailure()) {return result;}
            modified += result.or(0);
        }
        return success(modified);
    }

    private static Result<Integer> rollingRestartBatch(String sourceName,
                                                       NodeRole role,
                                                       SourceProfile source,
                                                       ClusterBootstrapConfig desired,
                                                       int managementPort,
                                                       int startIndex,
                                                       int batchSize) {
        var modified = 0;
        for (int i = startIndex;i <startIndex + batchSize;i++) {
            var nodeId = sourceName + "-" + role.value() + "-" + i;
            var result = rollingRestartSingleNode(nodeId, sourceName, role, source, desired, managementPort);
            if (result.isFailure()) {return result;}
            modified++;
        }
        return success(modified);
    }

    private static Result<Integer> rollingRestartSingleNode(String nodeId,
                                                            String sourceName,
                                                            NodeRole role,
                                                            SourceProfile source,
                                                            ClusterBootstrapConfig desired,
                                                            int managementPort) {
        logAction("~", "  draining " + nodeId + "...");
        return drainAndDestroyNode(nodeId, sourceName, role, source, managementPort).flatMap(_ -> reprovisionNode(sourceName,
                                                                                                                  role,
                                                                                                                  desired))
                                  .map(_ -> logAndCount("~", "  " + nodeId + " restarted with new runtime", 1));
    }

    private static Result<Unit> drainAndDestroyNode(String nodeId,
                                                    String sourceName,
                                                    NodeRole role,
                                                    SourceProfile source,
                                                    int managementPort) {
        return switch (source.type()){
            case SSH -> drainSshNode(nodeId, sourceName, source, managementPort);
            case CLOUD, DOCKER -> drainAndDestroyComputeNode(nodeId, sourceName, role, source, managementPort);
            case FORGE -> forgeRestartPlaceholder(nodeId);
        };
    }

    private static Result<Unit> drainSshNode(String nodeId,
                                             String sourceName,
                                             SourceProfile source,
                                             int managementPort) {
        var hosts = option(source.roles().values()
                                       .stream()
                                       .flatMap(r -> r.hosts().stream()
                                                            .flatMap(List::stream))
                                       .toList()).filter(l -> !l.isEmpty())
                          .or(List.of());
        if (hosts.isEmpty()) {return Result.unitResult();}
        var host = hosts.getFirst();
        return ClusterHttpClient.drainNode(host, managementPort, nodeId).flatMap(_ -> ClusterHttpClient.waitForDrainComplete(host,
                                                                                                                             managementPort,
                                                                                                                             nodeId,
                                                                                                                             DRAIN_TIMEOUT_MS))
                                          .flatMap(_ -> sshStopNode(host, source));
    }

    private static Result<Unit> sshStopNode(String host, SourceProfile source) {
        var sshConfig = buildSshConfig(source);
        logAction("~", "  SSH stop on " + host);
        return RemoteCommandRunner.ssh(host, "docker stop aether-node || true", sshConfig).mapToUnit();
    }

    private static Result<Unit> drainAndDestroyComputeNode(String nodeId,
                                                           String sourceName,
                                                           NodeRole role,
                                                           SourceProfile source,
                                                           int managementPort) {
        var address = resolveNodeAddress(nodeId);
        return ClusterHttpClient.drainNode(address, managementPort, nodeId).flatMap(_ -> ClusterHttpClient.waitForDrainComplete(address,
                                                                                                                                managementPort,
                                                                                                                                nodeId,
                                                                                                                                DRAIN_TIMEOUT_MS))
                                          .flatMap(_ -> dispatchDestroy(sourceName, source, role, 1, managementPort));
    }

    private static Result<List<ProvisionedNode>> reprovisionNode(String sourceName,
                                                                 NodeRole role,
                                                                 ClusterBootstrapConfig desired) {
        return lookupSource(sourceName,
                            desired.sources()).flatMap(source -> dispatchProvision(sourceName, source, role, 1))
                           .flatMap(nodes -> waitForNewNodes(nodes,
                                                             desired.operations().ports()
                                                                               .management()));
    }

    private static Result<List<ProvisionedNode>> waitForNewNodes(List<ProvisionedNode> nodes, int managementPort) {
        for (var node : nodes) {
            var result = ClusterHttpClient.waitForNodeReady(node.publicIp(), managementPort, READY_TIMEOUT_MS);
            if (result.isFailure()) {return result.map(_ -> List.of());}
        }
        return success(nodes);
    }

    private static Result<Integer> executeSourceFieldChange(DiffAction.SourceFieldChange change,
                                                            ClusterBootstrapConfig stored,
                                                            ClusterBootstrapConfig desired) {
        logAction("~",
                  change.sourceName() + ": " + change.field() + " changed (replace-before-retire)");
        return lookupSource(change.sourceName(), stored.sources()).flatMap(oldSource -> lookupSource(change.sourceName(),
                                                                                                     desired.sources()).flatMap(newSource -> replaceBeforeRetire(change.sourceName(),
                                                                                                                                                                 oldSource,
                                                                                                                                                                 newSource,
                                                                                                                                                                 desired)));
    }

    private static Result<Integer> replaceBeforeRetire(String sourceName,
                                                       SourceProfile oldSource,
                                                       SourceProfile newSource,
                                                       ClusterBootstrapConfig desired) {
        var managementPort = desired.operations().ports()
                                               .management();
        var totalAffected = 0;
        for (var entry : oldSource.roles().entrySet()) {
            var role = entry.getKey();
            var count = entry.getValue().count()
                                      .or(0);
            if (count <= 0) {continue;}
            var result = replaceBeforeRetireRole(sourceName, role, count, newSource, desired, managementPort);
            if (result.isFailure()) {return result;}
            totalAffected += result.or(0);
        }
        return success(totalAffected);
    }

    private static Result<Integer> replaceBeforeRetireRole(String sourceName,
                                                           NodeRole role,
                                                           int count,
                                                           SourceProfile newSource,
                                                           ClusterBootstrapConfig desired,
                                                           int managementPort) {
        logAction("~", "  provisioning " + count + " new " + role.value() + " node(s)...");
        return dispatchProvision(sourceName, newSource, role, count).flatMap(nodes -> waitForNewNodes(nodes,
                                                                                                      managementPort))
                                .flatMap(_ -> drainOldNodes(sourceName, role, count, desired))
                                .map(count2 -> logAndCount("~",
                                                           "  " + sourceName + "." + role.value() + ": replaced " + count + " node(s)",
                                                           count));
    }

    private static Result<Unit> drainOldNodes(String sourceName,
                                              NodeRole role,
                                              int count,
                                              ClusterBootstrapConfig desired) {
        var managementPort = desired.operations().ports()
                                               .management();
        for (int i = 0;i <count;i++) {
            var nodeId = sourceName + "-" + role.value() + "-old-" + i;
            var address = resolveNodeAddress(nodeId);
            Result<Unit> result = ClusterHttpClient.drainNode(address, managementPort, nodeId)
                                                             .flatMap(_ -> ClusterHttpClient.waitForDrainComplete(address,
                                                                                                                  managementPort,
                                                                                                                  nodeId,
                                                                                                                  DRAIN_TIMEOUT_MS));
            if (result.isFailure()) {return result;}
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

    private static int resolveMaxUnavailable(NodeRole role, ClusterBootstrapConfig config) {
        return role == NodeRole.CORE
              ? config.coreTopology().maxUnavailable()
              : Integer.MAX_VALUE;
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

    private static Result<Unit> forgeRestartPlaceholder(String nodeId) {
        logAction("~", "  " + nodeId + "/forge: in-process node will be restarted by Forge");
        return Result.unitResult();
    }

    private static Result<Integer> executeRemovals(List<DiffAction> removals,
                                                   ClusterBootstrapConfig stored,
                                                   int managementPort) {
        var totalRemoved = 0;
        for (var action : removals) {
            var result = executeRemoval(action, stored, managementPort);
            if (result.isFailure()) {return result;}
            totalRemoved += result.or(0);
        }
        return success(totalRemoved);
    }

    private static Result<Integer> executeRemoval(DiffAction action,
                                                  ClusterBootstrapConfig stored,
                                                  int managementPort) {
        return switch (action){
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

    private static Result<Integer> destroyEntireSource(String sourceName,
                                                       ClusterBootstrapConfig stored,
                                                       int managementPort) {
        return lookupSource(sourceName, stored.sources()).flatMap(source -> destroyAllRoles(sourceName,
                                                                                            source,
                                                                                            managementPort));
    }

    private static Result<Integer> destroyAllRoles(String sourceName, SourceProfile source, int managementPort) {
        var totalDestroyed = 0;
        for (var entry : source.roles().entrySet()) {
            var count = entry.getValue().count()
                                      .or(0);
            if (count <= 0) {continue;}
            var result = dispatchDestroy(sourceName, source, entry.getKey(), count, managementPort);
            if (result.isFailure()) {return result.map(_ -> 0);}
            totalDestroyed += count;
        }
        logAction("-", sourceName + ": destroyed " + totalDestroyed + " node(s) across all roles");
        return success(totalDestroyed);
    }

    private static Result<Integer> destroyRole(String sourceName,
                                               NodeRole role,
                                               int count,
                                               ClusterBootstrapConfig stored,
                                               int managementPort) {
        return lookupSource(sourceName,
                            stored.sources()).flatMap(source -> dispatchDestroy(sourceName,
                                                                                source,
                                                                                role,
                                                                                count,
                                                                                managementPort))
                           .map(_ -> logAndCount("-",
                                                 sourceName + "." + role.value() + ": destroyed " + count + " node(s)",
                                                 count));
    }

    private static Result<Integer> destroyScaleDown(String sourceName,
                                                    NodeRole role,
                                                    int from,
                                                    int to,
                                                    ClusterBootstrapConfig stored,
                                                    int managementPort) {
        var excess = from - to;
        return lookupSource(sourceName,
                            stored.sources()).flatMap(source -> dispatchDestroy(sourceName,
                                                                                source,
                                                                                role,
                                                                                excess,
                                                                                managementPort))
                           .map(_ -> logAndCount("-",
                                                 sourceName + "." + role.value() + ": scaled down by " + excess + " node(s) (LIFO)",
                                                 excess));
    }

    private static Result<Unit> dispatchDestroy(String sourceName,
                                                SourceProfile source,
                                                NodeRole role,
                                                int count,
                                                int managementPort) {
        return switch (source.type()){
            case CLOUD -> resolveCloudAndDestroy(source, sourceName, role, count);
            case DOCKER -> resolveDockerAndDestroy(sourceName, role, count);
            case FORGE -> forgeDestroyPlaceholder(sourceName, role, count);
            case SSH -> drainAndStopSshNodes(sourceName, role, count, source, managementPort);
        };
    }

    private static Result<Unit> resolveCloudAndDestroy(SourceProfile source,
                                                       String sourceName,
                                                       NodeRole role,
                                                       int count) {
        return ProviderResolver.resolveCloudCompute(source)
                                                   .flatMap(compute -> destroyViaCompute(compute,
                                                                                         sourceName,
                                                                                         role,
                                                                                         count));
    }

    private static Result<Unit> resolveDockerAndDestroy(String sourceName, NodeRole role, int count) {
        return ProviderResolver.resolveDockerCompute()
                                                    .flatMap(compute -> destroyViaCompute(compute,
                                                                                          sourceName,
                                                                                          role,
                                                                                          count));
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<Unit> destroyViaCompute(ComputeProvider compute,
                                                                                  String sourceName,
                                                                                  NodeRole role,
                                                                                  int count) {
        var nodeIds = buildNodeIds(sourceName, role, count);
        return CloudProviderSupport.destroyVia(compute, nodeIds).await();
    }

    private static List<String> buildNodeIds(String sourceName, NodeRole role, int count) {
        var ids = new ArrayList<String>(count);
        for (int i = count - 1;i >= 0;i--) {ids.add(sourceName + "-" + role.value() + "-" + i);}
        return List.copyOf(ids);
    }

    private static Result<Unit> forgeDestroyPlaceholder(String sourceName, NodeRole role, int count) {
        logAction("-",
                  sourceName + "." + role.value() + "/forge: " + count + " in-process node(s) will be stopped by Forge");
        return Result.unitResult();
    }

    private static Result<Unit> drainAndStopSshNodes(String sourceName,
                                                     NodeRole role,
                                                     int count,
                                                     SourceProfile source,
                                                     int managementPort) {
        logAction("-", sourceName + "." + role.value() + "/ssh: draining " + count + " node(s) (hosts remain)");
        var hosts = option(source.roles().get(role)).flatMap(RoleSubTable::hosts).or(List.of());
        var stopCount = Math.min(count, hosts.size());
        for (int i = 0;i <stopCount;i++) {
            var host = hosts.get(hosts.size() - 1 - i);
            var nodeId = sourceName + "-" + role.value() + "-" + (hosts.size() - 1 - i);
            var result = ClusterHttpClient.drainNode(host, managementPort, nodeId).flatMap(_ -> ClusterHttpClient.waitForDrainComplete(host,
                                                                                                                                       managementPort,
                                                                                                                                       nodeId,
                                                                                                                                       DRAIN_TIMEOUT_MS))
                                                    .flatMap(_ -> sshStopNode(host, source));
            if (result.isFailure()) {return result;}
        }
        return Result.unitResult();
    }

    private static Result<SourceProfile> lookupSource(String sourceName, Map<String, SourceProfile> sources) {
        return option(sources.get(sourceName)).toResult(new ApplyError.SourceNotFound(sourceName));
    }

    private static int logAndCount(String symbol, String message, int count) {
        logAction(symbol, message);
        return count;
    }

    @Contract private static void logAction(String symbol, String message) {
        System.out.printf("  [%s] %s%n", symbol, message);
    }

    public sealed interface ApplyError extends Cause {
        record SourceNotFound(String sourceName) implements ApplyError {
            @Override public String message() {
                return "Source '" + sourceName + "' not found in configuration";
            }
        }

        record SshScaleNotSupported(String sourceName) implements ApplyError {
            @Override public String message() {
                return "SSH source '" + sourceName + "' cannot scale up: hosts are fixed. Add hosts to the config and re-apply.";
            }
        }

        record ProvisionFailed(String sourceName, String detail) implements ApplyError {
            @Override public String message() {
                return "Provisioning failed for source '" + sourceName + "': " + detail;
            }
        }

        record DestroyFailed(String sourceName, String detail) implements ApplyError {
            @Override public String message() {
                return "Destroy failed for source '" + sourceName + "': " + detail;
            }
        }
    }
}
