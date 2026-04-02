package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.PeerInfo;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.concurrent.StoppableThread;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/// Hetzner Cloud implementation of the DiscoveryProvider SPI.
/// Discovers peers by querying servers with a specific `aether-cluster` label.
/// Watches for peer changes by polling at a configurable interval.
/// NOTE: deregisterSelf clears ALL labels on the server, not just aether-* labels.
public final class HetznerDiscoveryProvider implements DiscoveryProvider {
    private static final Logger log = LoggerFactory.getLogger(HetznerDiscoveryProvider.class);

    private static final int DEFAULT_PORT = 9100;
    private static final String LABEL_CLUSTER = "aether-cluster";
    private static final String LABEL_PORT = "aether-port";
    private static final String LABEL_ROLE = "aether-role";
    private static final String DEFAULT_ROLE = "core";

    private static final EnvironmentError NO_SELF_SERVER_ID = EnvironmentError.operationNotSupported("registerSelf/deregisterSelf requires selfServerId");

    private final HetznerClient client;
    private final String clusterName;
    private final Option<Long> selfServerId;
    private final long pollIntervalMs;
    private final StoppableThread watchThread = StoppableThread.stoppableThread();

    private HetznerDiscoveryProvider(HetznerClient client,
                                     String clusterName,
                                     Option<Long> selfServerId,
                                     long pollIntervalMs) {
        this.client = client;
        this.clusterName = clusterName;
        this.selfServerId = selfServerId;
        this.pollIntervalMs = pollIntervalMs;
    }

    /// Factory method for creating a HetznerDiscoveryProvider from config.
    public static HetznerDiscoveryProvider hetznerDiscoveryProvider(HetznerClient client,
                                                                    HetznerEnvironmentConfig config) {
        return new HetznerDiscoveryProvider(client,
                                            config.clusterName().or("default"),
                                            config.selfServerId(),
                                            config.discoveryPollIntervalMs());
    }

    @Override public Promise<List<PeerInfo>> discoverPeers() {
        return client.listServers(clusterLabelSelector()).map(HetznerDiscoveryProvider::toPeerInfoList)
                                 .mapError(HetznerDiscoveryProvider::toDiscoveryError);
    }

    @Override public Promise<Unit> watchPeers(Consumer<List<PeerInfo>> onChange) {
        var thread = Thread.ofVirtual().name("hetzner-discovery-watcher")
                                     .start(() -> pollLoop(onChange));
        watchThread.set(thread);
        return Promise.success(unit());
    }

    @Override public Promise<Unit> stopWatching() {
        interruptWatchThread();
        return Promise.success(unit());
    }

    @Override public Promise<Unit> registerSelf(PeerInfo self) {
        return selfServerId.map(id -> applyRegistrationLabels(id, self)).or(NO_SELF_SERVER_ID.promise());
    }

    @Override public Promise<Unit> deregisterSelf() {
        return selfServerId.map(this::clearLabels).or(NO_SELF_SERVER_ID.promise());
    }

    // --- Leaf: build label selector for cluster ---
    private String clusterLabelSelector() {
        return LABEL_CLUSTER + "=" + clusterName;
    }

    // --- Leaf: apply registration labels to self server ---
    private Promise<Unit> applyRegistrationLabels(long serverId, PeerInfo self) {
        return client.updateServerLabels(serverId, buildSelfLabels(self));
    }

    // --- Leaf: clear all labels from self server ---
    private Promise<Unit> clearLabels(long serverId) {
        return client.updateServerLabels(serverId, Map.of());
    }

    // --- Leaf: build label map for self-registration ---
    private Map<String, String> buildSelfLabels(PeerInfo self) {
        return Map.of(LABEL_CLUSTER,
                      clusterName,
                      LABEL_PORT,
                      String.valueOf(self.port()),
                      LABEL_ROLE,
                      self.metadata().getOrDefault("role", DEFAULT_ROLE));
    }

    // --- Leaf: map server list to peer info list ---
    private static List<PeerInfo> toPeerInfoList(List<Server> servers) {
        return servers.stream().map(HetznerDiscoveryProvider::serverToPeerInfo)
                             .toList();
    }

    // --- Leaf: extract PeerInfo from a server ---
    private static PeerInfo serverToPeerInfo(Server server) {
        return new PeerInfo(extractHost(server), extractPort(server), extractMetadata(server));
    }

    // --- Leaf: extract host, preferring private IP over public ---
    private static String extractHost(Server server) {
        return firstPrivateIp(server).or(() -> publicIpv4(server).or("0.0.0.0"));
    }

    // --- Leaf: extract first private IP ---
    private static Option<String> firstPrivateIp(Server server) {
        return option(server.privateNet()).filter(nets -> !nets.isEmpty())
                     .map(HetznerDiscoveryProvider::firstIp);
    }

    // --- Leaf: get first IP from private net list ---
    private static String firstIp(List<Server.PrivateNet> nets) {
        return nets.getFirst().ip();
    }

    // --- Leaf: extract public IPv4 address ---
    private static Option<String> publicIpv4(Server server) {
        return option(server.publicNet()).flatMap(net -> option(net.ipv4()))
                     .map(Server.Ipv4::ip);
    }

    // --- Leaf: extract port from aether-port label, default 9100 ---
    private static int extractPort(Server server) {
        return option(server.labels()).flatMap(labels -> option(labels.get(LABEL_PORT)))
                     .map(HetznerDiscoveryProvider::parsePortOrDefault)
                     .or(DEFAULT_PORT);
    }

    // --- Leaf: parse port string to int, falling back to default ---
    private static int parsePortOrDefault(String portStr) {
        return Number.parseInt(portStr).or(DEFAULT_PORT);
    }

    // --- Leaf: extract metadata from server labels ---
    private static Map<String, String> extractMetadata(Server server) {
        return option(server.labels()).or(Map.of());
    }

    // --- Leaf: poll loop for watching peers ---
    private void pollLoop(Consumer<List<PeerInfo>> onChange) {
        var previousPeers = new AtomicReference<Set<String>>(Set.of());
        while ( !Thread.currentThread().isInterrupted()) {
            pollOnce(onChange, previousPeers);
            sleepOrExit();
        }
    }

    // --- Leaf: execute a single poll cycle ---
    private void pollOnce(Consumer<List<PeerInfo>> onChange, AtomicReference<Set<String>> previousPeers) {
        discoverPeers().await()
                     .onFailure(cause -> log.warn("Discovery poll failed: {}",
                                                  cause.message()))
                     .onSuccess(peers -> notifyIfChanged(peers, onChange, previousPeers));
    }

    // --- Leaf: notify onChange if peer set has changed ---
    private static void notifyIfChanged(List<PeerInfo> peers,
                                        Consumer<List<PeerInfo>> onChange,
                                        AtomicReference<Set<String>> previousPeers) {
        var currentKeys = toPeerKeys(peers);
        if ( !currentKeys.equals(previousPeers.get())) {
            previousPeers.set(currentKeys);
            onChange.accept(peers);
        }
    }

    // --- Leaf: convert peer list to set of host:port keys for comparison ---
    private static Set<String> toPeerKeys(List<PeerInfo> peers) {
        return peers.stream().map(HetznerDiscoveryProvider::peerKey)
                           .collect(Collectors.toSet());
    }

    // --- Leaf: format peer key for comparison ---
    private static String peerKey(PeerInfo peer) {
        return peer.host() + ":" + peer.port();
    }

    // --- Leaf: sleep for poll interval, exit on interrupt ---
    private void sleepOrExit() {
        try {
            Thread.sleep(pollIntervalMs);
        }


























        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Leaf: interrupt and clear the watch thread ---
    private void interruptWatchThread() {
        watchThread.stop();
    }

    // --- Leaf: map cause to discovery error ---
    private static EnvironmentError toDiscoveryError(Cause cause) {
        return EnvironmentError.discoveryFailed("peer discovery", new RuntimeException(cause.message()));
    }
}
