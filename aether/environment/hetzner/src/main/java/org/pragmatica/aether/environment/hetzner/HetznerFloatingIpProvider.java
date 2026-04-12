package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.FloatingIpProvider;
import org.pragmatica.aether.environment.IpOwnership;
import org.pragmatica.cloud.hetzner.api.FloatingIp;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.environment.IpOwnership.ipOwnership;
import static org.pragmatica.lang.Option.option;


/// Hetzner Cloud floating IP provider. Manages floating IP attachment,
/// ownership verification, and zone compatibility via the Hetzner Cloud API.
public record HetznerFloatingIpProvider(HetznerClient client) implements FloatingIpProvider {
    private static final Logger log = LoggerFactory.getLogger(HetznerFloatingIpProvider.class);

    private static final EnvironmentError FLOATING_IP_NOT_FOUND = EnvironmentError.operationNotSupported("Floating IP not found in account");

    public static HetznerFloatingIpProvider hetznerFloatingIpProvider(HetznerClient client) {
        return new HetznerFloatingIpProvider(client);
    }

    @Override public Promise<Unit> attach(String floatingIp, String targetNodeId) {
        return findFloatingIpByAddress(floatingIp).flatMap(fip -> assignToServer(fip.id(), targetNodeId));
    }

    @Override public Promise<IpOwnership> verify(String floatingIp) {
        return client.listFloatingIps().map(ips -> findMatchingIp(ips, floatingIp));
    }

    @Override public Promise<Set<String>> compatibleZones(String floatingIp) {
        return findFloatingIpByAddress(floatingIp).map(HetznerFloatingIpProvider::extractHomeLocation);
    }

    private Promise<FloatingIp> findFloatingIpByAddress(String floatingIp) {
        return client.listFloatingIps().flatMap(ips -> matchByAddress(ips, floatingIp));
    }

    private static Promise<FloatingIp> matchByAddress(List<FloatingIp> ips, String address) {
        return ips.stream().filter(fip -> address.equals(fip.ip()))
                         .findFirst()
                         .map(Promise::success)
                         .orElseGet(() -> logNotFound(address));
    }

    private static Promise<FloatingIp> logNotFound(String address) {
        log.warn("Floating IP {} not found in Hetzner account", address);
        return FLOATING_IP_NOT_FOUND.promise();
    }

    private Promise<Unit> assignToServer(long floatingIpId, String targetNodeId) {
        return Number.parseLong(targetNodeId).async()
                               .flatMap(serverId -> doAssign(floatingIpId, serverId));
    }

    private Promise<Unit> doAssign(long floatingIpId, long serverId) {
        return client.assignFloatingIp(floatingIpId, serverId);
    }

    private static IpOwnership findMatchingIp(List<FloatingIp> ips, String address) {
        return ips.stream().filter(fip -> address.equals(fip.ip()))
                         .findFirst()
                         .map(HetznerFloatingIpProvider::toOwnership)
                         .orElseGet(HetznerFloatingIpProvider::notOwned);
    }

    private static IpOwnership toOwnership(FloatingIp fip) {
        return ipOwnership(true, serverAttachment(fip));
    }

    private static String serverAttachment(FloatingIp fip) {
        return option(fip.server()).map(String::valueOf).or("");
    }

    private static IpOwnership notOwned() {
        return ipOwnership(false, "");
    }

    private static Set<String> extractHomeLocation(FloatingIp fip) {
        return option(fip.homeLocation()).map(FloatingIp.Location::name)
                     .map(name -> Set.of(name))
                     .or(Set.of());
    }
}
