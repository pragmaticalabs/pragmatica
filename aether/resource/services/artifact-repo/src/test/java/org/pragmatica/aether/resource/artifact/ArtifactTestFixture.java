package org.pragmatica.aether.resource.artifact;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.storage.DhtStorageTier;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// AF, the two-store artifact fixture of design 420 (Q5, built in PR-0b).
///
/// One [ReplicatedTestDht] (3 replicas, read quorum 2), one shared [ArtifactVersionIndex], and two stores built with
/// the package-private constructor that takes the resolve-leg spans, the archive policy, the clock and the index. Each
/// store sits over its own [StorageInstance] of a memory tier plus the CONTENT-addressed [DhtStorageTier], so a block
/// "dropped" from every replica leaves store 2 with no private copy and its read reaches the DHT. The clock is test
/// controlled, so an archive arm advances it past the retention period.
final class ArtifactTestFixture {
    static final int CHUNK = 64 * 1024;
    static final long DAY = 24L * 60 * 60 * 1000;
    static final String BLOCKS = "artifacts-blocks/";

    final Artifact v1 = Artifact.artifact("org.example:lib:1.0.0").unwrap();
    final Artifact v2 = Artifact.artifact("org.example:lib:2.0.0").unwrap();
    final byte[] bytesA = pattern(CHUNK, 1);
    final byte[] bytesB = pattern(CHUNK / 2, 9);

    final ReplicatedTestDht dht = new ReplicatedTestDht(3, 2);
    final AtomicLong now = new AtomicLong(1_000_000L);
    final ArtifactVersionIndex index = ArtifactVersionIndex.inMemory();
    final ArtifactStore store1;
    final ArtifactStore store2;

    /// Leg spans in milliseconds: the base, the per-chunk increment and the ceiling.
    ArtifactTestFixture(int base, int perChunk, int ceiling) {
        store1 = store(base, perChunk, ceiling);
        store2 = store(base, perChunk, ceiling);
    }

    static ArtifactTestFixture artifactTestFixture() {
        return new ArtifactTestFixture(2000, 1000, 5000);
    }

    private ArtifactStore store(int base, int perChunk, int ceiling) {
        var storage = StorageInstance.storageInstance("artifacts",
                                                      List.of(MemoryTier.memoryTier(64L * 1024 * 1024),
                                                              DhtStorageTier.dhtStorageTier(dht,
                                                                                            "artifacts-blocks",
                                                                                            "artifacts",
                                                                                            Promise.UNIT,
                                                                                            DhtStorageTier.Addressing.CONTENT)));

        return new ArtifactStoreImpl(dht,
                                     storage,
                                     dht.config().retryPolicy(),
                                     timeSpan(base).millis(),
                                     timeSpan(perChunk).millis(),
                                     timeSpan(ceiling).millis(),
                                     _ -> {},
                                     ArtifactStore.ArchivePolicy.DEFAULT,
                                     now::get,
                                     index);
    }

    /// Remove every block key from the given replicas.
    @Contract
    void dropBlocks(int... replicaIndexes) {
        for (var i : replicaIndexes) {
            dht.replicas.get(i).keySet().removeIf(k -> k.startsWith(BLOCKS));
        }
    }

    /// Remove every key ending in `suffix` from every replica.
    @Contract
    void dropKeys(String suffix) {
        dht.replicas.forEach(r -> r.keySet().removeIf(k -> k.endsWith(suffix)));
    }

    boolean blocksHeld() {
        return dht.union().keySet().stream().anyMatch(k -> k.startsWith(BLOCKS));
    }

    boolean keyHeld(String suffix) {
        return dht.union().keySet().stream().anyMatch(k -> k.endsWith(suffix));
    }

    @Contract
    void advanceDays(int days) {
        now.addAndGet(days * DAY);
    }

    static String blockKey(byte[] chunk) {
        return BLOCKS + BlockId.blockId(chunk).unwrap().hexString();
    }

    static byte[] pattern(int n, int seed) {
        var out = new byte[n];

        for (var i = 0; i < n; i++) {
            out[i] = (byte) ((i * 7 + seed * 31 + i / 251) % 251);
        }

        return out;
    }

    @Contract
    void deployOk(ArtifactStore store, Artifact artifact, byte[] bytes) {
        var result = store.deploy(artifact, bytes).await();

        assertThat(result.isSuccess()).as("PRECONDITION deploy of " + artifact.asString() + ": " + result).isTrue();
    }

    /// The failure of a result, empty on success.
    static Option<Cause> causeOf(Result<?> result) {
        return result.fold(Option::some, _ -> Option.none());
    }

    /// The simple type name of a result's failure, `"success"` on success.
    static String failureName(Result<?> result) {
        return causeOf(result).map(cause -> cause.getClass().getSimpleName()).or("success");
    }
}
