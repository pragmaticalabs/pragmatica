package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// #737 -- pins the ordering [DefaultStorageInstance#replaceRef] depends on: the new target's reference
/// count must be credited BEFORE the superseded target's is released. Reversed, a concurrent GC scan
/// could observe the superseded block at a floor-clamped zero while it is, at that same instant, still
/// the only thing the new write's caller can reach. This is a mechanism test, not a race test -- it pins
/// the call order a single-threaded execution produces, which is exactly what a race would need disturbed.
class DefaultStorageInstanceReplaceRefOrderingTest {

    private static final long ONE_GB = 1024 * 1024 * 1024L;

    @Test
    void replaceRef_incrementsNewBlock_beforeDecrementingSuperseded() {
        var recording = new RecordingMetadataStore(MetadataStore.inMemoryMetadataStore("order-test"));
        var storage = StorageInstance.storageInstance("order-test",
                                                       List.of(MemoryTier.memoryTier(ONE_GB)),
                                                       recording);

        var contentA = "content-a".getBytes(StandardCharsets.UTF_8);
        var contentB = "content-b".getBytes(StandardCharsets.UTF_8);

        var idA = await(storage.put(contentA));
        var idB = await(storage.put(contentB));

        // Gives the ref something to supersede. No decrement here -- the ref did not exist before.
        await(storage.replaceRef("cursor-ref", contentA));
        recording.events.clear();

        // A and B both already exist, so B's credit is a dedup computeLifecycle call -- symmetric with
        // the decrement below -- and this replaceRef supersedes A.
        await(storage.replaceRef("cursor-ref", contentB));

        assertThat(recording.events)
                .as("one dedup credit for B and one release of the superseded A -- nothing else on this"
                    + " path touches a refcount")
                .hasSize(2);

        var first = recording.events.get(0);
        var second = recording.events.get(1);

        assertThat(first.increment()).as("the new target's credit must land first").isTrue();
        assertThat(first.blockId()).isEqualTo(idB);
        assertThat(second.increment()).as("the superseded target's release must land second").isFalse();
        assertThat(second.blockId()).isEqualTo(idA);
    }

    private static BlockId await(Promise<BlockId> promise) {
        return promise.await()
                      .fold(cause -> {
                          org.junit.jupiter.api.Assertions.fail("Expected success: " + cause.message());
                          return null;
                      }, id -> id);
    }

    /// Delegates every [MetadataStore] operation to a real in-memory store, recording each
    /// [#computeLifecycle] call as an increment or decrement (by comparing refCount before/after) in
    /// invocation order. Everything else passes straight through.
    private static final class RecordingMetadataStore implements MetadataStore {
        record Event(BlockId blockId, boolean increment) {}

        private final MetadataStore delegate;
        private final List<Event> events = new ArrayList<>();

        private RecordingMetadataStore(MetadataStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Option<BlockLifecycle> computeLifecycle(BlockId blockId, UnaryOperator<BlockLifecycle> updater) {
            var before = delegate.getLifecycle(blockId).map(BlockLifecycle::refCount).or(0);
            var result = delegate.computeLifecycle(blockId, updater);

            result.onPresent(lc -> events.add(new Event(blockId, lc.refCount() > before)));

            return result;
        }

        @Override
        public Option<BlockLifecycle> getLifecycle(BlockId blockId) {
            return delegate.getLifecycle(blockId);
        }

        @Override
        @Contract
        public void createLifecycle(BlockLifecycle lifecycle) {
            delegate.createLifecycle(lifecycle);
        }

        @Override
        public boolean claimBlock(BlockId blockId, BlockLifecycle sentinel) {
            return delegate.claimBlock(blockId, sentinel);
        }

        @Override
        public boolean releaseClaim(BlockId blockId, BlockLifecycle sentinel) {
            return delegate.releaseClaim(blockId, sentinel);
        }

        @Override
        @Contract
        public void removeLifecycle(BlockId blockId) {
            delegate.removeLifecycle(blockId);
        }

        @Override
        @Contract
        public void putRef(String refName, BlockId blockId) {
            delegate.putRef(refName, blockId);
        }

        @Override
        public Option<BlockId> resolveRef(String refName) {
            return delegate.resolveRef(refName);
        }

        @Override
        public Option<BlockId> removeRef(String refName) {
            return delegate.removeRef(refName);
        }

        @Override
        public Option<BlockId> replaceRef(String refName, BlockId blockId) {
            return delegate.replaceRef(refName, blockId);
        }

        @Override
        public boolean containsBlock(BlockId blockId) {
            return delegate.containsBlock(blockId);
        }

        @Override
        public String instanceName() {
            return delegate.instanceName();
        }

        @Override
        public List<BlockLifecycle> listBlocksByTier(TierLevel tier) {
            return delegate.listBlocksByTier(tier);
        }

        @Override
        public List<BlockLifecycle> listAllLifecycles() {
            return delegate.listAllLifecycles();
        }

        @Override
        public Map<String, BlockId> listAllRefs() {
            return delegate.listAllRefs();
        }

        @Override
        public long currentEpoch() {
            return delegate.currentEpoch();
        }

        @Override
        @Contract
        public void restoreLifecycles(List<BlockLifecycle> entries) {
            delegate.restoreLifecycles(entries);
        }

        @Override
        @Contract
        public void restoreRefs(Map<String, BlockId> refs) {
            delegate.restoreRefs(refs);
        }
    }
}
