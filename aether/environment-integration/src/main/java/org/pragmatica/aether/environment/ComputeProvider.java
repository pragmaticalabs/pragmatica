package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

/// SPI for provisioning and managing compute instances.
/// Implementations handle the actual instance lifecycle (Forge local nodes, cloud VMs, etc.).
/// CDM uses this interface to auto-heal when cluster size drops below target.
public interface ComputeProvider {
    Promise<InstanceInfo> provision(InstanceType instanceType);

    Promise<Unit> terminate(InstanceId instanceId);

    Promise<List<InstanceInfo>> listInstances();

    Promise<InstanceInfo> instanceStatus(InstanceId instanceId);
}
