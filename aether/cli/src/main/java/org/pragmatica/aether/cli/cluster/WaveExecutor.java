package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
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
        return executeAdditions(plan.additions(), desired).flatMap(added -> executeModifications(plan.modifications()).flatMap(modified -> executeRemovals(plan.removals(),
                                                                                                                                                           stored).map(removed -> applyResult(plan,
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

    private static Result<Integer> executeModifications(List<DiffAction> modifications) {
        var totalModified = 0;
        for (var action : modifications) {
            logModification(action);
            totalModified += modificationNodeCount(action);
        }
        return success(totalModified);
    }

    @Contract private static void logModification(DiffAction action) {
        switch (action){
            case DiffAction.RuntimeChange a -> logAction("~",
                                                         a.sourceName() + "." + a.role().value() + ": runtime change " + a.fromRuntime() + " -> " + a.toRuntime() + " (rolling restart required)");
            case DiffAction.SourceFieldChange a -> logAction("~",
                                                             a.sourceName() + ": " + a.field() + " changed (replace-before-retire planned)");
            case DiffAction.ClusterLevelChange a -> logAction("~",
                                                              "cluster." + a.field() + ": " + a.from() + " -> " + a.to() + " (cluster-wide update)");
            default -> logAction("~", action.description());
        }
    }

    private static int modificationNodeCount(DiffAction action) {
        return switch (action){
            case DiffAction.RuntimeChange _ -> 1;
            case DiffAction.SourceFieldChange _ -> 1;
            case DiffAction.ClusterLevelChange _ -> 1;
            default -> 0;
        };
    }

    private static Result<Integer> executeRemovals(List<DiffAction> removals, ClusterBootstrapConfig stored) {
        var totalRemoved = 0;
        for (var action : removals) {
            var result = executeRemoval(action, stored);
            if (result.isFailure()) {return result;}
            totalRemoved += result.or(0);
        }
        return success(totalRemoved);
    }

    private static Result<Integer> executeRemoval(DiffAction action, ClusterBootstrapConfig stored) {
        return switch (action){
            case DiffAction.RemoveSource a -> destroyEntireSource(a.sourceName(), stored);
            case DiffAction.RemoveRole a -> destroyRole(a.sourceName(), a.role(), a.count(), stored);
            case DiffAction.ScaleDown a -> destroyScaleDown(a.sourceName(), a.role(), a.from(), a.to(), stored);
            default -> success(0);
        };
    }

    private static Result<Integer> destroyEntireSource(String sourceName, ClusterBootstrapConfig stored) {
        return lookupSource(sourceName, stored.sources()).flatMap(source -> destroyAllRoles(sourceName, source));
    }

    private static Result<Integer> destroyAllRoles(String sourceName, SourceProfile source) {
        var totalDestroyed = 0;
        for (var entry : source.roles().entrySet()) {
            var count = entry.getValue().count()
                                      .or(0);
            if (count <= 0) {continue;}
            var result = dispatchDestroy(sourceName, source, entry.getKey(), count);
            if (result.isFailure()) {return result.map(_ -> 0);}
            totalDestroyed += count;
        }
        logAction("-", sourceName + ": destroyed " + totalDestroyed + " node(s) across all roles");
        return success(totalDestroyed);
    }

    private static Result<Integer> destroyRole(String sourceName,
                                               NodeRole role,
                                               int count,
                                               ClusterBootstrapConfig stored) {
        return lookupSource(sourceName,
                            stored.sources()).flatMap(source -> dispatchDestroy(sourceName, source, role, count))
                           .map(_ -> logAndCount("-",
                                                 sourceName + "." + role.value() + ": destroyed " + count + " node(s)",
                                                 count));
    }

    private static Result<Integer> destroyScaleDown(String sourceName,
                                                    NodeRole role,
                                                    int from,
                                                    int to,
                                                    ClusterBootstrapConfig stored) {
        var excess = from - to;
        return lookupSource(sourceName,
                            stored.sources()).flatMap(source -> dispatchDestroy(sourceName, source, role, excess))
                           .map(_ -> logAndCount("-",
                                                 sourceName + "." + role.value() + ": scaled down by " + excess + " node(s) (LIFO)",
                                                 excess));
    }

    private static Result<Unit> dispatchDestroy(String sourceName, SourceProfile source, NodeRole role, int count) {
        return switch (source.type()){
            case CLOUD -> resolveCloudAndDestroy(source, sourceName, role, count);
            case DOCKER -> resolveDockerAndDestroy(sourceName, role, count);
            case FORGE -> forgeDestroyPlaceholder(sourceName, role, count);
            case SSH -> sshDestroyPlaceholder(sourceName, role, count);
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

    private static Result<Unit> sshDestroyPlaceholder(String sourceName, NodeRole role, int count) {
        logAction("-", sourceName + "." + role.value() + "/ssh: " + count + " node(s) will be drained (hosts remain)");
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
