package org.pragmatica.storage;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// Abstraction for block lifecycle metadata and named references.
/// Implementations may use in-memory maps, databases, or distributed stores.
public interface MetadataStore {
    /// Create an in-memory metadata store suitable for testing and single-node deployments.
    static MetadataStore inMemoryMetadataStore(String instanceName) {
        return InMemoryMetadataStore.inMemoryMetadataStore(instanceName);
    }

    /// As above, with the given [AccessTracker] (a test that controls the clock and the warm-up window).
    static MetadataStore inMemoryMetadataStore(String instanceName, AccessTracker accessTracker) {
        return InMemoryMetadataStore.inMemoryMetadataStore(instanceName, accessTracker);
    }

    /// What reads did to blocks since boot (C-08): in memory, approximate, never snapshotted, and not part of
    /// any block record. Writers of the record never see it and readers never write the record.
    AccessTracker accessTracker();
    /// Retrieve lifecycle metadata for a block.
    Option<BlockLifecycle> getLifecycle(BlockId blockId);

    /// Store lifecycle metadata for a newly created block.
    @Contract
    void createLifecycle(BlockLifecycle lifecycle);

    /// Atomically claim a block ID with a sentinel lifecycle entry.
    /// Returns true if this call won the race (no prior entry existed). A cache-only marker
    /// ([BlockLifecycle#cacheOnly]) is not a prior entry for a claim of a real block: the claim replaces it,
    /// keeping the tiers the marker was tracking. A claim for a marker, itself, never replaces anything.
    /// A claim for a cache-only marker is an observation, not a mutation: it does not advance
    /// [#currentEpoch] (a read that finds a block this node did not write creates one, and a read never
    /// triggers a snapshot).
    boolean claimBlock(BlockId blockId, BlockLifecycle sentinel);
    /// [#claimBlock] for a write that has not yet reached its ack point. What a record asserts is "durably
    /// held per the ack point", so until [#acknowledgeClaim] the record is a claim only: it is not listed by
    /// [#listAllLifecycles] (so no snapshot captures it) and [#isUnacknowledged] reports it, so that
    /// presence checks do not read it as a block. The claim is held for the write and the two are paired by
    /// the caller; a failed write releases it ([#releaseClaim], [#removeLifecycle]).
    boolean claimUnacknowledged(BlockId blockId, BlockLifecycle sentinel);

    /// The write reached its ack point: the record from [#claimUnacknowledged] now asserts what it says.
    /// Advances [#currentEpoch], since the record becomes visible to snapshots. A no-op for a block with no
    /// unacknowledged claim.
    @Contract
    void acknowledgeClaim(BlockId blockId);

    /// Whether the block's record is a claim whose write has not reached its ack point.
    boolean isUnacknowledged(BlockId blockId);
    /// Conditionally release a previously made claim. Removes the lifecycle entry
    /// for the block only if it is still mapped to exactly the given sentinel
    /// (i.e. the write never progressed past the claim). If a concurrent caller
    /// advanced the lifecycle past the sentinel, the entry is preserved.
    /// Returns true if the claim was released.
    ///
    /// The same compare-and-remove is what GC uses to take an orphan record it scanned (#801):
    /// `sentinel` is then the record as scanned, and a `false` means something touched the block
    /// after the scan and it must not be collected on that verdict.
    ///
    /// Releasing a cache-only marker does not advance [#currentEpoch], as claiming one does not.
    boolean releaseClaim(BlockId blockId, BlockLifecycle sentinel);
    /// Atomically update lifecycle metadata using the provided function.
    /// Returns the updated lifecycle, or none if the block does not exist.
    Option<BlockLifecycle> computeLifecycle(BlockId blockId, UnaryOperator<BlockLifecycle> updater);
    /// [#computeLifecycle] for an observation -- what a read saw or did -- rather than a change to the block:
    /// the record is updated but [#currentEpoch] does not move, so reads never trigger a snapshot. What it
    /// updates (a tier found holding the block) is approximate: it is persisted when some snapshot is taken for
    /// another reason, and lost with the process otherwise. Read access itself is not recorded here but in
    /// [#accessTracker].
    Option<BlockLifecycle> observeLifecycle(BlockId blockId, UnaryOperator<BlockLifecycle> updater);

    /// Remove lifecycle metadata for a block.
    @Contract
    void removeLifecycle(BlockId blockId);

    /// Store a named reference mapping refName to blockId.
    @Contract
    void putRef(String refName, BlockId blockId);

    /// Resolve a named reference to its block ID.
    Option<BlockId> resolveRef(String refName);
    /// Remove a named reference. Returns the previously mapped block ID, if any.
    Option<BlockId> removeRef(String refName);
    /// Atomically point `refName` at `blockId`, returning whatever it previously pointed to, if any.
    /// Pure ref-pointer swap -- does not touch reference counts; callers own the counting.
    Option<BlockId> replaceRef(String refName, BlockId blockId);
    /// Check whether lifecycle metadata exists for a block.
    boolean containsBlock(BlockId blockId);
    /// The name of the storage instance this metadata store belongs to.
    String instanceName();
    /// List blocks present in a specific tier.
    List<BlockLifecycle> listBlocksByTier(TierLevel tier);
    /// List all block lifecycle entries (for snapshotting) -- the acknowledged ones: a claim whose write has
    /// not reached its ack point ([#claimUnacknowledged]) is not listed.
    List<BlockLifecycle> listAllLifecycles();
    /// List all named reference mappings (for snapshotting).
    Map<String, BlockId> listAllRefs();
    /// Current mutation epoch (monotonically increasing).
    long currentEpoch();

    /// Restore lifecycle entries from a snapshot (bulk load).
    @Contract
    void restoreLifecycles(List<BlockLifecycle> entries);

    /// Restore named references from a snapshot (bulk load).
    @Contract
    void restoreRefs(Map<String, BlockId> refs);

    /// Restore the mutation epoch from a snapshot (bulk load), so a restarted store continues the
    /// pre-restart sequence instead of counting up from zero (#1012). Raises the epoch to the
    /// snapshot's value and NEVER lowers it: the epoch also names snapshot files, and a store that
    /// has already moved past the snapshot must not be rewound into reusing names it has retired.
    @Contract
    void restoreEpoch(long epoch);
}
