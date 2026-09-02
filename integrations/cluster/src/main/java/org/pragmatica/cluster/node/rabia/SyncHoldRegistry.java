package org.pragmatica.cluster.node.rabia;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;


/// Leader-side tracker of "currently consuming KV-sync snapshot" peers. Used by the convergence
/// reconciler (Phase 4) to protect busy syncing nodes from force-decommission decisions made
/// while their SWIM heartbeats lag behind snapshot ingest.
///
/// `register(peer, deadlineMs)` marks a peer as actively syncing through the deadline; `clear(peer)`
/// removes the entry on snapshot completion (success or failure). `activeHolds()` returns the set
/// of peers whose deadline has not yet elapsed — entries past their deadline are treated as expired
/// even if `clear(peer)` has not yet run (defence against forgotten/dropped clears).
///
/// All operations are thread-safe; the underlying map is a `ConcurrentHashMap`. Multiple
/// `register` calls for the same peer simply overwrite the deadline.
///
/// See `aether/docs/specs/cluster-convergence-reconciler-spec.md` §5.4.
public final class SyncHoldRegistry {
    private final ConcurrentHashMap<NodeId, Long> holds = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    private SyncHoldRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    /// Build a registry using the supplied millisecond clock (typically `System::currentTimeMillis`
    /// or an HLC-backed source — see `MembershipFsmConfig` clock injection pattern).
    public static SyncHoldRegistry syncHoldRegistry(LongSupplier clock) {
        return new SyncHoldRegistry(clock);
    }

    /// Build a registry backed by `System.currentTimeMillis()`. Acceptable for RC1 per spec
    /// §5.4: handovers themselves stall the reconciler long enough that wall-clock drift is
    /// not a correctness hazard.
    public static SyncHoldRegistry syncHoldRegistry() {
        return new SyncHoldRegistry(System::currentTimeMillis);
    }

    /// Mark `peer` as actively syncing through `deadlineMs` (absolute epoch ms). Multiple
    /// invocations for the same peer overwrite the deadline.
    @Contract
    public void register(NodeId peer, long deadlineMs) {
        if (peer == null) {
            return;
        }

        holds.put(peer, deadlineMs);
    }

    /// Clear any active hold for `peer`. Safe to call when no hold is present.
    @Contract
    public void clear(NodeId peer) {
        if (peer == null) {
            return;
        }

        holds.remove(peer);
    }

    /// Snapshot of peers whose deadline has not yet elapsed. Stale entries (deadline ≤ now)
    /// are filtered out — the registry self-prunes on read so a forgotten `clear` does not
    /// permanently shield a peer.
    public Set<NodeId> activeHolds() {
        var now = clock.getAsLong();
        var result = new HashSet<NodeId>();

        holds.forEach((peer, deadline) -> partitionByDeadline(peer, deadline, now, result));

        return Set.copyOf(result);
    }

    /// Per-entry filter for `activeHolds()`. Expired entries are pruned in-place via
    /// `remove(key, expected)` — concurrent re-registration is safe because the conditional
    /// remove only fires when the deadline still matches what was iterated.
    private void partitionByDeadline(NodeId peer, Long deadline, long now, HashSet<NodeId> result) {
        if (deadline > now) {
            result.add(peer);
        } else {
            holds.remove(peer, deadline);
        }
    }

    /// Total number of in-flight hold entries (including those that may have expired but not
    /// yet been pruned). Primarily useful for tests and metrics.
    public int size() {
        return holds.size();
    }
}
