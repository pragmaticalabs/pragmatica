package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.pragmatica.aether.environment.InstanceId.instanceId;
import static org.pragmatica.aether.environment.InstanceInfo.instanceInfo;

/// Hetzner Cloud implementation of the ComputeProvider SPI.
/// Delegates to HetznerClient for server lifecycle management and maps
/// Hetzner server models to the environment integration domain types.
public record HetznerComputeProvider(HetznerClient client,
                                     HetznerEnvironmentConfig config) implements ComputeProvider {

    /// Factory method for creating a HetznerComputeProvider.
    public static HetznerComputeProvider hetznerComputeProvider(HetznerClient client,
                                                                 HetznerEnvironmentConfig config) {
        return new HetznerComputeProvider(client, config);
    }

    @Override
    public Promise<InstanceInfo> provision(InstanceType instanceType) {
        return client.createServer(buildCreateRequest())
                     .map(HetznerComputeProvider::toInstanceInfo)
                     .mapError(cause -> EnvironmentError.provisionFailed(new RuntimeException(cause.message())));
    }

    @Override
    public Promise<Unit> terminate(InstanceId instanceId) {
        return client.deleteServer(parseServerId(instanceId))
                     .mapError(cause -> EnvironmentError.terminateFailed(instanceId,
                                                                         new RuntimeException(cause.message())));
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances() {
        return client.listServers()
                     .map(HetznerComputeProvider::toInstanceInfoList)
                     .mapError(cause -> EnvironmentError.listInstancesFailed(new RuntimeException(cause.message())));
    }

    @Override
    public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        return client.getServer(parseServerId(instanceId))
                     .map(HetznerComputeProvider::toInstanceInfo)
                     .mapError(cause -> EnvironmentError.provisionFailed(new RuntimeException(cause.message())));
    }

    // --- Leaf: build server creation request ---

    private CreateServerRequest buildCreateRequest() {
        var name = "aether-" + UUID.randomUUID().toString().substring(0, 8);
        return CreateServerRequest.createServerRequest(
            name, config.serverType(), config.image(),
            config.sshKeyIds(), config.networkIds(), config.firewallIds(),
            config.region(), config.userData(), true);
    }

    // --- Leaf: parse server ID from instance ID ---

    private static long parseServerId(InstanceId instanceId) {
        return Long.parseLong(instanceId.value());
    }

    // --- Leaf: map Hetzner server to InstanceInfo ---

    static InstanceInfo toInstanceInfo(Server server) {
        return instanceInfo(
            instanceId(String.valueOf(server.id())),
            mapStatus(server.status()),
            collectAddresses(server),
            InstanceType.ON_DEMAND);
    }

    // --- Leaf: map list of servers ---

    private static List<InstanceInfo> toInstanceInfoList(List<Server> servers) {
        return servers.stream()
                      .map(HetznerComputeProvider::toInstanceInfo)
                      .toList();
    }

    // --- Leaf: map Hetzner status string to InstanceStatus ---

    static InstanceStatus mapStatus(String hetznerStatus) {
        return switch (hetznerStatus) {
            case "initializing", "starting", "rebuilding", "migrating" -> InstanceStatus.PROVISIONING;
            case "running" -> InstanceStatus.RUNNING;
            case "stopping", "off", "deleting" -> InstanceStatus.STOPPING;
            default -> InstanceStatus.TERMINATED;
        };
    }

    // --- Leaf: collect all IP addresses from a server ---

    static List<String> collectAddresses(Server server) {
        var addresses = new ArrayList<String>();

        if (server.publicNet() != null && server.publicNet().ipv4() != null) {
            addresses.add(server.publicNet().ipv4().ip());
        }

        if (server.privateNet() != null) {
            server.privateNet().stream()
                  .map(Server.PrivateNet::ip)
                  .forEach(addresses::add);
        }

        return List.copyOf(addresses);
    }
}
