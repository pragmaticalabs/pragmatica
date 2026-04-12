package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.FloatingIpProvider;
import org.pragmatica.aether.environment.IpOwnership;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Set;


/// Hetzner Cloud floating IP provider. §11.1a
/// TODO: wire to Hetzner Floating IP API (POST /v1/floating_ips/{id}/actions/assign, GET /v1/floating_ips/{id})
///       when HetznerClient gains floating IP endpoints.
public record HetznerFloatingIpProvider(HetznerClient client) implements FloatingIpProvider {
    private static final EnvironmentError NOT_YET_IMPLEMENTED =
        EnvironmentError.operationNotSupported("Hetzner FloatingIpProvider coming soon");

    public static HetznerFloatingIpProvider hetznerFloatingIpProvider(HetznerClient client) {
        return new HetznerFloatingIpProvider(client);
    }

    @Override
    public Promise<Unit> attach(String floatingIp, String targetNodeId) {
        return NOT_YET_IMPLEMENTED.promise();
    }

    @Override
    public Promise<IpOwnership> verify(String floatingIp) {
        return NOT_YET_IMPLEMENTED.promise();
    }

    @Override
    public Promise<Set<String>> compatibleZones(String floatingIp) {
        return NOT_YET_IMPLEMENTED.promise();
    }
}
