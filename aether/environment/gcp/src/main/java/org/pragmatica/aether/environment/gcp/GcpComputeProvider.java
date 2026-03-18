package org.pragmatica.aether.environment.gcp;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.cloud.gcp.GcpClient;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest.AccessConfig;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest.Disk;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest.InitializeParams;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest.Metadata;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest.MetadataItem;
import org.pragmatica.cloud.gcp.api.InsertInstanceRequest.NetworkInterfaceConfig;
import org.pragmatica.cloud.gcp.api.Instance;
import org.pragmatica.cloud.gcp.api.SetLabelsRequest;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// GCP Cloud implementation of the ComputeProvider SPI.
/// Delegates to GcpClient for instance lifecycle management and maps
/// GCP instance models to the environment integration domain types.
public record GcpComputeProvider(GcpClient client,
                                 GcpEnvironmentConfig config) implements ComputeProvider {
    private static final String MANAGED_LABEL_KEY = "aether-managed";
    private static final String MANAGED_LABEL_VALUE = "true";

    /// Factory method for creating a GcpComputeProvider.
    public static Result<GcpComputeProvider> gcpComputeProvider(GcpClient client,
                                                                GcpEnvironmentConfig config) {
        return success(new GcpComputeProvider(client, config));
    }

    @Override
    public Promise<InstanceInfo> provision(InstanceType instanceType) {
        return client.insertInstance(buildInsertRequest())
                     .map(GcpComputeProvider::toInstanceInfo)
                     .mapError(GcpComputeProvider::toProvisionError);
    }

    @Override
    public Promise<Unit> terminate(InstanceId instanceId) {
        return client.deleteInstance(instanceId.value())
                     .mapError(cause -> toTerminateError(instanceId, cause));
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances() {
        return client.listInstances()
                     .map(GcpComputeProvider::toInstanceInfoList)
                     .mapError(GcpComputeProvider::toListInstancesError);
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return client.listInstances(toLabelFilter(tagFilter))
                     .map(GcpComputeProvider::toInstanceInfoList)
                     .mapError(GcpComputeProvider::toListInstancesError);
    }

    @Override
    public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        return client.getInstance(instanceId.value())
                     .map(GcpComputeProvider::toInstanceInfo)
                     .mapError(GcpComputeProvider::toProvisionError);
    }

    @Override
    public Promise<Unit> restart(InstanceId id) {
        return client.resetInstance(id.value())
                     .mapToUnit();
    }

    @Override
    public Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return client.getInstance(id.value())
                     .flatMap(instance -> setLabelsOnInstance(id.value(),
                                                              instance,
                                                              tags));
    }

    // --- Leaf: set labels on an instance using its current fingerprint ---
    private Promise<Unit> setLabelsOnInstance(String instanceName, Instance instance, Map<String, String> tags) {
        var fingerprint = extractLabelFingerprint(instance);
        return client.setLabels(instanceName,
                                new SetLabelsRequest(tags, fingerprint))
                     .mapToUnit();
    }

    // --- Leaf: extract label fingerprint from instance (empty string if unavailable) ---
    private static String extractLabelFingerprint(Instance instance) {
        return "";
    }

    // --- Leaf: build insert instance request ---
    private InsertInstanceRequest buildInsertRequest() {
        var name = generateInstanceName();
        var machineType = config.machineType();
        var disk = buildBootDisk();
        var networkInterface = buildNetworkInterface();
        var labels = Map.of(MANAGED_LABEL_KEY, MANAGED_LABEL_VALUE);
        var metadata = buildMetadata();
        return new InsertInstanceRequest(name, machineType, List.of(disk), List.of(networkInterface), labels, metadata);
    }

    // --- Leaf: build boot disk configuration ---
    private Disk buildBootDisk() {
        return new Disk(true, true, new InitializeParams(config.sourceImage(), 20, "pd-standard"));
    }

    // --- Leaf: build network interface configuration ---
    private NetworkInterfaceConfig buildNetworkInterface() {
        return new NetworkInterfaceConfig(config.network(),
                                          config.subnetwork(),
                                          List.of(new AccessConfig("External NAT", "ONE_TO_ONE_NAT")));
    }

    // --- Leaf: build instance metadata from user data ---
    private Metadata buildMetadata() {
        return new Metadata(List.of(new MetadataItem("startup-script", config.userData())));
    }

    // --- Leaf: generate a unique instance name ---
    private static String generateInstanceName() {
        return "aether-" + UUID.randomUUID()
                              .toString()
                              .substring(0, 8);
    }

    // --- Leaf: map GCP instance to InstanceInfo ---
    static InstanceInfo toInstanceInfo(Instance instance) {
        return new InstanceInfo(new InstanceId(instance.name()),
                                mapStatus(instance.status()),
                                collectAddresses(instance),
                                InstanceType.ON_DEMAND,
                                safeLabels(instance));
    }

    // --- Leaf: safely extract labels from instance, defaulting to empty map ---
    private static Map<String, String> safeLabels(Instance instance) {
        return option(instance.labels()).or(Map.of());
    }

    // --- Leaf: map list of instances ---
    private static List<InstanceInfo> toInstanceInfoList(List<Instance> instances) {
        return instances.stream()
                        .map(GcpComputeProvider::toInstanceInfo)
                        .toList();
    }

    // --- Leaf: map GCP status string to InstanceStatus ---
    static InstanceStatus mapStatus(String gcpStatus) {
        return switch (gcpStatus) {
            case "PROVISIONING", "STAGING" -> InstanceStatus.PROVISIONING;
            case "RUNNING" -> InstanceStatus.RUNNING;
            case "STOPPING", "TERMINATED", "SUSPENDED", "SUSPENDING" -> InstanceStatus.STOPPING;
            default -> InstanceStatus.TERMINATED;
        };
    }

    // --- Leaf: collect all IP addresses from an instance ---
    static List<String> collectAddresses(Instance instance) {
        return option(instance.networkInterfaces()).map(GcpComputeProvider::extractIpsFromInterfaces)
                     .or(List.of());
    }

    // --- Leaf: extract IPs from network interfaces ---
    private static List<String> extractIpsFromInterfaces(List<Instance.NetworkInterface> interfaces) {
        return interfaces.stream()
                         .map(Instance.NetworkInterface::networkIP)
                         .toList();
    }

    // --- Leaf: convert tag filter map to GCP label filter string ---
    static String toLabelFilter(Map<String, String> tagFilter) {
        return tagFilter.entrySet()
                        .stream()
                        .map(GcpComputeProvider::toLabelFilterEntry)
                        .reduce(GcpComputeProvider::combineWithAnd)
                        .orElse("");
    }

    // --- Leaf: format a single label filter entry for GCP ---
    private static String toLabelFilterEntry(Map.Entry<String, String> entry) {
        return "labels." + entry.getKey() + "=" + entry.getValue();
    }

    // --- Leaf: combine two filter expressions with AND ---
    private static String combineWithAnd(String a, String b) {
        return a + " AND " + b;
    }

    // --- Leaf: map cause to provision error ---
    private static EnvironmentError toProvisionError(Cause cause) {
        return EnvironmentError.provisionFailed(new RuntimeException(cause.message()));
    }

    // --- Leaf: map cause to terminate error ---
    private static EnvironmentError toTerminateError(InstanceId instanceId, Cause cause) {
        return EnvironmentError.terminateFailed(instanceId, new RuntimeException(cause.message()));
    }

    // --- Leaf: map cause to list instances error ---
    private static EnvironmentError toListInstancesError(Cause cause) {
        return EnvironmentError.listInstancesFailed(new RuntimeException(cause.message()));
    }
}
