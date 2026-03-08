package org.pragmatica.aether.endpoint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry.Endpoint;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Non-consensus registry of worker-hosted slice endpoints.
/// Populated by governor health reports, used by SliceInvoker for routing.
///
/// Thread-safe: all operations use ConcurrentHashMap.
/// Phase 2 will unify this with the consensus-driven EndpointRegistry.
public final class WorkerEndpointRegistry {
    private static final Logger log = LoggerFactory.getLogger(WorkerEndpointRegistry.class);

    // groupId -> List<WorkerEndpointEntry>
    private final Map<String, List<WorkerEndpointEntry>> groupEntries = new ConcurrentHashMap<>();

    // groupId -> governorId (for routing through governor in Phase 1)
    private final Map<String, NodeId> groupGovernors = new ConcurrentHashMap<>();

    // lookupKey -> round-robin counter
    private final Map<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

    private WorkerEndpointRegistry() {}

    /// Creates a new empty worker endpoint registry.
    public static WorkerEndpointRegistry workerEndpointRegistry() {
        return new WorkerEndpointRegistry();
    }

    /// Bulk update from a governor health report.
    /// Replaces all entries for the given group with the report contents.
    @SuppressWarnings("JBCT-RET-01") // Mutating operation — void is intentional for event-driven update
    public void registerWorkerEndpoints(WorkerGroupHealthReport report) {
        groupEntries.put(report.groupId(),
                         List.copyOf(report.endpoints()));
        groupGovernors.put(report.groupId(), report.governorId());
        log.debug("Registered {} worker endpoints for group '{}' from governor {}",
                  report.endpoints()
                        .size(),
                  report.groupId(),
                  report.governorId());
    }

    /// Select a single worker endpoint using round-robin load balancing.
    /// Returns empty if no worker endpoints are available for the given artifact and method.
    public Option<WorkerEndpointEntry> selectWorkerEndpoint(Artifact artifact, MethodName methodName) {
        var available = findMatchingEndpoints(artifact, methodName);
        if (available.isEmpty()) {
            return Option.none();
        }
        var lookupKey = artifact.asString() + "/" + methodName.name();
        var counter = roundRobinCounters.computeIfAbsent(lookupKey, _ -> new AtomicInteger(0));
        var index = (counter.getAndIncrement() & 0x7FFFFFFF) % available.size();
        return Option.option(available.get(index));
    }

    /// Find all worker endpoints for a given artifact and method.
    public List<WorkerEndpointEntry> allWorkerEndpoints(Artifact artifact, MethodName methodName) {
        return findMatchingEndpoints(artifact, methodName);
    }

    /// Select a worker endpoint and convert to core Endpoint routed through the governor.
    /// In Phase 1, all worker invocations are relayed via the governor node.
    /// Returns empty if no worker endpoints match or no governor is known.
    public Option<Endpoint> selectWorkerEndpointAsCore(Artifact artifact, MethodName methodName) {
        return selectWorkerEndpoint(artifact, methodName).flatMap(this::toGovernorRoutedEndpoint);
    }

    /// Remove all entries for a group (e.g., when governor leaves).
    @SuppressWarnings("JBCT-RET-01") // Mutating operation — void is intentional for event-driven cleanup
    public void removeGroup(String groupId) {
        groupGovernors.remove(groupId);
        Option.option(groupEntries.remove(groupId))
              .onPresent(removed -> log.debug("Removed {} worker endpoints for group '{}'",
                                              removed.size(),
                                              groupId));
    }

    /// Total unique worker nodes across all groups.
    public int workerCount() {
        return (int) groupEntries.values()
                                .stream()
                                .flatMap(List::stream)
                                .map(WorkerEndpointEntry::workerNodeId)
                                .distinct()
                                .count();
    }

    /// All unique worker node IDs across all groups, sorted by ID.
    public List<NodeId> allWorkerNodeIds() {
        return groupEntries.values()
                           .stream()
                           .flatMap(List::stream)
                           .map(WorkerEndpointEntry::workerNodeId)
                           .distinct()
                           .sorted(Comparator.comparing(NodeId::id))
                           .toList();
    }

    /// Number of worker groups currently registered.
    public int groupCount() {
        return groupEntries.size();
    }

    /// All endpoint entries across all groups, sorted by worker node ID.
    public List<WorkerEndpointEntry> allEndpoints() {
        return groupEntries.values()
                           .stream()
                           .flatMap(List::stream)
                           .sorted(Comparator.comparing(e -> e.workerNodeId()
                                                              .id()))
                           .toList();
    }

    /// Whether the registry has no entries.
    public boolean isEmpty() {
        return groupEntries.isEmpty();
    }

    private List<WorkerEndpointEntry> findMatchingEndpoints(Artifact artifact, MethodName methodName) {
        return groupEntries.values()
                           .stream()
                           .flatMap(List::stream)
                           .filter(e -> e.artifact()
                                         .equals(artifact) && e.methodName()
                                                               .equals(methodName))
                           .sorted(Comparator.comparing(e -> e.workerNodeId()
                                                              .id()))
                           .toList();
    }

    private Option<Endpoint> toGovernorRoutedEndpoint(WorkerEndpointEntry entry) {
        return findGovernorForWorker(entry)
        .map(governorId -> Endpoint.endpoint(entry.artifact(), entry.methodName(), entry.instanceNumber(), governorId));
    }

    private Option<NodeId> findGovernorForWorker(WorkerEndpointEntry entry) {
        return groupGovernors.entrySet()
                             .stream()
                             .filter(e -> groupContainsWorker(e.getKey(),
                                                              entry))
                             .map(Map.Entry::getValue)
                             .findFirst()
                             .map(Option::some)
                             .orElse(Option.none());
    }

    private boolean groupContainsWorker(String groupId, WorkerEndpointEntry entry) {
        return Option.option(groupEntries.get(groupId))
                     .map(entries -> entries.contains(entry))
                     .or(false);
    }
}
