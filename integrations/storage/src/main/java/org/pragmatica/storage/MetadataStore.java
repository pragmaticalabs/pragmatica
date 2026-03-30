package org.pragmatica.storage;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

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
    void createLifecycle(BlockLifecycle lifecycle);

    /// Atomically claim a block ID with a sentinel lifecycle entry.
    /// Returns true if this call won the race (no prior entry existed).
    boolean claimBlock(BlockId blockId, BlockLifecycle sentinel);

    /// Atomically update lifecycle metadata using the provided function.
    /// Returns the updated lifecycle, or none if the block does not exist.
    Option<BlockLifecycle> computeLifecycle(BlockId blockId, UnaryOperator<BlockLifecycle> updater);

    /// Remove lifecycle metadata for a block.
    void removeLifecycle(BlockId blockId);

    /// Store a named reference mapping refName to blockId.
    void putRef(String refName, BlockId blockId);

    /// Resolve a named reference to its block ID.
    Option<BlockId> resolveRef(String refName);

    /// Remove a named reference. Returns the previously mapped block ID, if any.
    Option<BlockId> removeRef(String refName);

    /// Check whether lifecycle metadata exists for a block.
    boolean containsBlock(BlockId blockId);

    /// The name of the storage instance this metadata store belongs to.
    String instanceName();

    /// List all block lifecycle entries (for snapshotting).
    List<BlockLifecycle> listAllLifecycles();

    /// List all named reference mappings (for snapshotting).
    Map<String, BlockId> listAllRefs();

    /// Current mutation epoch (monotonically increasing).
    long currentEpoch();

    /// Restore lifecycle entries from a snapshot (bulk load).
    void restoreLifecycles(List<BlockLifecycle> entries);

    /// Restore named references from a snapshot (bulk load).
    void restoreRefs(Map<String, BlockId> refs);
}
