package org.pragmatica.aether.environment.gcp;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionSpec;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// GCP Cloud implementation of the ComputeProvider SPI.
/// Delegates to GcpClient for instance lifecycle management and maps
/// GCP instance models to the environment integration domain types.
public record GcpComputeProvider(GcpClient client, GcpEnvironmentConfig config) implements ComputeProvider {
    private static final Logger log = LoggerFactory.getLogger(GcpComputeProvider.class);

    private static final String MANAGED_LABEL_KEY = "aether-managed";

    private static final String MANAGED_LABEL_VALUE = "true";

    public static Result<GcpComputeProvider> gcpComputeProvider(GcpClient client, GcpEnvironmentConfig config) {
        return success(new GcpComputeProvider(client, config));
    }

    @Override public Promise<InstanceInfo> provision(InstanceType instanceType) {
        return client.insertInstance(buildInsertRequest(Option.empty())).map(GcpComputeProvider::toInstanceInfo)
                                    .mapError(GcpComputeProvider::toProvisionError);
    }

    @Override public Promise<InstanceInfo> provision(ProvisionSpec spec) {
        var zone = extractZone(spec.placement());
        return client.insertInstance(buildInsertRequest(zone)).map(GcpComputeProvider::toInstanceInfo)
                                    .mapError(GcpComputeProvider::toProvisionError);
    }

    @Override public Promise<Unit> terminate(InstanceId instanceId) {
        return client.deleteInstance(instanceId.value()).mapError(cause -> toTerminateError(instanceId, cause));
    }

    @Override public Promise<List<InstanceInfo>> listInstances() {
        return client.listInstances().map(GcpComputeProvider::toInstanceInfoList)
                                   .mapError(GcpComputeProvider::toListInstancesError);
    }

    @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return client.listInstances(toLabelFilter(tagFilter)).map(GcpComputeProvider::toInstanceInfoList)
                                   .mapError(GcpComputeProvider::toListInstancesError);
    }

    @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        return client.getInstance(instanceId.value()).map(GcpComputeProvider::toInstanceInfo)
                                 .mapError(GcpComputeProvider::toProvisionError);
    }

    @Override public Promise<Unit> restart(InstanceId id) {
        return client.resetInstance(id.value()).mapToUnit();
    }

    @Override public Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return client.getInstance(id.value()).flatMap(instance -> setLabelsOnInstance(id.value(), instance, tags));
    }

    private Promise<Unit> setLabelsOnInstance(String instanceName, Instance instance, Map<String, String> tags) {
        var fingerprint = extractLabelFingerprint(instance);
        return client.setLabels(instanceName, new SetLabelsRequest(tags, fingerprint)).mapToUnit();
    }

    private static String extractLabelFingerprint(Instance instance) {
        return "";
    }

    private InsertInstanceRequest buildInsertRequest(Option<String> zoneOverride) {
        var name = generateInstanceName();
        var machineType = config.machineType();
        var disk = buildBootDisk();
        var networkInterface = buildNetworkInterface();
        var labels = Map.of(MANAGED_LABEL_KEY, MANAGED_LABEL_VALUE);
        var metadata = buildMetadata();
        return new InsertInstanceRequest(name, machineType, List.of(disk), List.of(networkInterface), labels, metadata, zoneOverride);
    }

    private static Option<String> extractZone(Option<PlacementHint> placement) {
        return placement.flatMap(GcpComputeProvider::zoneFromHint);
    }

    private static Option<String> zoneFromHint(PlacementHint hint) {
        return switch (hint) {
            case PlacementHint.ZoneHint zone -> Option.some(zone.zoneName());
            case PlacementHint.HostGroupHint ignored -> logUnsupported("HostGroupHint");
            case PlacementHint.AffinityHint ignored -> logUnsupported("AffinityHint");
            case PlacementHint.AntiAffinityHint ignored -> logUnsupported("AntiAffinityHint");
        };
    }

    private static Option<String> logUnsupported(String hintType) {
        log.debug("GCP provider ignoring {} — not yet supported", hintType);
        return Option.empty();
    }

    private Disk buildBootDisk() {
        return new Disk(true, true, new InitializeParams(config.sourceImage(), 20, "pd-standard"));
    }

    private NetworkInterfaceConfig buildNetworkInterface() {
        return new NetworkInterfaceConfig(config.network(),
                                          config.subnetwork(),
                                          List.of(new AccessConfig("External NAT", "ONE_TO_ONE_NAT")));
    }

    private Metadata buildMetadata() {
        return new Metadata(List.of(new MetadataItem("startup-script", config.userData())));
    }

    private static String generateInstanceName() {
        return "aether-" + UUID.randomUUID().toString()
                                          .substring(0, 8);
    }

    static InstanceInfo toInstanceInfo(Instance instance) {
        return new InstanceInfo(new InstanceId(instance.name()),
                                mapStatus(instance.status()),
                                collectAddresses(instance),
                                InstanceType.ON_DEMAND,
                                safeLabels(instance));
    }

    private static Map<String, String> safeLabels(Instance instance) {
        return option(instance.labels()).or(Map.of());
    }

    private static List<InstanceInfo> toInstanceInfoList(List<Instance> instances) {
        return instances.stream().map(GcpComputeProvider::toInstanceInfo)
                               .toList();
    }

    static InstanceStatus mapStatus(String gcpStatus) {
        return switch (gcpStatus){
            case "PROVISIONING", "STAGING" -> InstanceStatus.PROVISIONING;
            case "RUNNING" -> InstanceStatus.RUNNING;
            case "STOPPING", "TERMINATED", "SUSPENDED", "SUSPENDING" -> InstanceStatus.STOPPING;
            default -> InstanceStatus.TERMINATED;
        };
    }

    static List<String> collectAddresses(Instance instance) {
        return option(instance.networkInterfaces()).map(GcpComputeProvider::extractIpsFromInterfaces).or(List.of());
    }

    private static List<String> extractIpsFromInterfaces(List<Instance.NetworkInterface> interfaces) {
        return interfaces.stream().map(Instance.NetworkInterface::networkIP)
                                .toList();
    }

    static String toLabelFilter(Map<String, String> tagFilter) {
        return tagFilter.entrySet().stream()
                                 .map(GcpComputeProvider::toLabelFilterEntry)
                                 .reduce(GcpComputeProvider::combineWithAnd)
                                 .orElse("");
    }

    private static String toLabelFilterEntry(Map.Entry<String, String> entry) {
        return "labels." + entry.getKey() + "=" + entry.getValue();
    }

    private static String combineWithAnd(String a, String b) {
        return a + " AND " + b;
    }

    private static EnvironmentError toProvisionError(Cause cause) {
        return EnvironmentError.provisionFailed(new RuntimeException(cause.message()));
    }

    private static EnvironmentError toTerminateError(InstanceId instanceId, Cause cause) {
        return EnvironmentError.terminateFailed(instanceId, new RuntimeException(cause.message()));
    }

    private static EnvironmentError toListInstancesError(Cause cause) {
        return EnvironmentError.listInstancesFailed(new RuntimeException(cause.message()));
    }
}
