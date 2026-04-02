package org.pragmatica.aether.environment.aws;

import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.PeerInfo;
import org.pragmatica.cloud.aws.AwsClient;
import org.pragmatica.cloud.aws.api.DescribeInstancesResponse;
import org.pragmatica.cloud.aws.api.Instance;
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

/// AWS Cloud implementation of the DiscoveryProvider SPI.
/// Discovers peers by querying EC2 instances with a specific `aether-cluster` tag.
/// Watches for peer changes by polling at a configurable interval.
public final class AwsDiscoveryProvider implements DiscoveryProvider {
    private static final Logger log = LoggerFactory.getLogger(AwsDiscoveryProvider.class);

    private static final int DEFAULT_PORT = 9100;
    private static final String TAG_CLUSTER = "aether-cluster";
    private static final String TAG_PORT = "aether-port";
    private static final String TAG_ROLE = "aether-role";
    private static final String DEFAULT_ROLE = "core";

    private final AwsClient client;
    private final String clusterName;
    private final long pollIntervalMs;
    private final StoppableThread watchThread = StoppableThread.stoppableThread();

    private AwsDiscoveryProvider(AwsClient client,
                                 String clusterName,
                                 long pollIntervalMs) {
        this.client = client;
        this.clusterName = clusterName;
        this.pollIntervalMs = pollIntervalMs;
    }

    /// Factory method for creating an AwsDiscoveryProvider from config.
    public static AwsDiscoveryProvider awsDiscoveryProvider(AwsClient client,
                                                            AwsEnvironmentConfig config) {
        return new AwsDiscoveryProvider(client,
                                        config.clusterName().or("default"),
                                        config.discoveryPollIntervalMs());
    }

    @Override public Promise<List<PeerInfo>> discoverPeers() {
        return client.describeInstances(TAG_CLUSTER, clusterName).map(AwsDiscoveryProvider::toPeerInfoList)
                                       .mapError(AwsDiscoveryProvider::toDiscoveryError);
    }

    @Override public Promise<Unit> watchPeers(Consumer<List<PeerInfo>> onChange) {
        var thread = Thread.ofVirtual().name("aws-discovery-watcher")
                                     .start(() -> pollLoop(onChange));
        watchThread.set(thread);
        return Promise.success(unit());
    }

    @Override public Promise<Unit> stopWatching() {
        interruptWatchThread();
        return Promise.success(unit());
    }

    @Override public Promise<Unit> registerSelf(PeerInfo self) {
        return EnvironmentError.operationNotSupported("registerSelf — use EC2 tags directly").promise();
    }

    @Override public Promise<Unit> deregisterSelf() {
        return EnvironmentError.operationNotSupported("deregisterSelf — use EC2 tags directly").promise();
    }

    // --- Leaf: map describe response to peer info list ---
    private static List<PeerInfo> toPeerInfoList(DescribeInstancesResponse response) {
        return response.allInstances().stream()
                                    .filter(AwsDiscoveryProvider::isRunning)
                                    .map(AwsDiscoveryProvider::instanceToPeerInfo)
                                    .toList();
    }

    // --- Leaf: check if instance is running ---
    private static boolean isRunning(Instance instance) {
        return "running".equals(instance.instanceState().name());
    }

    // --- Leaf: extract PeerInfo from an instance ---
    private static PeerInfo instanceToPeerInfo(Instance instance) {
        return new PeerInfo(extractHost(instance), extractPort(instance), extractMetadata(instance));
    }

    // --- Leaf: extract host, preferring private IP ---
    private static String extractHost(Instance instance) {
        return option(instance.privateIpAddress()).or(() -> option(instance.publicIpAddress()).or("0.0.0.0"));
    }

    // --- Leaf: extract port from aether-port tag, default 9100 ---
    private static int extractPort(Instance instance) {
        return tagValue(instance, TAG_PORT).map(AwsDiscoveryProvider::parsePortOrDefault)
                       .or(DEFAULT_PORT);
    }

    // --- Leaf: parse port string to int, falling back to default ---
    private static int parsePortOrDefault(String portStr) {
        return org.pragmatica.lang.parse.Number.parseInt(portStr).or(DEFAULT_PORT);
    }

    // --- Leaf: extract metadata from instance tags ---
    private static Map<String, String> extractMetadata(Instance instance) {
        return AwsComputeProvider.extractTags(instance);
    }

    // --- Leaf: get tag value from an instance ---
    private static Option<String> tagValue(Instance instance, String key) {
        return option(instance.tagSet()).flatMap(ts -> option(ts.items()))
                     .flatMap(tags -> findTagValue(tags, key));
    }

    // --- Leaf: find tag value by key ---
    private static Option<String> findTagValue(List<Instance.Tag> tags, String key) {
        return Option.from(tags.stream().filter(tag -> key.equals(tag.key()))
                                      .map(Instance.Tag::value)
                                      .findFirst());
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
        return peers.stream().map(AwsDiscoveryProvider::peerKey)
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
