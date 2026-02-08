package org.pragmatica.aether.provider;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ServiceLoader;

/**
 * SPI for provisioning new cluster nodes.
 * Implementations handle the actual node creation (Forge local nodes, cloud VMs, etc.).
 * CDM uses this interface to auto-heal when cluster size drops below target.
 */
public interface NodeProvider {
    Option<NodeProvider> SPI = Option.from(
        ServiceLoader.load(NodeProvider.class).findFirst());

    Promise<Unit> provision(InstanceType instanceType);
}
