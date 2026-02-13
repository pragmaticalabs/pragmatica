package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// SPI for provisioning new cluster nodes.
/// Implementations handle the actual node creation (Forge local nodes, cloud VMs, etc.).
/// CDM uses this interface to auto-heal when cluster size drops below target.
public interface ComputeProvider {
    Promise<Unit> provision(InstanceType instanceType);
}
