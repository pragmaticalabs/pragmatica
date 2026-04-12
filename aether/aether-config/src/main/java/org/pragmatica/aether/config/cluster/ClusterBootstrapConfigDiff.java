package org.pragmatica.aether.config.cluster;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.pragmatica.aether.config.cluster.DiffPlan.diffPlan;


/// Computes diff between two [ClusterBootstrapConfig] instances. S9
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) public final class ClusterBootstrapConfigDiff {
    private ClusterBootstrapConfigDiff() {}

    public static DiffPlan diff(ClusterBootstrapConfig stored, ClusterBootstrapConfig desired) {
        var additions = new ArrayList<DiffAction>();
        var modifications = new ArrayList<DiffAction>();
        var removals = new ArrayList<DiffAction>();
        var immutable = new ArrayList<DiffAction>();
        diffImmutableFields(stored, desired, immutable);
        diffClusterLevel(stored, desired, modifications);
        diffSources(stored.sources(), desired.sources(), additions, modifications, removals);
        return diffPlan(additions, modifications, removals, immutable);
    }

    public static String formatPlan(DiffPlan plan, ClusterBootstrapConfig stored, ClusterBootstrapConfig desired) {
        var sb = new StringBuilder();
        appendHeader(sb, stored, desired);
        appendActions(sb, plan.allActions());
        return sb.toString();
    }

    private static void diffImmutableFields(ClusterBootstrapConfig stored,
                                            ClusterBootstrapConfig desired,
                                            List<DiffAction> immutable) {
        if (!Objects.equals(stored.cluster().name(),
                            desired.cluster().name())) {immutable.add(new DiffAction.ImmutableFieldChange("cluster.name"));}
    }

    private static void diffClusterLevel(ClusterBootstrapConfig stored,
                                         ClusterBootstrapConfig desired,
                                         List<DiffAction> modifications) {
        if (!Objects.equals(stored.cluster().version(),
                            desired.cluster().version())) {modifications.add(new DiffAction.ClusterLevelChange("version",
                                                                                                               stored.cluster()
                                                                                                                             .version(),
                                                                                                               desired.cluster()
                                                                                                                              .version()));}
    }

    private static void diffSources(Map<String, SourceProfile> storedSources,
                                    Map<String, SourceProfile> desiredSources,
                                    List<DiffAction> additions,
                                    List<DiffAction> modifications,
                                    List<DiffAction> removals) {
        var allKeys = new HashSet<String>();
        allKeys.addAll(storedSources.keySet());
        allKeys.addAll(desiredSources.keySet());
        for (var key : allKeys) {
            var storedSource = storedSources.get(key);
            var desiredSource = desiredSources.get(key);
            if (storedSource == null) {addNewSource(key, desiredSource, additions);} else if (desiredSource == null) {removals.add(new DiffAction.RemoveSource(key));} else {diffExistingSource(key,
                                                                                                                                                                                                storedSource,
                                                                                                                                                                                                desiredSource,
                                                                                                                                                                                                additions,
                                                                                                                                                                                                modifications,
                                                                                                                                                                                                removals);}
        }
    }

    private static void addNewSource(String sourceName, SourceProfile source, List<DiffAction> additions) {
        additions.add(new DiffAction.AddSource(sourceName));
        for (var entry : source.roles().entrySet()) {additions.add(new DiffAction.AddRole(sourceName,
                                                                                          entry.getKey(),
                                                                                          roleSize(entry.getValue())));}
    }

    private static void diffExistingSource(String sourceName,
                                           SourceProfile stored,
                                           SourceProfile desired,
                                           List<DiffAction> additions,
                                           List<DiffAction> modifications,
                                           List<DiffAction> removals) {
        diffSourceFields(sourceName, stored, desired, modifications);
        diffRoles(sourceName, stored.roles(), desired.roles(), additions, modifications, removals);
    }

    private static void diffSourceFields(String sourceName,
                                         SourceProfile stored,
                                         SourceProfile desired,
                                         List<DiffAction> modifications) {
        checkSourceField(sourceName, "credentials", stored.credentials(), desired.credentials(), modifications);
        checkSourceField(sourceName, "databases", stored.databases(), desired.databases(), modifications);
        checkSourceField(sourceName, "region", stored.region(), desired.region(), modifications);
        checkSourceField(sourceName, "zone", stored.zone(), desired.zone(), modifications);
        checkSourceField(sourceName, "loadBalancer", stored.loadBalancer(), desired.loadBalancer(), modifications);
        checkSourceField(sourceName,
                         "loadBalancerIps",
                         stored.loadBalancerIps(),
                         desired.loadBalancerIps(),
                         modifications);
        checkSourceField(sourceName,
                         "loadBalancerEndpoint",
                         stored.loadBalancerEndpoint(),
                         desired.loadBalancerEndpoint(),
                         modifications);
        checkSourceField(sourceName, "firewallRules", stored.firewallRules(), desired.firewallRules(), modifications);
    }

    private static void checkSourceField(String sourceName,
                                         String field,
                                         Object storedValue,
                                         Object desiredValue,
                                         List<DiffAction> modifications) {
        if (!Objects.equals(storedValue, desiredValue)) {modifications.add(new DiffAction.SourceFieldChange(sourceName,
                                                                                                            field));}
    }

    private static void diffRoles(String sourceName,
                                  Map<NodeRole, RoleSubTable> storedRoles,
                                  Map<NodeRole, RoleSubTable> desiredRoles,
                                  List<DiffAction> additions,
                                  List<DiffAction> modifications,
                                  List<DiffAction> removals) {
        var allRoles = new HashSet<NodeRole>();
        allRoles.addAll(storedRoles.keySet());
        allRoles.addAll(desiredRoles.keySet());
        for (var role : allRoles) {
            var storedRole = storedRoles.get(role);
            var desiredRole = desiredRoles.get(role);
            if (storedRole == null) {additions.add(new DiffAction.AddRole(sourceName, role, roleSize(desiredRole)));} else if (desiredRole == null) {removals.add(new DiffAction.RemoveRole(sourceName,
                                                                                                                                                                                            role,
                                                                                                                                                                                            roleSize(storedRole)));} else {diffExistingRole(sourceName,
                                                                                                                                                                                                                                            role,
                                                                                                                                                                                                                                            storedRole,
                                                                                                                                                                                                                                            desiredRole,
                                                                                                                                                                                                                                            additions,
                                                                                                                                                                                                                                            modifications,
                                                                                                                                                                                                                                            removals);}
        }
    }

    private static void diffExistingRole(String sourceName,
                                         NodeRole role,
                                         RoleSubTable stored,
                                         RoleSubTable desired,
                                         List<DiffAction> additions,
                                         List<DiffAction> modifications,
                                         List<DiffAction> removals) {
        var storedSize = roleSize(stored);
        var desiredSize = roleSize(desired);
        if (storedSize <desiredSize) {additions.add(new DiffAction.ScaleUp(sourceName, role, storedSize, desiredSize));} else if (storedSize > desiredSize) {removals.add(new DiffAction.ScaleDown(sourceName,
                                                                                                                                                                                                   role,
                                                                                                                                                                                                   storedSize,
                                                                                                                                                                                                   desiredSize));}
        if (!Objects.equals(stored.runtimeRef(), desired.runtimeRef())) {modifications.add(new DiffAction.RuntimeChange(sourceName,
                                                                                                                        role,
                                                                                                                        stored.runtimeRef(),
                                                                                                                        desired.runtimeRef()));}
    }

    private static int roleSize(RoleSubTable role) {
        return role.count().or(0) + role.hosts().map(List::size)
                                              .or(0);
    }

    private static void appendHeader(StringBuilder sb, ClusterBootstrapConfig stored, ClusterBootstrapConfig desired) {
        sb.append("Cluster: ").append(stored.cluster().name())
                 .append('\n');
        appendSummaryLine(sb, "Current", stored);
        appendSummaryLine(sb, "Desired", desired);
        sb.append('\n');
    }

    private static void appendSummaryLine(StringBuilder sb, String label, ClusterBootstrapConfig config) {
        var coreCount = countByRole(config, NodeRole.CORE);
        var workerCount = countByRole(config, NodeRole.WORKER);
        var sourceCount = config.sources().size();
        sb.append(label).append(": ")
                 .append(coreCount)
                 .append(" cores, ")
                 .append(workerCount)
                 .append(" workers (")
                 .append(sourceCount)
                 .append(" sources)\n");
    }

    private static int countByRole(ClusterBootstrapConfig config, NodeRole role) {
        return config.sources().values()
                             .stream()
                             .map(s -> s.roles().get(role))
                             .filter(Objects::nonNull)
                             .mapToInt(ClusterBootstrapConfigDiff::roleSize)
                             .sum();
    }

    private static void appendActions(StringBuilder sb, List<DiffAction> actions) {
        for (var action : actions) {sb.append("  ").append(action.symbol())
                                             .append(' ')
                                             .append(action.description())
                                             .append('\n');}
    }
}
