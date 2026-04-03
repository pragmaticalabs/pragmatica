package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.function.Consumer;


/// SPI for discovering peer nodes in the cluster environment.
/// Implementations integrate with environment-specific service discovery mechanisms
/// (e.g., Hetzner labels, AWS Cloud Map, Consul).
public interface DiscoveryProvider {
    Promise<List<PeerInfo>> discoverPeers();
    Promise<Unit> watchPeers(Consumer<List<PeerInfo>> onChange);
    Promise<Unit> stopWatching();
    Promise<Unit> registerSelf(PeerInfo self);
    Promise<Unit> deregisterSelf();
}
