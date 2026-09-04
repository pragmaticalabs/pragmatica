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

    /// Retrieve lifecycle metadata for a block.
    Option<BlockLifecycle> getLifecycle(BlockId blockId);

    /// Store lifecycle metadata for a newly created block.
    @Contract
    void createLifecycle(BlockLifecycle lifecycle);

    /// Atomically claim a block ID with a sentinel lifecycle entry.
    /// Returns true if this call won the race (no prior entry existed).
    boolean claimBlock(BlockId blockId, BlockLifecycle sentinel);
    /// Conditionally release a previously made claim. Removes the lifecycle entry
    /// for the block only if it is still mapped to exactly the given sentinel
    /// (i.e. the write never progressed past the claim). If a concurrent caller
    /// advanced the lifecycle past the sentinel, the entry is preserved.
    /// Returns true if the claim was released.
    boolean releaseClaim(BlockId blockId, BlockLifecycle sentinel);
    /// Atomically update lifecycle metadata using the provided function.
    /// Returns the updated lifecycle, or none if the block does not exist.
    Option<BlockLifecycle> computeLifecycle(BlockId blockId, UnaryOperator<BlockLifecycle> updater);

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
    /// List all block lifecycle entries (for snapshotting).
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
}
