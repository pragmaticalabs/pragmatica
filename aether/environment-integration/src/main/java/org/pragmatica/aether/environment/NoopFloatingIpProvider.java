package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Set;

import static org.pragmatica.aether.environment.IpOwnership.ipOwnership;


/// NOOP floating IP provider for local development. §11.1a
/// Always succeeds, reports ownership as local, and returns a single "local" zone.
public record NoopFloatingIpProvider() implements FloatingIpProvider {
    private static final IpOwnership LOCAL_OWNERSHIP = ipOwnership(true, "localhost");
    private static final Set<String> LOCAL_ZONES = Set.of("local");

    public static NoopFloatingIpProvider noopFloatingIpProvider() {
        return new NoopFloatingIpProvider();
    }

    @Override
    public Promise<Unit> attach(String floatingIp, String targetNodeId) {
        return Promise.unitPromise();
    }

    @Override
    public Promise<IpOwnership> verify(String floatingIp) {
        return Promise.success(LOCAL_OWNERSHIP);
    }

    @Override
    public Promise<Set<String>> compatibleZones(String floatingIp) {
        return Promise.success(LOCAL_ZONES);
    }
}
