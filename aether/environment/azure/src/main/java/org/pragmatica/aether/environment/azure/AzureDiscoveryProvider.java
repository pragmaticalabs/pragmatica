package org.pragmatica.aether.environment.azure;

import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.PeerInfo;
import org.pragmatica.cloud.azure.AzureClient;
import org.pragmatica.cloud.azure.api.ResourceRow;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.concurrent.StoppableThread;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

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


/// Azure Cloud implementation of the DiscoveryProvider SPI.
/// Discovers peers by querying VMs with a specific `aether-cluster` tag via Azure Resource Graph.
/// Watches for peer changes by polling at a configurable interval.
public final class AzureDiscoveryProvider implements DiscoveryProvider {
    private static final Logger log = LoggerFactory.getLogger(AzureDiscoveryProvider.class);

    private static final int DEFAULT_PORT = 9100;

    private static final String TAG_CLUSTER = "aether-cluster";

    private static final String TAG_PORT = "aether-port";

    private static final String TAG_ROLE = "aether-role";

    private static final String DEFAULT_ROLE = "core";

    private static final EnvironmentError NO_SELF_VM_NAME = EnvironmentError.operationNotSupported("registerSelf/deregisterSelf requires selfVmName");

    private final AzureClient client;
    private final String clusterName;
    private final Option<String> selfVmName;
    private final long pollIntervalMs;

    private final StoppableThread watchThread = StoppableThread.stoppableThread();

    private AzureDiscoveryProvider(AzureClient client,
                                   String clusterName,
                                   Option<String> selfVmName,
                                   long pollIntervalMs) {
        this.client = client;
        this.clusterName = clusterName;
        this.selfVmName = selfVmName;
        this.pollIntervalMs = pollIntervalMs;
    }

    public static AzureDiscoveryProvider azureDiscoveryProvider(AzureClient client, AzureEnvironmentConfig config) {
        return new AzureDiscoveryProvider(client,
                                          config.clusterName().or("default"),
                                          config.selfVmName(),
                                          config.discoveryPollIntervalMs());
    }

    @Override public Promise<List<PeerInfo>> discoverPeers() {
        return client.queryResources(clusterQuery()).map(AzureDiscoveryProvider::toPeerInfoList)
                                    .mapError(AzureDiscoveryProvider::toDiscoveryError);
    }

    @Override public Promise<Unit> watchPeers(Consumer<List<PeerInfo>> onChange) {
        var thread = Thread.ofVirtual().name("azure-discovery-watcher")
                                     .start(() -> pollLoop(onChange));
        watchThread.set(thread);
        return Promise.success(unit());
    }

    @Override public Promise<Unit> stopWatching() {
        interruptWatchThread();
        return Promise.success(unit());
    }

    @Override public Promise<Unit> registerSelf(PeerInfo self) {
        return selfVmName.map(name -> applyRegistrationTags(name, self)).or(NO_SELF_VM_NAME.promise());
    }

    @Override public Promise<Unit> deregisterSelf() {
        return selfVmName.map(this::clearTags).or(NO_SELF_VM_NAME.promise());
    }

    String clusterQuery() {
        return "Resources | where type == \"microsoft.compute/virtualmachines\"" + " | where tags[\"" + TAG_CLUSTER + "\"] == \"" + clusterName + "\"";
    }

    private Promise<Unit> applyRegistrationTags(String vmName, PeerInfo self) {
        return client.updateTags(vmName, buildSelfTags(self)).mapToUnit();
    }

    private Promise<Unit> clearTags(String vmName) {
        return client.updateTags(vmName, Map.of()).mapToUnit();
    }

    private Map<String, String> buildSelfTags(PeerInfo self) {
        return Map.of(TAG_CLUSTER,
                      clusterName,
                      TAG_PORT,
                      String.valueOf(self.port()),
                      TAG_ROLE,
                      self.metadata().getOrDefault("role", DEFAULT_ROLE));
    }

    private static List<PeerInfo> toPeerInfoList(List<ResourceRow> rows) {
        return rows.stream().map(AzureDiscoveryProvider::rowToPeerInfo)
                          .toList();
    }

    private static PeerInfo rowToPeerInfo(ResourceRow row) {
        return new PeerInfo(extractHost(row), extractPort(row), extractMetadata(row));
    }

    private static String extractHost(ResourceRow row) {
        return row.name();
    }

    private static int extractPort(ResourceRow row) {
        return option(row.tags()).flatMap(tags -> option(tags.get(TAG_PORT)))
                     .map(AzureDiscoveryProvider::parsePortOrDefault)
                     .or(DEFAULT_PORT);
    }

    private static int parsePortOrDefault(String portStr) {
        return org.pragmatica.lang.parse.Number.parseInt(portStr).or(DEFAULT_PORT);
    }

    private static Map<String, String> extractMetadata(ResourceRow row) {
        return option(row.tags()).or(Map.of());
    }

    private void pollLoop(Consumer<List<PeerInfo>> onChange) {
        var previousPeers = new AtomicReference<Set<String>>(Set.of());
        while (!Thread.currentThread().isInterrupted()) {
            pollOnce(onChange, previousPeers);
            sleepOrExit();
        }
    }

    private void pollOnce(Consumer<List<PeerInfo>> onChange, AtomicReference<Set<String>> previousPeers) {
        discoverPeers().await()
                     .onFailure(cause -> log.warn("Discovery poll failed: {}",
                                                  cause.message()))
                     .onSuccess(peers -> notifyIfChanged(peers, onChange, previousPeers));
    }

    private static void notifyIfChanged(List<PeerInfo> peers,
                                        Consumer<List<PeerInfo>> onChange,
                                        AtomicReference<Set<String>> previousPeers) {
        var currentKeys = toPeerKeys(peers);
        if (!currentKeys.equals(previousPeers.get())) {
            previousPeers.set(currentKeys);
            onChange.accept(peers);
        }
    }

    private static Set<String> toPeerKeys(List<PeerInfo> peers) {
        return peers.stream().map(AzureDiscoveryProvider::peerKey)
                           .collect(Collectors.toSet());
    }

    private static String peerKey(PeerInfo peer) {
        return peer.host() + ":" + peer.port();
    }

    private void sleepOrExit() {
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void interruptWatchThread() {
        watchThread.stop();
    }

    private static EnvironmentError toDiscoveryError(Cause cause) {
        return EnvironmentError.discoveryFailed("peer discovery", new RuntimeException(cause.message()));
    }
}
