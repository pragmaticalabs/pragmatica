package org.pragmatica.aether.environment.azure;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.cloud.azure.AzureClient;
import org.pragmatica.cloud.azure.api.CreateVmRequest;
import org.pragmatica.cloud.azure.api.CreateVmRequest.HardwareProfile;
import org.pragmatica.cloud.azure.api.CreateVmRequest.ImageReference;
import org.pragmatica.cloud.azure.api.CreateVmRequest.LinuxConfiguration;
import org.pragmatica.cloud.azure.api.CreateVmRequest.ManagedDisk;
import org.pragmatica.cloud.azure.api.CreateVmRequest.NetworkInterfaceRef;
import org.pragmatica.cloud.azure.api.CreateVmRequest.NetworkProfile;
import org.pragmatica.cloud.azure.api.CreateVmRequest.OsDisk;
import org.pragmatica.cloud.azure.api.CreateVmRequest.OsProfile;
import org.pragmatica.cloud.azure.api.CreateVmRequest.SshConfiguration;
import org.pragmatica.cloud.azure.api.CreateVmRequest.SshPublicKey;
import org.pragmatica.cloud.azure.api.CreateVmRequest.StorageProfile;
import org.pragmatica.cloud.azure.api.CreateVmRequest.VmRequestProperties;
import org.pragmatica.cloud.azure.api.ResourceRow;
import org.pragmatica.cloud.azure.api.VirtualMachine;
import org.pragmatica.cloud.azure.api.VirtualMachine.Status;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Azure Cloud implementation of the ComputeProvider SPI.
/// Delegates to AzureClient for VM lifecycle management and maps
/// Azure VM models to the environment integration domain types.
public record AzureComputeProvider(AzureClient client, AzureEnvironmentConfig config) implements ComputeProvider {
    private static final Logger log = LoggerFactory.getLogger(AzureComputeProvider.class);

    public static Result<AzureComputeProvider> azureComputeProvider(AzureClient client, AzureEnvironmentConfig config) {
        return success(new AzureComputeProvider(client, config));
    }

    @Override public Promise<InstanceInfo> provision(InstanceType instanceType) {
        return client.createVm(buildCreateRequest(List.of())).map(AzureComputeProvider::toInstanceInfo)
                              .mapError(AzureComputeProvider::toProvisionError);
    }

    @Override public Promise<InstanceInfo> provision(ProvisionSpec spec) {
        var zones = extractZones(spec.placement());
        return client.createVm(buildCreateRequest(zones)).map(AzureComputeProvider::toInstanceInfo)
                              .mapError(AzureComputeProvider::toProvisionError);
    }

    @Override public Promise<Unit> terminate(InstanceId instanceId) {
        return client.deleteVm(instanceId.value()).mapError(cause -> toTerminateError(instanceId, cause));
    }

    @Override public Promise<List<InstanceInfo>> listInstances() {
        return client.listVms().map(AzureComputeProvider::toInstanceInfoList)
                             .mapError(AzureComputeProvider::toListInstancesError);
    }

    @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        return client.getVm(instanceId.value()).map(AzureComputeProvider::toInstanceInfo)
                           .mapError(AzureComputeProvider::toProvisionError);
    }

    @Override public Promise<Unit> restart(InstanceId id) {
        return client.restartVm(id.value());
    }

    @Override public Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return client.updateTags(id.value(), tags).mapToUnit();
    }

    @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        return client.queryResources(buildTagFilterQuery(tagFilter)).map(AzureComputeProvider::toInstanceInfoListFromRows)
                                    .mapError(AzureComputeProvider::toListInstancesError);
    }

    private CreateVmRequest buildCreateRequest(List<String> zones) {
        var name = generateVmName();
        var imageRef = parseImageUrn(config.image());
        var hardware = new HardwareProfile(config.vmSize());
        var storage = new StorageProfile(imageRef, new OsDisk("FromImage", new ManagedDisk("Standard_LRS")));
        var sshKey = new SshPublicKey("/home/" + config.adminUsername() + "/.ssh/authorized_keys", config.sshPublicKey());
        var linux = new LinuxConfiguration(true, new SshConfiguration(List.of(sshKey)));
        var os = new OsProfile(name, config.adminUsername(), linux);
        var network = new NetworkProfile(List.of(new NetworkInterfaceRef(config.vnetSubnetId())));
        var properties = new VmRequestProperties(hardware, storage, os, network);
        var tags = Map.of("aether-managed", "true");
        return CreateVmRequest.createVmRequest(name,
                                               config.azureConfig().location(),
                                               tags,
                                               properties,
                                               zones);
    }

    private static List<String> extractZones(Option<PlacementHint> placement) {
        return placement.flatMap(AzureComputeProvider::zoneFromHint).map(zone -> List.of(zone))
                                .or(List.of());
    }

    private static Option<String> zoneFromHint(PlacementHint hint) {
        return switch (hint){
            case PlacementHint.ZoneHint zone -> Option.some(zone.zoneName());
            case PlacementHint.HostGroupHint ignored -> logUnsupported("HostGroupHint");
            case PlacementHint.AffinityHint ignored -> logUnsupported("AffinityHint");
            case PlacementHint.AntiAffinityHint ignored -> logUnsupported("AntiAffinityHint");
        };
    }

    private static Option<String> logUnsupported(String hintType) {
        log.debug("Azure provider ignoring {} — not yet supported", hintType);
        return Option.empty();
    }

    private static String generateVmName() {
        return "aether-" + UUID.randomUUID().toString()
                                          .substring(0, 8);
    }

    static ImageReference parseImageUrn(String urn) {
        var parts = urn.split(":");
        return new ImageReference(parts.length > 0
                                  ? parts[0]
                                  : "Canonical",
                                  parts.length > 1
                                  ? parts[1]
                                  : "0001-com-ubuntu-server-jammy",
                                  parts.length > 2
                                  ? parts[2]
                                  : "22_04-lts-gen2",
                                  parts.length > 3
                                  ? parts[3]
                                  : "latest");
    }

    static InstanceInfo toInstanceInfo(VirtualMachine vm) {
        return new InstanceInfo(new InstanceId(vm.name()),
                                mapStatus(vm),
                                List.of(),
                                InstanceType.ON_DEMAND,
                                safeTags(vm));
    }

    static InstanceInfo toInstanceInfoFromRow(ResourceRow row) {
        return new InstanceInfo(new InstanceId(row.name()),
                                InstanceStatus.RUNNING,
                                List.of(),
                                InstanceType.ON_DEMAND,
                                safeTags(row));
    }

    private static Map<String, String> safeTags(VirtualMachine vm) {
        return option(vm.tags()).or(Map.of());
    }

    private static Map<String, String> safeTags(ResourceRow row) {
        return option(row.tags()).or(Map.of());
    }

    static String buildTagFilterQuery(Map<String, String> tagFilter) {
        var baseQuery = "Resources | where type == \"microsoft.compute/virtualmachines\"";
        var tagClauses = tagFilter.entrySet().stream()
                                           .map(AzureComputeProvider::toTagClause)
                                           .collect(Collectors.joining(" "));
        return baseQuery + tagClauses;
    }

    private static String toTagClause(Map.Entry<String, String> entry) {
        return " | where tags[\"" + entry.getKey() + "\"] == \"" + entry.getValue() + "\"";
    }

    private static List<InstanceInfo> toInstanceInfoList(List<VirtualMachine> vms) {
        return vms.stream().map(AzureComputeProvider::toInstanceInfo)
                         .toList();
    }

    private static List<InstanceInfo> toInstanceInfoListFromRows(List<ResourceRow> rows) {
        return rows.stream().map(AzureComputeProvider::toInstanceInfoFromRow)
                          .toList();
    }

    static InstanceStatus mapStatus(VirtualMachine vm) {
        return option(vm.properties()).flatMap(AzureComputeProvider::extractPowerState)
                     .map(AzureComputeProvider::powerStateToStatus)
                     .or(provisioningStateToStatus(vm));
    }

    private static Option<String> extractPowerState(VirtualMachine.VmProperties props) {
        return option(props.instanceView()).flatMap(iv -> option(iv.statuses()))
                     .flatMap(AzureComputeProvider::findPowerStateCode);
    }

    private static Option<String> findPowerStateCode(List<Status> statuses) {
        return Option.from(statuses.stream().filter(AzureComputeProvider::isPowerState)
                                          .map(Status::code)
                                          .findFirst());
    }

    private static boolean isPowerState(Status status) {
        return option(status.code()).map(c -> c.startsWith("PowerState/")).or(false);
    }

    private static InstanceStatus powerStateToStatus(String code) {
        return switch (code){
            case "PowerState/running" -> InstanceStatus.RUNNING;
            case "PowerState/deallocated", "PowerState/stopped" -> InstanceStatus.STOPPING;
            case "PowerState/starting" -> InstanceStatus.PROVISIONING;
            case "PowerState/deallocating", "PowerState/stopping" -> InstanceStatus.STOPPING;
            default -> InstanceStatus.TERMINATED;
        };
    }

    private static InstanceStatus provisioningStateToStatus(VirtualMachine vm) {
        var state = option(vm.properties()).map(VirtualMachine.VmProperties::provisioningState).or("Unknown");
        return switch (state){
            case "Succeeded" -> InstanceStatus.RUNNING;
            case "Creating", "Updating" -> InstanceStatus.PROVISIONING;
            case "Deleting", "Failed" -> InstanceStatus.STOPPING;
            default -> InstanceStatus.TERMINATED;
        };
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
