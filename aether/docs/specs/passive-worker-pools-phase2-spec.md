# Passive Worker Pools -- Phase 2: Multi-Group Topology

| Field       | Value                                          |
|-------------|------------------------------------------------|
| Version     | 0.20.0                                         |
| Status      | Draft                                          |
| Author      | Sergiy Yevtushenko                             |
| Created     | 2026-03-10                                     |
| Depends on  | Phase 1 (v0.19.3)                              |
| Sub-phases  | P2a -> P2b -> P2c (strict ordering)            |

## Summary

Phase 2 extends the flat worker pool architecture delivered in Phase 1 to support multi-group topology with zone-aware grouping, spot instance pools, and flag-gated KV-store migration for endpoint resolution. The architecture must support 10K worker nodes. Workers run any workload including scheduled tasks and pub/sub -- these capabilities are cross-cutting and not deferred.

## Background: Phase 1 Recap

Phase 1 (v0.19.3) delivered:

- **WorkerNode** -- passive cluster member receiving Decisions via `PassiveNode`, using SWIM for intra-worker failure detection and governor election.
  - `aether/worker/src/main/java/.../worker/WorkerNode.java`
- **GovernorElection** -- deterministic election: lowest ALIVE NodeId wins, sticky incumbent.
  - `aether/worker/src/main/java/.../worker/governor/GovernorElection.java`
- **DecisionRelay** -- governor fans out Decisions to followers; followers receive relayed Decisions.
  - `aether/worker/src/main/java/.../worker/governor/DecisionRelay.java`
- **MutationForwarder** -- workers forward mutations to core via governor.
  - `aether/worker/src/main/java/.../worker/mutation/MutationForwarder.java`
- **WorkerNetwork** -- TCP network for inter-worker communication (Decision relay, mutation forwarding).
  - `aether/worker/src/main/java/.../worker/network/WorkerNetwork.java`
- **WorkerBootstrap** -- KV state snapshot bootstrap for newly joining workers.
  - `aether/worker/src/main/java/.../worker/bootstrap/WorkerBootstrap.java`
- **WorkerEndpointRegistry** -- non-consensus registry of worker-hosted slice endpoints, populated by governor health reports.
  - `aether/aether-invoke/src/main/java/.../endpoint/WorkerEndpointRegistry.java`
- **WorkerGroupHealthReport** -- health report from governor to core; carries endpoints and members.
  - `aether/aether-invoke/src/main/java/.../endpoint/WorkerGroupHealthReport.java`
- **WorkerEndpointEntry** -- endpoint record for a slice method on a worker.
  - `aether/aether-invoke/src/main/java/.../endpoint/WorkerEndpointEntry.java`
- **CoreSwimHealthDetector** -- core-to-core SWIM health detection.
  - `aether/node/src/main/java/.../node/health/CoreSwimHealthDetector.java`
- **WorkerConfig** -- configuration record for worker nodes (coreNodes, clusterPort, swimPort, swimSettings, sliceConfig).
  - `aether/aether-config/src/main/java/.../config/WorkerConfig.java`
- **AllocationPool** -- pool of allocatable nodes combining core and worker nodes.
  - `aether/aether-deployment/src/main/java/.../deployment/cluster/AllocationPool.java`
- **PlacementPolicy** -- enum: `CORE_ONLY`, `WORKERS_PREFERRED`, `WORKERS_ONLY`, `ALL`.
  - `aether/aether-config/src/main/java/.../config/PlacementPolicy.java`
- **WorkerRoutes** -- management API routes: `/api/workers`, `/api/workers/health`, `/api/workers/endpoints`.
  - `aether/node/src/main/java/.../api/routes/WorkerRoutes.java`
- **CDM pool awareness** -- `ClusterDeploymentManager` builds `AllocationPool` from core nodes + `WorkerEndpointRegistry.allWorkerNodeIds()`.
  - `aether/aether-deployment/src/main/java/.../deployment/cluster/ClusterDeploymentManager.java`

Phase 1 limitations addressed by Phase 2:
- Single flat group (all workers in one SWIM cluster)
- No zone awareness
- No spot instance support
- Endpoint resolution always goes through consensus KV-store first, falling back to `WorkerEndpointRegistry`
- Workers do not run scheduled tasks or pub/sub

## Design Decisions

These are user-specified and MUST be followed exactly:

| # | Decision | Detail |
|---|----------|--------|
| 1 | Cloud Integration SPI available | SPI is already implemented (`EnvironmentIntegration`, `ComputeProvider`). Spot pool assumes the SPI is available. Local env SPI also provided. |
| 2 | Scale target: 10K workers | Architecture must support 10,000 worker nodes. No cloud stress testing now, but all data structures and protocols must scale. |
| 3 | KV-store migration is flag-gated | Both old (consensus KV) and new (WorkerEndpointRegistry) paths coexist. Defaults to old behavior. |
| 4 | Sub-phase ordering is strict | P2a -> P2b -> P2c. Each sub-phase is independently shippable. |
| 5 | Cross-group routing always through core | No governor-to-governor direct communication. |
| 6 | Workers run ANY load | Scheduled tasks and pub/sub support on workers. NOT deferred to Phase 3. |

## Architecture

### Multi-Group Topology (ASCII Diagram)

```
                    +-------------------+
                    |   Core Cluster    |
                    | (Rabia Consensus) |
                    |  N1   N2   N3    |
                    +--------+----------+
                             |
              +--------------+--------------+
              |              |              |
     +--------v---+   +-----v------+  +----v-------+
     | Group A    |   | Group B    |  | Group C    |
     | zone-us-e1 |   | zone-us-w1 |  | zone-eu-w1 |
     | (MAIN)     |   | (MAIN)     |  | (SPOT)     |
     +------------+   +------------+  +------------+
     | Gov + 49W  |   | Gov + 49W  |  | Gov + 29W  |
     | SWIM scope |   | SWIM scope |  | SWIM scope |
     +------------+   +------------+  +------------+
```

- Each group runs its own SWIM protocol instance (group-scoped probing)
- Groups are formed by zone prefix extracted from NodeId
- Each group has its own governor election
- Cross-group routing: Worker -> Governor -> Core -> (Core routes to target governor) -> Target worker
- SWIM message volume per group: O(group_size), not O(total_workers)

### Data Flow: Endpoint Resolution (Phase 2 Final State)

```
  Client Request
       |
       v
  SliceInvoker.selectEndpoint()
       |
       +-- [flag=OFF] --> EndpointRegistry (consensus KV) --> WorkerEndpointRegistry (fallback)
       |
       +-- [flag=ON]  --> WorkerEndpointRegistry (primary) --> EndpointRegistry (fallback)
```

### Data Flow: Worker Group Health Reporting

```
  Worker Node (follower)
       |  SWIM membership
       v
  Governor Node (per group)
       |  WorkerGroupHealthReport (includes zone, pool type, resource averages)
       v
  Core Node (any)
       |  registerWorkerEndpoints()
       v
  WorkerEndpointRegistry
       |  (grouped by groupId)
       v
  SliceInvoker (endpoint selection)
```

## P2a: Multi-Group + Zone-Aware Grouping

### P2a-01: WorkerGroupId Value Object

A distinct type from the existing `GroupId` (which is Maven artifact group ID). This represents a worker pool group identity.

**File:** `aether/aether-config/src/main/java/org/pragmatica/aether/config/WorkerGroupId.java`

```java
package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Verify.Is;
import static org.pragmatica.lang.Verify.ensure;

/// Identity of a worker group. Format: zone-prefix-based, e.g. "zone-us-east-1-group-0".
@Codec
public record WorkerGroupId(String id) implements Comparable<WorkerGroupId> {
    private static final Pattern WORKER_GROUP_ID_PATTERN =
        Pattern.compile("^[a-z][a-z0-9-]+-group-\\d+$");

    public static Result<WorkerGroupId> workerGroupId(String id) {
        return ensure(id, Is::matches, WORKER_GROUP_ID_PATTERN)
                     .map(WorkerGroupId::new);
    }

    @Override
    public int compareTo(WorkerGroupId other) {
        return id.compareTo(other.id);
    }

    @Override
    public String toString() {
        return id;
    }
}
```

**Integration:** Used by `WorkerGroupHealthReport`, `WorkerEndpointRegistry`, `DecisionRelay`, `AllocationPool`.

### P2a-02: ZoneExtractor

Extracts zone from NodeId convention. NodeIds follow the format `zone-{region}-worker-{N}`.

**File:** `aether/aether-config/src/main/java/org/pragmatica/aether/config/ZoneExtractor.java`

```java
package org.pragmatica.aether.config;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

/// Extracts zone prefix from a NodeId following the convention:
///   zone-us-east-1-worker-7  ->  zone-us-east-1
///   zone-eu-west-1-worker-3  ->  zone-eu-west-1
///
/// Falls back to "default" zone when NodeId does not match the convention
/// (backward-compatible with Phase 1 random NodeIds).
public sealed interface ZoneExtractor {
    record unused() implements ZoneExtractor {}

    /// Default zone used when NodeId does not follow the zone convention.
    String DEFAULT_ZONE = "default";

    /// Extract zone prefix from a NodeId.
    static String extractZone(NodeId nodeId) {
        return parseZone(nodeId.id()).or(DEFAULT_ZONE);
    }

    private static Option<String> parseZone(String id) {
        var workerIdx = id.lastIndexOf("-worker-");
        if (workerIdx <= 0) {
            return Option.empty();
        }
        var zone = id.substring(0, workerIdx);
        return zone.startsWith("zone-")
               ? Option.some(zone)
               : Option.empty();
    }
}
```

### P2a-03: GroupAssignment (Consistent Hashing)

Assigns workers to groups based on zone prefix. Uses consistent hashing to distribute workers within a zone into multiple groups when `maxGroupSize` is exceeded.

**File:** `aether/worker/src/main/java/org/pragmatica/aether/worker/group/GroupAssignment.java`

```java
package org.pragmatica.aether.worker.group;

import org.pragmatica.aether.config.WorkerGroupId;
import org.pragmatica.aether.config.ZoneExtractor;
import org.pragmatica.consensus.NodeId;

/// Determines which group a worker node belongs to, using consistent hashing
/// within its zone. When a zone has more workers than maxGroupSize, they are
/// split into numbered sub-groups.
///
/// Assignment is deterministic: every node computes the same result for a given
/// NodeId and the same set of zone members.
public sealed interface GroupAssignment {
    record unused() implements GroupAssignment {}

    /// Compute the group ID for a worker node.
    ///
    /// @param nodeId the worker's NodeId (zone-prefixed)
    /// @param zoneMemberCount total workers known in this zone
    /// @param maxGroupSize maximum workers per group
    /// @return the assigned WorkerGroupId
    static WorkerGroupId assignGroup(NodeId nodeId, int zoneMemberCount, int maxGroupSize) {
        var zone = ZoneExtractor.extractZone(nodeId);
        if (zoneMemberCount <= maxGroupSize) {
            return WorkerGroupId.workerGroupId(zone + "-group-0").unwrap();
        }
        var groupIndex = consistentHash(nodeId, groupCount(zoneMemberCount, maxGroupSize));
        return WorkerGroupId.workerGroupId(zone + "-group-" + groupIndex).unwrap();
    }

    /// Number of groups needed for a zone.
    static int groupCount(int memberCount, int maxGroupSize) {
        return (memberCount + maxGroupSize - 1) / maxGroupSize;
    }

    /// Jump consistent hash: deterministic, uniform distribution, minimal reshuffling.
    private static int consistentHash(NodeId nodeId, int buckets) {
        var key = nodeId.id().hashCode() & 0xFFFFFFFFL;
        int b = -1;
        int j = 0;
        while (j < buckets) {
            b = j;
            key = key * 2862933555777941757L + 1;
            j = (int) ((b + 1) * (1L << 31) / ((key >>> 33) + 1));
        }
        return b;
    }
}
```

**Scale consideration (10K workers):** Jump consistent hash provides O(1) computation per node, minimal reshuffling on group split/merge, and uniform distribution. With `maxGroupSize=50` and 10K workers across 10 zones, this produces ~200 groups -- each with its own bounded SWIM protocol scope.

### P2a-04: WorkerConfig Extension

Add `zone` and `maxGroupSize` fields to `WorkerConfig`.

**File:** `aether/aether-config/src/main/java/org/pragmatica/aether/config/WorkerConfig.java` (modification)

```java
/// @param zone          Zone identifier for this worker (e.g., "zone-us-east-1")
/// @param maxGroupSize  Maximum workers per SWIM group (default 50)
public record WorkerConfig(List<String> coreNodes,
                           int clusterPort,
                           int swimPort,
                           SwimSettings swimSettings,
                           SliceConfig sliceConfig,
                           String zone,
                           int maxGroupSize) {
    public static final int DEFAULT_CLUSTER_PORT = 7100;
    public static final int DEFAULT_SWIM_PORT = 7200;
    public static final String DEFAULT_ZONE = "default";
    public static final int DEFAULT_MAX_GROUP_SIZE = 50;

    // Factory methods updated to include zone and maxGroupSize
    // with backward-compatible overloads defaulting to DEFAULT_ZONE / 50
}
```

**Integration:**
- `WorkerConfigLoader` updated to parse `zone` and `maxGroupSize` from TOML config
- `WorkerNode.workerNode()` uses `config.zone()` to build zone-prefixed NodeId instead of `NodeId.randomNodeId()`

### P2a-05: Zone-Prefixed NodeId for Workers

**File:** `aether/worker/src/main/java/org/pragmatica/aether/worker/WorkerNode.java` (modification)

Change the factory from `NodeId.randomNodeId()` to a zone-prefixed NodeId:

```java
static Result<WorkerNode> workerNode(WorkerConfig config) {
    var nodeId = NodeId.nodeId(config.zone() + "-worker-" + IdGenerator.generate("w"))
                       .unwrap();
    // ... rest unchanged
}
```

This ensures every worker's NodeId encodes its zone, making `ZoneExtractor` work without any lookup table.

### P2a-06: Group-Scoped SWIM

Workers must only probe within their assigned group. This is the critical scaling enabler -- without it, 10K workers would generate O(10K^2) probe traffic.

**Approach:** Each group runs an independent SWIM protocol instance. Workers discover same-group peers via the governor's health report (which lists group members). On join, a worker contacts the core to learn its group assignment and the addresses of seed members.

**File:** `aether/worker/src/main/java/org/pragmatica/aether/worker/WorkerNode.java` (modification)

In `AssembledWorkerNode.startSwim()`, seed members are filtered to same-group peers. The `GroupAssignment` result determines which seed addresses to use.

**Impact on existing code:**
- `SwimProtocol` itself is unchanged -- it is already group-agnostic (probes whatever members are added)
- `AssembledWorkerNode.onMemberJoined/onMemberFaulty/onMemberLeft` -- unchanged (already operate on the local SWIM view)
- `GovernorElection` -- unchanged (already uses the local membership snapshot)

### P2a-07: Auto-Splitting on Group Overflow

When a group's live member count exceeds `maxGroupSize`, the governor triggers a group split. The split is coordinated through the core cluster:

1. Governor detects `membershipSnapshot.size() > maxGroupSize` in `reEvaluateGovernor()`
2. Governor sends a `GroupSplitRequest` to core
3. CDM creates new group assignments using `GroupAssignment.assignGroup()`
4. CDM distributes new group assignments via health report response
5. Workers re-evaluate their group and re-join SWIM with new group peers

**File:** `aether/worker/src/main/java/org/pragmatica/aether/worker/group/GroupSplitRequest.java`

```java
package org.pragmatica.aether.worker.group;

import org.pragmatica.aether.config.WorkerGroupId;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;

import java.util.List;

/// Request from a governor to the core cluster to split an overflowed group.
@Codec
public record GroupSplitRequest(WorkerGroupId currentGroup,
                                NodeId governorId,
                                List<NodeId> members,
                                int currentSize) {
    public static GroupSplitRequest groupSplitRequest(WorkerGroupId currentGroup,
                                                       NodeId governorId,
                                                       List<NodeId> members,
                                                       int currentSize) {
        return new GroupSplitRequest(currentGroup, governorId, List.copyOf(members), currentSize);
    }
}
```

### P2a-08: WorkerGroupHealthReport Extension

Add zone, resource averages, and group count to the health report.

**File:** `aether/aether-invoke/src/main/java/org/pragmatica/aether/endpoint/WorkerGroupHealthReport.java` (modification)

```java
/// @param governorId   the NodeId of the governor sending this report
/// @param groupId      the worker group identifier
/// @param zone         the zone this group belongs to
/// @param poolType     MAIN or SPOT
/// @param endpoints    list of active worker endpoint entries
/// @param members      list of worker NodeIds in the group
/// @param memberCount  total members in this group (for scaling decisions)
/// @param cpuAverage   average CPU usage across group members (0.0-1.0)
/// @param memoryAverage average memory usage across group members (0.0-1.0)
/// @param timestamp    when this report was generated
public record WorkerGroupHealthReport(NodeId governorId,
                                      String groupId,
                                      String zone,
                                      String poolType,
                                      List<WorkerEndpointEntry> endpoints,
                                      List<NodeId> members,
                                      int memberCount,
                                      double cpuAverage,
                                      double memoryAverage,
                                      long timestamp) {
    // Factory methods with backward-compatible overloads
}
```

### P2a-09: DecisionRelay -- Group-Scoped Filtering

The governor only relays Decisions that are relevant to slices deployed in its group. This reduces bandwidth per group.

**File:** `aether/worker/src/main/java/org/pragmatica/aether/worker/governor/DecisionRelay.java` (modification)

```java
/// Called when a Decision is received from the core cluster (governor path).
/// Filters decisions to only relay those affecting slices in this group,
/// then broadcasts to followers.
public void onDecisionFromCore(Decision<?> decision,
                               List<NodeId> followers,
                               Set<AetherKey> groupRelevantKeys) {
    bufferDecision(decision);
    if (isRelevantToGroup(decision, groupRelevantKeys)) {
        followers.forEach(followerId -> network.send(followerId, decision));
    }
}
```

The governor maintains a `Set<AetherKey>` of keys relevant to slices assigned to this group (derived from CDM placement). Decisions touching keys outside this set are buffered (for KV consistency) but not relayed.

### P2a-10: Worker Management API Extension

**File:** `aether/node/src/main/java/org/pragmatica/aether/api/routes/WorkerRoutes.java` (modification)

Add `/api/workers/groups` endpoint:

```java
record GroupEntry(String groupId, String zone, String poolType,
                  int memberCount, String governorId) {}

// In routes():
Route.<List<GroupEntry>> get("/api/workers/groups")
     .toJson(this::listGroups)
```

**CLI:** `aether/cli/AetherCli.java` updated with `workers groups` subcommand.

**Docs:** `aether/docs/reference/management-api.md` and `aether/docs/reference/cli.md` updated.

## P2b: Spot Pool

### P2b-01: WorkerPoolType Enum

**File:** `aether/aether-config/src/main/java/org/pragmatica/aether/config/WorkerPoolType.java`

```java
package org.pragmatica.aether.config;

/// The type of worker pool a worker belongs to.
public enum WorkerPoolType {
    /// Always-on workers -- guaranteed availability.
    MAIN,
    /// Spot/preemptible workers -- may be reclaimed at any time.
    SPOT
}
```

### P2b-02: WorkerMemberInfo (SWIM Metadata Extension)

The current `SwimMember` record has no metadata field. Rather than modifying the SWIM library, pool type is encoded in the NodeId convention:

```
zone-us-east-1-spot-worker-7    (SPOT)
zone-us-east-1-worker-7         (MAIN)
```

**File:** `aether/worker/src/main/java/org/pragmatica/aether/worker/group/WorkerMemberInfo.java`

```java
package org.pragmatica.aether.worker.group;

import org.pragmatica.aether.config.WorkerPoolType;
import org.pragmatica.consensus.NodeId;

/// Extracts pool type and zone from a worker's NodeId convention.
public sealed interface WorkerMemberInfo {
    record unused() implements WorkerMemberInfo {}

    static WorkerPoolType poolType(NodeId nodeId) {
        return nodeId.id().contains("-spot-")
               ? WorkerPoolType.SPOT
               : WorkerPoolType.MAIN;
    }

    static boolean isSpot(NodeId nodeId) {
        return poolType(nodeId) == WorkerPoolType.SPOT;
    }
}
```

### P2b-03: AllocationPool Extension

Add `spotWorkers` list.

**File:** `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/AllocationPool.java` (modification)

```java
/// @param coreNodes     consensus participants
/// @param mainWorkers   always-on worker pool members
/// @param spotWorkers   spot/preemptible worker pool members
public record AllocationPool(List<NodeId> coreNodes,
                             List<NodeId> mainWorkers,
                             List<NodeId> spotWorkers) {
    // Backward-compatible factory: coreOnly, with mainWorkers only, etc.

    public List<NodeId> nodesForPolicy(PlacementPolicy policy) {
        return switch (policy) {
            case CORE_ONLY -> coreNodes;
            case WORKERS_PREFERRED -> mainWorkers.isEmpty()
                                      ? coreNodes
                                      : mainWorkers;
            case WORKERS_ONLY -> mainWorkers;
            case SPOT_ELIGIBLE -> spotEligibleNodes();
            case ALL -> allNodes();
        };
    }

    /// Spot-eligible: spot workers first, then main workers as fallback.
    private List<NodeId> spotEligibleNodes() {
        if (!spotWorkers.isEmpty()) {
            return spotWorkers;
        }
        return mainWorkers.isEmpty() ? coreNodes : mainWorkers;
    }
}
```

### P2b-04: PlacementPolicy Extension

**File:** `aether/aether-config/src/main/java/org/pragmatica/aether/config/PlacementPolicy.java` (modification)

```java
public enum PlacementPolicy {
    CORE_ONLY,
    WORKERS_PREFERRED,
    WORKERS_ONLY,
    /// Slices are eligible for spot workers; non-critical workloads.
    SPOT_ELIGIBLE,
    ALL
}
```

### P2b-05: SWIM Voluntary Leave on Preemption

When a spot worker detects a preemption signal, it sends a SWIM voluntary leave before draining.

**File:** `aether/worker/src/main/java/org/pragmatica/aether/worker/preemption/PreemptionDetector.java`

```java
package org.pragmatica.aether.worker.preemption;

import org.pragmatica.lang.Promise;

/// SPI for detecting preemption signals. Implementations poll cloud metadata
/// endpoints or listen for OS signals.
public interface PreemptionDetector {
    /// Returns a Promise that resolves when a preemption signal is detected.
    /// The promise never fails -- it only completes when preemption is imminent.
    Promise<PreemptionSignal> awaitPreemption();
}
```

**File:** `aether/worker/src/main/java/org/pragmatica/aether/worker/preemption/PreemptionSignal.java`

```java
package org.pragmatica.aether.worker.preemption;

/// Details of a preemption notification.
/// @param gracePeriodMs time remaining before forced termination (cloud-specific)
/// @param source where the signal originated (e.g., "gcp-metadata", "aws-spot", "local-signal")
public record PreemptionSignal(long gracePeriodMs, String source) {
    public static PreemptionSignal preemptionSignal(long gracePeriodMs, String source) {
        return new PreemptionSignal(gracePeriodMs, source);
    }
}
```

**File:** `aether/worker/src/main/java/org/pragmatica/aether/worker/preemption/LocalPreemptionDetector.java`

```java
package org.pragmatica.aether.worker.preemption;

import org.pragmatica.lang.Promise;

/// Local environment preemption detector. Listens for SIGTERM/SIGINT.
/// Used in Forge tests and local development.
public final class LocalPreemptionDetector implements PreemptionDetector {
    private final Promise<PreemptionSignal> promise = Promise.promise();

    private LocalPreemptionDetector() {
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
            promise.succeed(PreemptionSignal.preemptionSignal(5000, "local-signal"))
        ));
    }

    public static LocalPreemptionDetector localPreemptionDetector() {
        return new LocalPreemptionDetector();
    }

    @Override
    public Promise<PreemptionSignal> awaitPreemption() {
        return promise;
    }
}
```

### P2b-06: Preemption Shutdown Hook

Wired into `AssembledWorkerNode`, the preemption handler:

1. Sends SWIM voluntary leave
2. Drains active slices (completes in-flight requests, stops accepting new ones)
3. Notifies governor of departure
4. Exits

**File:** `aether/worker/src/main/java/org/pragmatica/aether/worker/preemption/PreemptionHandler.java`

```java
package org.pragmatica.aether.worker.preemption;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.swim.SwimProtocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Orchestrates graceful shutdown on preemption.
///
/// Sequence: SWIM leave -> drain slices -> notify governor -> exit.
public final class PreemptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(PreemptionHandler.class);

    private final NodeId selfId;
    private final PreemptionDetector detector;

    private PreemptionHandler(NodeId selfId, PreemptionDetector detector) {
        this.selfId = selfId;
        this.detector = detector;
    }

    public static PreemptionHandler preemptionHandler(NodeId selfId,
                                                       PreemptionDetector detector) {
        return new PreemptionHandler(selfId, detector);
    }

    /// Start listening for preemption. Returns immediately.
    /// When preemption is detected, invokes the shutdown callback.
    public void start(Runnable onPreemption) {
        detector.awaitPreemption()
                .onSuccess(signal -> {
                    LOG.warn("Preemption detected for {}: gracePeriod={}ms, source={}",
                             selfId.id(), signal.gracePeriodMs(), signal.source());
                    onPreemption.run();
                });
    }
}
```

### P2b-07: CDM Spot-Aware Allocation

**File:** `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterDeploymentManager.java` (modification)

The CDM allocation logic in `allocateSliceInstance()` must respect placement policy:

- `SPOT_ELIGIBLE` slices: prefer spot workers, fallback to main workers
- Critical slices (with `PlacementPolicy.CORE_ONLY` or `WORKERS_PREFERRED`): NEVER placed on spot workers
- When a spot worker is preempted, CDM detects the removal via `WorkerEndpointRegistry` group removal and triggers re-allocation to remaining nodes

The `buildAllocationPool()` method is updated to partition worker nodes into main and spot:

```java
AllocationPool buildAllocationPool() {
    var allWorkers = workerEndpointRegistry.map(WorkerEndpointRegistry::allWorkerNodeIds)
                                            .or(List.of());
    var main = allWorkers.stream()
                         .filter(id -> !WorkerMemberInfo.isSpot(id))
                         .toList();
    var spot = allWorkers.stream()
                         .filter(WorkerMemberInfo::isSpot)
                         .toList();
    return AllocationPool.allocationPool(allocatableNodes(), main, spot);
}
```

## P2c: KV-Store Migration (Flag-Gated)

### P2c-01: CoreEndpointReporter

Pushes core endpoint state into the `WorkerEndpointRegistry` format, enabling a unified lookup path.

**File:** `aether/aether-invoke/src/main/java/org/pragmatica/aether/endpoint/CoreEndpointReporter.java`

```java
package org.pragmatica.aether.endpoint;

import org.pragmatica.aether.endpoint.EndpointRegistry.Endpoint;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Bridges core consensus-driven endpoints into the WorkerEndpointRegistry format.
///
/// When the migration flag is enabled, core endpoints are registered in the
/// WorkerEndpointRegistry so that SliceInvoker can query a single registry.
/// The "group" for core endpoints is always "core".
public final class CoreEndpointReporter {
    private static final Logger log = LoggerFactory.getLogger(CoreEndpointReporter.class);
    private static final String CORE_GROUP_ID = "core";

    private final EndpointRegistry endpointRegistry;
    private final WorkerEndpointRegistry workerEndpointRegistry;

    private CoreEndpointReporter(EndpointRegistry endpointRegistry,
                                  WorkerEndpointRegistry workerEndpointRegistry) {
        this.endpointRegistry = endpointRegistry;
        this.workerEndpointRegistry = workerEndpointRegistry;
    }

    public static CoreEndpointReporter coreEndpointReporter(EndpointRegistry endpointRegistry,
                                                             WorkerEndpointRegistry workerEndpointRegistry) {
        return new CoreEndpointReporter(endpointRegistry, workerEndpointRegistry);
    }

    /// Sync current core endpoints into the worker registry.
    /// Called periodically or on endpoint change events when the migration flag is enabled.
    @SuppressWarnings("JBCT-RET-01")
    public void syncCoreEndpoints(List<NodeId> coreNodes) {
        var coreEntries = endpointRegistry.allEndpoints()
                                           .stream()
                                           .map(CoreEndpointReporter::toWorkerEntry)
                                           .toList();
        var report = WorkerGroupHealthReport.workerGroupHealthReport(
            coreNodes.getFirst(),
            CORE_GROUP_ID,
            coreEntries,
            coreNodes);
        workerEndpointRegistry.registerWorkerEndpoints(report);
        log.debug("Synced {} core endpoints to worker registry", coreEntries.size());
    }

    private static WorkerEndpointEntry toWorkerEntry(Endpoint endpoint) {
        return WorkerEndpointEntry.workerEndpointEntry(
            endpoint.artifact(), endpoint.methodName(),
            endpoint.nodeId(), endpoint.instanceNumber());
    }
}
```

### P2c-02: SliceInvoker Query Order Branch

**File:** `aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/SliceInvoker.java` (modification)

The `selectCoreOrWorkerEndpoint()` method branches on the migration flag:

```java
private Promise<Endpoint> selectCoreOrWorkerEndpoint(Artifact slice, MethodName method) {
    if (useRegistryFirst.get()) {
        // New path: WorkerEndpointRegistry is primary (contains both core + worker endpoints)
        return workerEndpointRegistry
            .flatMap(registry -> registry.selectWorkerEndpointAsCore(slice, method))
            .or(() -> endpointRegistry.selectEndpoint(slice, method))
            .async(NO_ENDPOINT_FOUND);
    }
    // Legacy path: consensus EndpointRegistry first, worker fallback
    var coreEndpoint = endpointRegistry.selectEndpoint(slice, method);
    if (coreEndpoint.isPresent()) {
        return coreEndpoint.async(NO_ENDPOINT_FOUND);
    }
    return workerEndpointRegistry
        .flatMap(registry -> registry.selectWorkerEndpointAsCore(slice, method))
        .async(NO_ENDPOINT_FOUND);
}
```

The `useRegistryFirst` flag is an `AtomicBoolean` initialized from configuration and toggleable at runtime via the management API.

### P2c-03: Management API Toggle

**File:** `aether/node/src/main/java/org/pragmatica/aether/api/routes/WorkerRoutes.java` (modification)

```java
Route.<MigrationStatus> get("/api/workers/migration")
     .toJson(this::getMigrationStatus),
Route.<MigrationStatus> post("/api/workers/migration/enable")
     .toJson(this::enableMigration),
Route.<MigrationStatus> post("/api/workers/migration/disable")
     .toJson(this::disableMigration)
```

**CLI:** `aether workers migration [status|enable|disable]`

### P2c-04: Flag-Gated Behavior

| Flag State | EndpointRegistry (KV) | WorkerEndpointRegistry | Primary |
|------------|----------------------|------------------------|---------|
| OFF (default) | Active | Active (workers only) | KV-store |
| ON | Active (fallback) | Active (core + workers) | Registry |

**Rollback:** Set flag to OFF. `CoreEndpointReporter` stops syncing. Old path resumes immediately. No data loss -- both registries remain populated.

## Worker Capabilities (Cross-Cutting)

### Scheduled Tasks on Workers

Currently, `ScheduledTaskManager` (`aether/aether-invoke/src/main/java/.../invoke/ScheduledTaskManager.java`) runs only on core nodes and respects leader-only semantics.

For workers, scheduled tasks must:
1. Be deployable to worker nodes via CDM placement
2. Respect group-level leader semantics: only the governor runs leader-only scheduled tasks within its group
3. Non-leader-only tasks can run on any worker in the group

**Implementation approach:**

- `ScheduledTaskManager` gains an alternate constructor that accepts a `Supplier<Boolean>` for "is leader" instead of receiving `LeaderChange` messages. On workers, this supplier returns `isGovernor()`.
- `QuorumStateNotification` is replaced by a simple "group healthy" check (governor is elected and SWIM group has quorum).
- The existing `ScheduledTaskRegistry` and `IntervalParser` are reused as-is.

**File changes:**
- `aether/aether-invoke/src/main/java/.../invoke/ScheduledTaskManager.java` -- add factory overload for worker context
- `aether/worker/src/main/java/.../worker/WorkerNode.java` -- wire `ScheduledTaskManager` in `assembleNode()`

### Pub/Sub on Workers

Currently, `TopicPublisher` (`aether/aether-invoke/src/main/java/.../invoke/TopicPublisher.java`) uses `SliceInvoker` to deliver messages to subscribers. `TopicSubscriptionRegistry` (`aether/aether-invoke/src/main/java/.../endpoint/TopicSubscriptionRegistry.java`) watches KV-store events for subscription changes.

For workers:
1. Workers already receive Decisions (including KV mutations) via `DecisionRelay` -> `PassiveNode`
2. Workers can maintain a local `TopicSubscriptionRegistry` watching the same KV events
3. Workers can run a local `TopicPublisher` using the worker-local `SliceInvoker` proxy

**Key consideration:** When a worker publishes a message, it uses the local `SliceInvoker` which routes through the core cluster. This means pub/sub delivery from workers goes:

```
Worker (publisher) -> Core (SliceInvoker routing) -> Target node (subscriber)
```

This is consistent with Design Decision #5 (cross-group routing always through core).

**File changes:**
- `aether/worker/src/main/java/.../worker/WorkerNode.java` -- wire `TopicSubscriptionRegistry` and `PublisherFactory`
- `aether/aether-invoke/src/main/java/.../invoke/PublisherFactory.java` -- no changes needed (already uses `SliceInvoker` abstraction)

## Scale Considerations: 10K Workers

### SWIM Protocol Scaling

| Metric | Phase 1 (flat) | Phase 2 (grouped) |
|--------|-----------------|-------------------|
| Workers | 1-100 | 10,000 |
| SWIM probes/sec/worker | O(N) | O(group_size) |
| Total SWIM messages/sec | O(N^2) | O(N * group_size) |
| Group size | N | 50 (configurable) |
| Groups (10 zones, 10K workers) | 1 | ~200 |
| Governor count | 1 | ~200 |

With `maxGroupSize=50`, each worker sends ~50 probes/second. Total cluster-wide: 10K * 50 = 500K probes/sec, distributed across 200 independent UDP channels. This is well within network capacity.

### Consistent Hashing Properties

- **Minimal reshuffling:** When a group splits, only ~1/N of workers move to the new group
- **Deterministic:** All workers compute the same assignment without coordination
- **O(1) per node:** Jump consistent hash is constant-time

### Health Report Aggregation

With ~200 governors reporting to core, the core cluster receives ~200 health reports per reporting interval. Each report contains group membership (~50 NodeIds) and endpoints. At 1 report/second per governor:

- 200 reports/sec * ~10KB/report = ~2MB/sec inbound to core
- Acceptable for a 3-5 node core cluster

### WorkerEndpointRegistry Scaling

The registry uses `ConcurrentHashMap` keyed by `groupId`. With 200 groups and ~50 entries per group:
- 10,000 entries total
- Lookup: O(1) by group, O(group_size) scan within group
- Memory: ~10K * 200 bytes = ~2MB

[ASSUMPTION] The round-robin counters in `WorkerEndpointRegistry` use `AtomicInteger` which wraps correctly at `Integer.MAX_VALUE` with the `& 0x7FFFFFFF` mask. No scaling concern.

## Task Breakdown

### P2a: Multi-Group + Zone-Aware Grouping

| Task | Description | Dependencies | Complexity |
|------|------------|--------------|------------|
| P2a-01 | `WorkerGroupId` value object | None | S |
| P2a-02 | `ZoneExtractor` utility | None | S |
| P2a-03 | `GroupAssignment` (consistent hashing) | P2a-01, P2a-02 | M |
| P2a-04 | `WorkerConfig` extension (zone, maxGroupSize) | None | S |
| P2a-05 | Zone-prefixed NodeId in `WorkerNode` factory | P2a-02, P2a-04 | S |
| P2a-06 | Group-scoped SWIM (seed filtering) | P2a-03, P2a-05 | M |
| P2a-07 | Auto-splitting (`GroupSplitRequest`, CDM handling) | P2a-03, P2a-06 | L |
| P2a-08 | `WorkerGroupHealthReport` extension | P2a-01 | S |
| P2a-09 | `DecisionRelay` group-scoped filtering | P2a-01, P2a-08 | M |
| P2a-10 | Management API `/api/workers/groups` + CLI + docs | P2a-08 | M |
| P2a-11 | `WorkerConfigLoader` TOML parsing for new fields | P2a-04 | S |

**Estimated total P2a:** 3-5 days

### P2b: Spot Pool

| Task | Description | Dependencies | Complexity |
|------|------------|--------------|------------|
| P2b-01 | `WorkerPoolType` enum | None | S |
| P2b-02 | `WorkerMemberInfo` (pool type from NodeId) | P2b-01 | S |
| P2b-03 | `AllocationPool` extension (spotWorkers) | P2b-01, P2b-02 | M |
| P2b-04 | `PlacementPolicy.SPOT_ELIGIBLE` | P2b-01 | S |
| P2b-05 | `PreemptionDetector` SPI + `LocalPreemptionDetector` | None | M |
| P2b-06 | `PreemptionHandler` (SWIM leave, drain, exit) | P2b-05, P2a-06 | M |
| P2b-07 | CDM spot-aware allocation | P2b-02, P2b-03, P2b-04 | L |
| P2b-08 | Spot-prefixed NodeId convention | P2b-02, P2a-05 | S |
| P2b-09 | Management API spot pool status | P2b-03 | S |

**Estimated total P2b:** 3-4 days

### P2c: KV-Store Migration (Flag-Gated)

| Task | Description | Dependencies | Complexity |
|------|------------|--------------|------------|
| P2c-01 | `CoreEndpointReporter` | P2a-08 | M |
| P2c-02 | `SliceInvoker` query order branch (flag-gated) | P2c-01 | M |
| P2c-03 | Management API migration toggle + CLI + docs | P2c-02 | S |
| P2c-04 | Integration tests for both flag states | P2c-02, P2c-03 | M |

**Estimated total P2c:** 2-3 days

### Worker Capabilities (Cross-Cutting)

| Task | Description | Dependencies | Complexity |
|------|------------|--------------|------------|
| WC-01 | `ScheduledTaskManager` worker-mode factory | P2a-06 | M |
| WC-02 | Wire scheduled tasks in `WorkerNode` | WC-01 | S |
| WC-03 | Wire `TopicSubscriptionRegistry` + `PublisherFactory` in worker | P2a-06 | M |
| WC-04 | Forge tests for worker scheduled tasks | WC-02 | M |
| WC-05 | Forge tests for worker pub/sub | WC-03 | M |

**Estimated total WC:** 2-3 days

### Grand Total: 10-15 days

## Testing Strategy

### Forge Tests (Unit/Integration)

| Test | Validates | File Location |
|------|-----------|---------------|
| `WorkerGroupIdTest` | Validation, pattern matching | `aether/aether-config/src/test/java/.../config/WorkerGroupIdTest.java` |
| `ZoneExtractorTest` | Zone parsing from NodeId | `aether/aether-config/src/test/java/.../config/ZoneExtractorTest.java` |
| `GroupAssignmentTest` | Consistent hashing, splitting, distribution uniformity | `aether/worker/src/test/java/.../worker/group/GroupAssignmentTest.java` |
| `GroupAssignmentScaleTest` | 10K nodes, group count, reshuffling on split | `aether/worker/src/test/java/.../worker/group/GroupAssignmentScaleTest.java` |
| `DecisionRelayFilterTest` | Group-scoped decision filtering | `aether/worker/src/test/java/.../worker/governor/DecisionRelayFilterTest.java` |
| `AllocationPoolSpotTest` | Spot-aware node selection by policy | `aether/aether-deployment/src/test/java/.../deployment/cluster/AllocationPoolSpotTest.java` |
| `PreemptionHandlerTest` | Shutdown sequence on preemption signal | `aether/worker/src/test/java/.../worker/preemption/PreemptionHandlerTest.java` |
| `CoreEndpointReporterTest` | Core-to-registry sync | `aether/aether-invoke/src/test/java/.../endpoint/CoreEndpointReporterTest.java` |
| `SliceInvokerMigrationTest` | Flag-gated query order | `aether/aether-invoke/src/test/java/.../invoke/SliceInvokerMigrationTest.java` |
| `WorkerScheduledTaskTest` | Governor-as-leader scheduled task execution | `aether/aether-invoke/src/test/java/.../invoke/WorkerScheduledTaskTest.java` |
| `WorkerPubSubTest` | Pub/sub routing through workers | Forge: `aether/forge/forge-tests/src/test/java/.../forge/WorkerPubSubTest.java` |

### E2E Tests (Testcontainers)

| Test | Scenario | File Location |
|------|----------|---------------|
| `WorkerGroupFormationE2ETest` | 3 core + 6 workers (2 zones) -> verifies 2 groups formed | `aether/e2e-tests/src/test/java/.../e2e/WorkerGroupFormationE2ETest.java` |
| `WorkerGroupSplitE2ETest` | Start with 3 workers, scale to 60 -> verify group split | `aether/e2e-tests/src/test/java/.../e2e/WorkerGroupSplitE2ETest.java` |
| `SpotWorkerPreemptionE2ETest` | Deploy to spot workers, kill one -> verify re-allocation | `aether/e2e-tests/src/test/java/.../e2e/SpotWorkerPreemptionE2ETest.java` |
| `KVStoreMigrationE2ETest` | Toggle flag, verify endpoint resolution works in both modes | `aether/e2e-tests/src/test/java/.../e2e/KVStoreMigrationE2ETest.java` |
| `WorkerScheduledTaskE2ETest` | Deploy scheduled task to workers, verify execution | `aether/e2e-tests/src/test/java/.../e2e/WorkerScheduledTaskE2ETest.java` |

### Test Naming Convention

All tests follow the project convention: `methodName_scenario_expectation()`

Examples:
```java
void assignGroup_singleZoneBelowMax_returnsSingleGroup()
void assignGroup_tenThousandNodes_distributesUniformly()
void extractZone_validZonePrefix_returnsZone()
void extractZone_randomNodeId_returnsDefault()
void nodesForPolicy_spotEligible_returnsSpotWorkersFirst()
void selectCoreOrWorkerEndpoint_flagEnabled_queriesRegistryFirst()
```

## Migration and Rollback

### P2a Migration

**Forward:** Workers with new config (`zone`, `maxGroupSize`) join zone-prefixed groups. Workers with old config (no zone) join the `default` zone and continue to work as in Phase 1.

**Rollback:** Remove zone/maxGroupSize from config. Workers revert to `default` zone. Single flat group resumes.

### P2b Migration

**Forward:** Spot workers are provisioned via `ComputeProvider` with `InstanceType.SPOT`. They join with spot-prefixed NodeIds. CDM recognizes them and applies spot-aware allocation.

**Rollback:** Stop provisioning spot instances. Existing spot workers drain naturally or are terminated. CDM falls back to main workers only.

### P2c Migration (Flag-Gated)

| Step | Action |
|------|--------|
| 1 | Deploy code with flag defaulting to OFF |
| 2 | `CoreEndpointReporter` starts syncing (always runs, populates registry) |
| 3 | Enable flag via management API: `POST /api/workers/migration/enable` |
| 4 | Monitor: verify endpoint resolution latency is stable |
| 5 | If issues: `POST /api/workers/migration/disable` (instant rollback) |
| 6 | After validation: make flag default to ON in config |
| 7 | Future phase: remove old path entirely |

**Data safety:** Both registries remain populated at all times. The flag only controls query order. No data is lost or corrupted by toggling.

## Open Questions

| # | Question | Status |
|---|----------|--------|
| 1 | Should group split be automatic or operator-triggered? | [ASSUMPTION] Automatic, triggered by governor when membership exceeds `maxGroupSize`. |
| 2 | Health report interval for 200 governors? | [ASSUMPTION] 1 second, same as Phase 1. Core can handle 200 reports/sec. |
| 3 | Worker-to-worker direct invocation within a group? | Design Decision #5 says always through core. Deferred optimization. |

## References

### Internal References (Phase 1 Implementation)

- `aether/worker/src/main/java/org/pragmatica/aether/worker/WorkerNode.java` -- Worker node lifecycle and SWIM integration
- `aether/worker/src/main/java/org/pragmatica/aether/worker/governor/GovernorElection.java` -- Deterministic governor election
- `aether/worker/src/main/java/org/pragmatica/aether/worker/governor/DecisionRelay.java` -- Decision relay from core to workers
- `aether/worker/src/main/java/org/pragmatica/aether/worker/mutation/MutationForwarder.java` -- Mutation forwarding to core
- `aether/worker/src/main/java/org/pragmatica/aether/worker/network/WorkerNetwork.java` -- TCP inter-worker communication
- `aether/worker/src/main/java/org/pragmatica/aether/worker/bootstrap/WorkerBootstrap.java` -- KV state bootstrap
- `aether/aether-invoke/src/main/java/org/pragmatica/aether/endpoint/WorkerEndpointRegistry.java` -- Worker endpoint registry
- `aether/aether-invoke/src/main/java/org/pragmatica/aether/endpoint/WorkerGroupHealthReport.java` -- Health report record
- `aether/aether-invoke/src/main/java/org/pragmatica/aether/endpoint/WorkerEndpointEntry.java` -- Endpoint entry record
- `aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/SliceInvoker.java` -- Endpoint resolution and invocation
- `aether/aether-invoke/src/main/java/org/pragmatica/aether/endpoint/EndpointRegistry.java` -- Consensus-driven endpoint registry
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterDeploymentManager.java` -- CDM orchestration
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/AllocationPool.java` -- Allocation pool
- `aether/aether-config/src/main/java/org/pragmatica/aether/config/PlacementPolicy.java` -- Placement policy enum
- `aether/aether-config/src/main/java/org/pragmatica/aether/config/WorkerConfig.java` -- Worker configuration
- `aether/node/src/main/java/org/pragmatica/aether/node/health/CoreSwimHealthDetector.java` -- Core SWIM health detection
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/WorkerRoutes.java` -- Worker management API routes
- `aether/slice/src/main/java/org/pragmatica/aether/artifact/GroupId.java` -- Maven artifact GroupId (distinct from WorkerGroupId)
- `integrations/consensus/src/main/java/org/pragmatica/consensus/NodeId.java` -- Node identity
- `integrations/swim/src/main/java/org/pragmatica/swim/SwimMember.java` -- SWIM member record
- `integrations/swim/src/main/java/org/pragmatica/swim/SwimProtocol.java` -- SWIM protocol implementation

### Cloud Integration SPI

- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/EnvironmentIntegration.java` -- Faceted SPI entry point
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/ComputeProvider.java` -- Compute instance lifecycle
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/InstanceType.java` -- OnDemand/Spot instance types
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/InstanceInfo.java` -- Instance info record

### Scheduled Tasks and Pub/Sub

- `aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/ScheduledTaskManager.java` -- Timer lifecycle manager
- `aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/ScheduledTaskRegistry.java` -- Task registry
- `aether/aether-invoke/src/main/java/org/pragmatica/aether/invoke/TopicPublisher.java` -- Pub/sub publisher
- `aether/aether-invoke/src/main/java/org/pragmatica/aether/endpoint/TopicSubscriptionRegistry.java` -- Topic subscription watcher

### Testing Infrastructure

- `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/ForgeTestBase.java` -- Forge test base class
- `aether/e2e-tests/src/test/java/org/pragmatica/aether/e2e/AbstractE2ETest.java` -- E2E test base class
- `aether/e2e-tests/src/test/java/org/pragmatica/aether/e2e/SwimDetectionE2ETest.java` -- Existing SWIM E2E test

### Technical References

- [Jump Consistent Hash](https://arxiv.org/abs/1406.2294) -- Lamping & Veach, Google. Used for group assignment.
- [SWIM Protocol](https://www.cs.cornell.edu/projects/Quicksilver/public_pdfs/SWIM.pdf) -- Scalable Weakly-consistent Infection-style Process Group Membership Protocol.
