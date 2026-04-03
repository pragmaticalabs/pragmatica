package org.pragmatica.aether.environment.gcp;

import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.PeerInfo;
import org.pragmatica.cloud.gcp.GcpClient;
import org.pragmatica.cloud.gcp.api.Instance;
import org.pragmatica.cloud.gcp.api.SetLabelsRequest;
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


/// GCP Cloud implementation of the DiscoveryProvider SPI.
/// Discovers peers by querying instances with a specific `aether-cluster` label.
/// Watches for peer changes by polling at a configurable interval.
public final class GcpDiscoveryProvider implements DiscoveryProvider {
    private static final Logger log = LoggerFactory.getLogger(GcpDiscoveryProvider.class);

    private static final int DEFAULT_PORT = 9100;

    private static final String LABEL_CLUSTER = "aether-cluster";

    private static final String LABEL_PORT = "aether-port";

    private static final String LABEL_ROLE = "aether-role";

    private static final String DEFAULT_ROLE = "core";

    private static final EnvironmentError NO_SELF_INSTANCE = EnvironmentError.operationNotSupported("registerSelf/deregisterSelf requires selfInstanceName");

    private final GcpClient client;
    private final String clusterName;
    private final Option<String> selfInstanceName;
    private final long pollIntervalMs;

    private final StoppableThread watchThread = StoppableThread.stoppableThread();

    private GcpDiscoveryProvider(GcpClient client,
                                 String clusterName,
                                 Option<String> selfInstanceName,
                                 long pollIntervalMs) {
        this.client = client;
        this.clusterName = clusterName;
        this.selfInstanceName = selfInstanceName;
        this.pollIntervalMs = pollIntervalMs;
    }

    public static GcpDiscoveryProvider gcpDiscoveryProvider(GcpClient client, GcpEnvironmentConfig config) {
        return new GcpDiscoveryProvider(client,
                                        config.clusterName().or("default"),
                                        config.selfInstanceName(),
                                        config.discoveryPollIntervalMs());
    }

    @Override public Promise<List<PeerInfo>> discoverPeers() {
        return client.listInstances(clusterLabelFilter()).map(GcpDiscoveryProvider::toPeerInfoList)
                                   .mapError(GcpDiscoveryProvider::toDiscoveryError);
    }

    @Override public Promise<Unit> watchPeers(Consumer<List<PeerInfo>> onChange) {
        var thread = Thread.ofVirtual().name("gcp-discovery-watcher")
                                     .start(() -> pollLoop(onChange));
        watchThread.set(thread);
        return Promise.success(unit());
    }

    @Override public Promise<Unit> stopWatching() {
        interruptWatchThread();
        return Promise.success(unit());
    }

    @Override public Promise<Unit> registerSelf(PeerInfo self) {
        return selfInstanceName.map(name -> applyRegistrationLabels(name, self)).or(NO_SELF_INSTANCE.promise());
    }

    @Override public Promise<Unit> deregisterSelf() {
        return selfInstanceName.map(this::clearLabels).or(NO_SELF_INSTANCE.promise());
    }

    private String clusterLabelFilter() {
        return "labels." + LABEL_CLUSTER + "=" + clusterName;
    }

    private Promise<Unit> applyRegistrationLabels(String instanceName, PeerInfo self) {
        return client.getInstance(instanceName)
                                 .flatMap(instance -> setLabelsOnSelf(instanceName,
                                                                      instance,
                                                                      buildSelfLabels(self)));
    }

    private Promise<Unit> setLabelsOnSelf(String instanceName, Instance instance, Map<String, String> labels) {
        return client.setLabels(instanceName, new SetLabelsRequest(labels, "")).mapToUnit();
    }

    private Promise<Unit> clearLabels(String instanceName) {
        return client.getInstance(instanceName).flatMap(instance -> setLabelsOnSelf(instanceName, instance, Map.of()));
    }

    private Map<String, String> buildSelfLabels(PeerInfo self) {
        return Map.of(LABEL_CLUSTER,
                      clusterName,
                      LABEL_PORT,
                      String.valueOf(self.port()),
                      LABEL_ROLE,
                      self.metadata().getOrDefault("role", DEFAULT_ROLE));
    }

    private static List<PeerInfo> toPeerInfoList(List<Instance> instances) {
        return instances.stream().map(GcpDiscoveryProvider::instanceToPeerInfo)
                               .toList();
    }

    private static PeerInfo instanceToPeerInfo(Instance instance) {
        return new PeerInfo(extractHost(instance), extractPort(instance), extractMetadata(instance));
    }

    private static String extractHost(Instance instance) {
        return firstNetworkIp(instance).or("0.0.0.0");
    }

    private static Option<String> firstNetworkIp(Instance instance) {
        return option(instance.networkInterfaces()).filter(nets -> !nets.isEmpty()).map(GcpDiscoveryProvider::firstIp);
    }

    private static String firstIp(List<Instance.NetworkInterface> interfaces) {
        return interfaces.getFirst().networkIP();
    }

    private static int extractPort(Instance instance) {
        return option(instance.labels()).flatMap(labels -> option(labels.get(LABEL_PORT)))
                     .map(GcpDiscoveryProvider::parsePortOrDefault)
                     .or(DEFAULT_PORT);
    }

    private static int parsePortOrDefault(String portStr) {
        return org.pragmatica.lang.parse.Number.parseInt(portStr).or(DEFAULT_PORT);
    }

    private static Map<String, String> extractMetadata(Instance instance) {
        return option(instance.labels()).or(Map.of());
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
        return peers.stream().map(GcpDiscoveryProvider::peerKey)
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
