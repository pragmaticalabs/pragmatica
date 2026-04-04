package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.pragmatica.lang.Result.success;


/// Computes field-level diff between two [ClusterManagementConfig] instances.
/// Used by the apply flow to determine what actions are needed to converge
/// the stored config to the desired config.
@SuppressWarnings({"JBCT-UTIL-02", "JBCT-SEQ-01"}) public final class ClusterConfigDiff {
    private final List<ConfigChange> changes;

    private ClusterConfigDiff(List<ConfigChange> changes) {
        this.changes = List.copyOf(changes);
    }

    public List<ConfigChange> changes() {
        return changes;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public boolean hasImmutableChanges() {
        return changes.stream().anyMatch(ConfigChange::isImmutable);
    }

    public List<ConfigChange> immutableChanges() {
        return changes.stream().filter(ConfigChange::isImmutable)
                             .toList();
    }

    public List<ConfigChange> actionableChanges() {
        return changes.stream().filter(ConfigChange::requiresAction)
                             .toList();
    }

    public Result<List<ConfigChange>> validateAndExtractActions() {
        if (hasImmutableChanges()) {return buildImmutableError().result();}
        return success(actionableChanges());
    }

    public static ClusterConfigDiff diff(ClusterManagementConfig stored, ClusterManagementConfig desired) {
        var changes = new ArrayList<ConfigChange>();
        diffImmutableFields(stored, desired, changes);
        diffClusterSpec(stored.cluster(), desired.cluster(), changes);
        diffTls(stored.deployment(), desired.deployment(), changes);
        return new ClusterConfigDiff(changes);
    }

    private static void diffImmutableFields(ClusterManagementConfig stored,
                                            ClusterManagementConfig desired,
                                            List<ConfigChange> changes) {
        diffDeploymentType(stored.deployment(), desired.deployment(), changes);
        diffPorts(stored.deployment(), desired.deployment(), changes);
        diffInstances(stored.deployment(), desired.deployment(), changes);
        diffClusterName(stored.cluster(), desired.cluster(), changes);
    }

    private static void diffDeploymentType(DeploymentSpec stored, DeploymentSpec desired, List<ConfigChange> changes) {
        if (stored.type() != desired.type()) {changes.add(new ConfigChange.ImmutableChange("deployment.type",
                                                                                           stored.type().value(),
                                                                                           desired.type().value()));}
    }

    private static void diffPorts(DeploymentSpec stored, DeploymentSpec desired, List<ConfigChange> changes) {
        diffImmutablePort("deployment.ports.cluster",
                          stored.ports().cluster(),
                          desired.ports().cluster(),
                          changes);
        diffImmutablePort("deployment.ports.management",
                          stored.ports().management(),
                          desired.ports().management(),
                          changes);
        diffImmutablePort("deployment.ports.app-http",
                          stored.ports().appHttp(),
                          desired.ports().appHttp(),
                          changes);
        diffImmutablePort("deployment.ports.swim",
                          stored.ports().swim(),
                          desired.ports().swim(),
                          changes);
    }

    private static void diffImmutablePort(String field, int stored, int desired, List<ConfigChange> changes) {
        if (stored != desired) {changes.add(new ConfigChange.ImmutableChange(field,
                                                                             String.valueOf(stored),
                                                                             String.valueOf(desired)));}
    }

    private static void diffInstances(DeploymentSpec stored, DeploymentSpec desired, List<ConfigChange> changes) {
        if (!stored.instances().equals(desired.instances())) {changes.add(new ConfigChange.ImmutableChange("deployment.instances",
                                                                                                           stored.instances()
                                                                                                                           .toString(),
                                                                                                           desired.instances()
                                                                                                                            .toString()));}
    }

    private static void diffClusterName(ClusterSpec stored, ClusterSpec desired, List<ConfigChange> changes) {
        if (!Objects.equals(stored.name(), desired.name())) {changes.add(new ConfigChange.ImmutableChange("cluster.name",
                                                                                                          stored.name(),
                                                                                                          desired.name()));}
    }

    private static void diffClusterSpec(ClusterSpec stored, ClusterSpec desired, List<ConfigChange> changes) {
        diffCoreCount(stored.core(), desired.core(), changes);
        diffCoreMin(stored.core(), desired.core(), changes);
        diffCoreMax(stored.core(), desired.core(), changes);
        diffAutoHeal(stored.autoHeal(), desired.autoHeal(), changes);
        diffVersion(stored, desired, changes);
        diffUpgradeStrategy(stored.upgrade(), desired.upgrade(), changes);
    }

    private static void diffCoreCount(CoreSpec stored, CoreSpec desired, List<ConfigChange> changes) {
        if (stored.count() != desired.count()) {changes.add(new ConfigChange.ScaleCore(stored.count(), desired.count()));}
    }

    private static void diffCoreMin(CoreSpec stored, CoreSpec desired, List<ConfigChange> changes) {
        if (stored.min() != desired.min()) {changes.add(new ConfigChange.UpdateCoreMin(stored.min(), desired.min()));}
    }

    private static void diffCoreMax(CoreSpec stored, CoreSpec desired, List<ConfigChange> changes) {
        if (stored.max() != desired.max()) {changes.add(new ConfigChange.UpdateCoreMax(stored.max(), desired.max()));}
    }

    private static void diffAutoHeal(AutoHealSpec stored, AutoHealSpec desired, List<ConfigChange> changes) {
        if (!Objects.equals(stored, desired)) {changes.add(new ConfigChange.UpdateAutoHeal(stored, desired));}
    }

    private static void diffVersion(ClusterSpec stored, ClusterSpec desired, List<ConfigChange> changes) {
        if (!Objects.equals(stored.version(), desired.version())) {changes.add(new ConfigChange.UpdateVersion(stored.version(),
                                                                                                              desired.version()));}
    }

    private static void diffUpgradeStrategy(UpgradeSpec stored, UpgradeSpec desired, List<ConfigChange> changes) {
        if (!Objects.equals(stored.strategy(), desired.strategy())) {changes.add(new ConfigChange.UpdateUpgradeStrategy(stored.strategy(),
                                                                                                                        desired.strategy()));}
    }

    private static void diffTls(DeploymentSpec stored, DeploymentSpec desired, List<ConfigChange> changes) {
        var storedTls = stored.tls();
        var desiredTls = desired.tls();
        if (!Objects.equals(storedTls, desiredTls)) {storedTls.onPresent(s -> desiredTls.onPresent(d -> addTlsChangeIfDifferent(s,
                                                                                                                                d,
                                                                                                                                changes)));}
    }

    private static void addTlsChangeIfDifferent(TlsDeploymentConfig stored,
                                                TlsDeploymentConfig desired,
                                                List<ConfigChange> changes) {
        if (!Objects.equals(stored, desired)) {changes.add(new ConfigChange.UpdateTls(stored, desired));}
    }

    private ClusterConfigError.ValidationFailed buildImmutableError() {
        var errors = immutableChanges().stream()
                                     .map(c -> (ClusterConfigError) new ClusterConfigError.ImmutableFieldChange(c.description()))
                                     .toList();
        return new ClusterConfigError.ValidationFailed(errors);
    }

    public sealed interface ConfigChange {
        String description();
        boolean requiresAction();

        default boolean isImmutable() {
            return false;
        }

        record ScaleCore(int from, int to) implements ConfigChange {
            @Override public String description() {
                return "cluster.core.count: " + from + " -> " + to;
            }

            @Override public boolean requiresAction() {
                return true;
            }
        }

        record UpdateAutoHeal(AutoHealSpec from, AutoHealSpec to) implements ConfigChange {
            @Override public String description() {
                return "cluster.auto_heal: updated";
            }

            @Override public boolean requiresAction() {
                return true;
            }
        }

        record UpdateCoreMin(int from, int to) implements ConfigChange {
            @Override public String description() {
                return "cluster.core.min: " + from + " -> " + to;
            }

            @Override public boolean requiresAction() {
                return true;
            }
        }

        record UpdateCoreMax(int from, int to) implements ConfigChange {
            @Override public String description() {
                return "cluster.core.max: " + from + " -> " + to;
            }

            @Override public boolean requiresAction() {
                return true;
            }
        }

        record UpdateVersion(String from, String to) implements ConfigChange {
            @Override public String description() {
                return "cluster.version: " + from + " -> " + to;
            }

            @Override public boolean requiresAction() {
                return true;
            }
        }

        record UpdateUpgradeStrategy(UpgradeStrategy from, UpgradeStrategy to) implements ConfigChange {
            @Override public String description() {
                return "cluster.upgrade.strategy: " + from.value() + " -> " + to.value();
            }

            @Override public boolean requiresAction() {
                return true;
            }
        }

        record UpdateTls(TlsDeploymentConfig from, TlsDeploymentConfig to) implements ConfigChange {
            @Override public String description() {
                return "deployment.tls: updated";
            }

            @Override public boolean requiresAction() {
                return true;
            }
        }

        record ImmutableChange(String field, String from, String to) implements ConfigChange {
            @Override public String description() {
                return field + ": " + from + " -> " + to;
            }

            @Override public boolean requiresAction() {
                return false;
            }

            @Override public boolean isImmutable() {
                return true;
            }
        }

        record NoOp(String field) implements ConfigChange {
            @Override public String description() {
                return field + ": unchanged";
            }

            @Override public boolean requiresAction() {
                return false;
            }
        }
    }
}
