package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Set;


/// Manages floating IPs for elected load balancers. §11.1a
public interface FloatingIpProvider {
    Promise<Unit> attach(String floatingIp, String targetNodeId);
    Promise<IpOwnership> verify(String floatingIp);
    Promise<Set<String>> compatibleZones(String floatingIp);
}
