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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;

/// Hetzner Cloud implementation of the ComputeProvider SPI.
/// Delegates to HetznerClient for server lifecycle management and maps
/// Hetzner server models to the environment integration domain types.
public record HetznerComputeProvider(HetznerClient client,
                                     HetznerEnvironmentConfig config) implements ComputeProvider {
    /// Factory method for creating a HetznerComputeProvider.
    public static Result<HetznerComputeProvider> hetznerComputeProvider(HetznerClient client,
                                                                        HetznerEnvironmentConfig config) {
        return success(new HetznerComputeProvider(client, config));
    }

    @Override
    public Promise<InstanceInfo> provision(InstanceType instanceType) {
        return client.createServer(buildCreateRequest())
                     .map(HetznerComputeProvider::toInstanceInfo)
                     .mapError(HetznerComputeProvider::toProvisionError);
    }

    @Override
    public Promise<Unit> terminate(InstanceId instanceId) {
        var serverId = parseServerId(instanceId);
        var result = serverId.async()
                             .flatMap(this::destroyServer);
        return mapTerminateError(result, instanceId);
    }

    // --- Leaf: map terminate errors with instance context ---
    private static Promise<Unit> mapTerminateError(Promise<Unit> result, InstanceId instanceId) {
        return result.mapError(cause -> toTerminateError(instanceId, cause));
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances() {
        return client.listServers()
                     .map(HetznerComputeProvider::toInstanceInfoList)
                     .mapError(HetznerComputeProvider::toListInstancesError);
    }

    @Override
    public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        var serverId = parseServerId(instanceId);
        return serverId.async()
                       .flatMap(this::serverById)
                       .map(HetznerComputeProvider::toInstanceInfo)
                       .mapError(HetznerComputeProvider::toProvisionError);
    }

    // --- Leaf: destroy a server by ID ---
    private Promise<Unit> destroyServer(long serverId) {
        return client.deleteServer(serverId);
    }

    // --- Leaf: look up a server by ID ---
    private Promise<Server> serverById(long serverId) {
        return client.getServer(serverId);
    }

    // --- Leaf: build server creation request ---
    private CreateServerRequest buildCreateRequest() {
        var name = generateServerName();
        var serverType = config.serverType();
        var image = config.image();
        var sshKeyIds = config.sshKeyIds();
        var networkIds = config.networkIds();
        var firewallIds = config.firewallIds();
        var region = config.region();
        var userData = config.userData();
        return CreateServerRequest.createServerRequest(name,
                                                       serverType,
                                                       image,
                                                       sshKeyIds,
                                                       networkIds,
                                                       firewallIds,
                                                       region,
                                                       userData,
                                                       true);
    }

    // --- Leaf: generate a unique server name ---
    private static String generateServerName() {
        return "aether-" + UUID.randomUUID()
                              .toString()
                              .substring(0, 8);
    }

    // --- Leaf: parse server ID from instance ID ---
    private static Result<Long> parseServerId(InstanceId instanceId) {
        return Number.parseLong(instanceId.value());
    }

    // --- Leaf: map Hetzner server to InstanceInfo ---
    static InstanceInfo toInstanceInfo(Server server) {
        return new InstanceInfo(new InstanceId(String.valueOf(server.id())),
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
        var publicIp = publicIpv4(server);
        var privateIps = privateIps(server);
        return Stream.concat(publicIp.stream(),
                             privateIps.stream())
                     .toList();
    }

    // --- Leaf: resolve public IPv4 address ---
    private static Option<String> publicIpv4(Server server) {
        return option(server.publicNet()).flatMap(HetznerComputeProvider::ipv4FromPublicNet)
                     .map(Server.Ipv4::ip);
    }

    // --- Leaf: resolve ipv4 from public net ---
    private static Option<Server.Ipv4> ipv4FromPublicNet(Server.PublicNet net) {
        return option(net.ipv4());
    }

    // --- Leaf: resolve private network IPs ---
    private static List<String> privateIps(Server server) {
        return option(server.privateNet()).map(HetznerComputeProvider::toPrivateIpList)
                     .or(List.of());
    }

    // --- Leaf: map private net list to IPs ---
    private static List<String> toPrivateIpList(List<Server.PrivateNet> nets) {
        return nets.stream()
                   .map(Server.PrivateNet::ip)
                   .toList();
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
