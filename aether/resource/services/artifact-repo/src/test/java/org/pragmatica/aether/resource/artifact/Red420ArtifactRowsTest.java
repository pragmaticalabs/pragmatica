// EVIDENCE ONLY (#420 red rows, design r7 Q5). Real assertions at pin ba2474d1a2. Never merged.
package org.pragmatica.aether.resource.artifact;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.storage.DhtStorageTier;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.BlockId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// AF, the two-store artifact fixture of design 420 r7 Q5.
class Red420ArtifactRowsTest {
    private static final int CHUNK = 64 * 1024;
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final String BLOCKS = "artifacts-blocks/";

    private final Artifact v1 = Artifact.artifact("org.example:lib:1.0.0").unwrap();
    private final Artifact v2 = Artifact.artifact("org.example:lib:2.0.0").unwrap();
    private final byte[] bytesA = pattern(CHUNK, 1);
    private final byte[] bytesB = pattern(CHUNK / 2, 9);

    private ReplicatedTestDht dht;
    private AtomicLong now;
    private ArtifactVersionIndex index;
    private ArtifactStore store1;
    private ArtifactStore store2;

    private void fixture(int base, int perChunk, int ceiling) {
        dht = new ReplicatedTestDht(3, 2);
        now = new AtomicLong(1_000_000L);
        index = ArtifactVersionIndex.inMemory();
        store1 = store(base, perChunk, ceiling);
        store2 = store(base, perChunk, ceiling);
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

    private void dropBlocks(int... replicaIndexes) {
        for (var i : replicaIndexes) {
            dht.replicas.get(i).keySet().removeIf(k -> k.startsWith(BLOCKS));
        }
    }

    private void dropKeys(String suffix) {
        dht.replicas.forEach(r -> r.keySet().removeIf(k -> k.endsWith(suffix)));
    }

    private boolean blocksHeld() {
        return dht.union().keySet().stream().anyMatch(k -> k.startsWith(BLOCKS));
    }

    private static byte[] pattern(int n, int seed) {
        var out = new byte[n];

        for (var i = 0; i < n; i++) {
            out[i] = (byte) ((i * 7 + seed * 31 + i / 251) % 251);
        }

        return out;
    }

    private void deployOk(ArtifactStore s, Artifact a, byte[] bytes) {
        var r = s.deploy(a, bytes).await();

        assertThat(r.isSuccess()).as("PRECONDITION deploy of " + a.asString() + ": " + r).isTrue();
    }

    // CONTROL (not a row): the fixture's store 2 can resolve what store 1 deployed, with nothing dropped.
    @Test
    void control_store2ResolvesWhenNothingIsDropped() {
        fixture(2000, 1000, 5000);
        deployOk(store1, v1, bytesA);

        var r = store2.resolve(v1).await();

        assertThat(r.isSuccess()).as("CONTROL store 2 resolve: " + r).isTrue();
        assertThat(r.unwrap()).isEqualTo(bytesA);
    }

    @Test
    void T1_identicalRePut_reassertsTheBlock() {
        fixture(2000, 1000, 5000);
        deployOk(store1, v1, bytesA);
        assertThat(blocksHeld()).as("PRECONDITION block written by first deploy").isTrue();
        assertThat(store2.metadata(v1).await().unwrap().isPresent()).as("PRECONDITION store 2 sees meta").isTrue();
        dropBlocks(0, 1, 2);
        assertThat(blocksHeld()).as("PRECONDITION block dropped from every replica").isFalse();

        var again = store1.deploy(v1, bytesA).await();
        assertThat(again.isSuccess()).as("PRECONDITION identical re-PUT accepted: " + again).isTrue();

        var r = store2.resolve(v1).await();

        assertThat(r.isSuccess()).as("T1 store 2 resolve after identical re-PUT: " + r).isTrue();
        assertThat(r.unwrap()).isEqualTo(bytesA);
    }

    @Test
    void T2_newDeployChunkEqualToADroppedOne_isWrittenToTheSharedTier() {
        fixture(2000, 1000, 5000);
        deployOk(store1, v1, bytesA);
        var chunkKey = BLOCKS + BlockId.blockId(bytesA).unwrap().hexString();
        assertThat(dht.union()).as("PRECONDITION chunk c written by deploy A").containsKey(chunkKey);
        dropBlocks(0, 1, 2);
        assertThat(dht.union()).as("PRECONDITION chunk c dropped").doesNotContainKey(chunkKey);

        var bFirstEqualsC = Arrays.copyOf(bytesA, CHUNK + 1000);
        System.arraycopy(pattern(1000, 5), 0, bFirstEqualsC, CHUNK, 1000);
        deployOk(store1, v2, bFirstEqualsC);

        assertThat(dht.union()).as("T2 dht.union() holds c after deploy B").containsKey(chunkKey);
    }

    @Test
    void T3_failedFirstDeploy_doesNotBlockADifferentLaterDeploy() {
        fixture(2000, 1000, 5000);
        dht.putFailure = name -> name.startsWith(BLOCKS) ? Option.some(Causes.cause("chunk refused")) : Option.none();

        var x = store1.deploy(v1, bytesA).await();
        assertThat(x.isFailure()).as("PRECONDITION deploy X fails on its chunks: " + x).isTrue();
        x.onFailure(c -> assertThat(c.message()).as("PRECONDITION X failed for the chunk refusal").contains("chunk refused"));
        dht.putFailure = _ -> Option.none();

        var y = store1.deploy(v1, bytesB).await();

        assertThat(y.isSuccess()).as("T3 deploy Y of other bytes: " + y).isTrue();
    }

    @Test
    void T5_identityReadersReadTheIndexNotTheMetaKey() {
        fixture(2000, 1000, 5000);
        deployOk(store1, v1, bytesA);
        dropKeys("/meta");
        assertThat(dht.union().keySet().stream().anyMatch(k -> k.endsWith("/meta"))).as("PRECONDITION meta key dropped").isFalse();
        now.addAndGet(8 * DAY);
        var soft = new org.assertj.core.api.SoftAssertions();

        var resolved = store2.resolve(v1).await();
        soft.assertThat(resolved.isSuccess()).as("T5 resolve: " + resolved).isTrue();
        soft.assertThat(store2.exists(v1).await().unwrap()).as("T5 exists").isTrue();
        soft.assertThat(store2.metadata(v1).await().unwrap().isPresent()).as("T5 metadata present").isTrue();
        var archived = store2.archive(v1).await();
        soft.assertThat(archived.isSuccess()).as("T5 archive: " + archived).isTrue();
        soft.assertAll();
    }

    @Test
    void T6a_deployWritesNoFilesKey() {
        fixture(2000, 1000, 5000);
        deployOk(store1, v1, bytesA);
        assertThat(dht.puts).as("PRECONDITION deploy wrote puts").isNotEmpty();

        assertThat(dht.puts.stream().filter(k -> k.endsWith("/files")).toList()).as("T6a files keys put").isEmpty();
    }

    @Test
    void T6b_archiveDatesWithoutTheFilesKey() {
        fixture(2000, 1000, 5000);
        deployOk(store1, v1, bytesA);
        dropKeys("/files");
        assertThat(dht.union().keySet().stream().anyMatch(k -> k.endsWith("/files"))).as("PRECONDITION files key dropped").isFalse();
        now.addAndGet(8 * DAY);

        var archived = store1.archive(v1).await();

        assertThat(archived.isSuccess()).as("T6b archive after retention with files key absent: " + archived).isTrue();
    }

    @Test
    void T7a_missOnQuorumReplicas_isRecoveredByLocate() {
        fixture(2000, 1000, 5000);
        dht.readOrder = List.of(1, 2, 0);
        deployOk(store1, v1, bytesA);
        dropBlocks(1, 2);
        assertThat(dht.replicas.get(0).keySet().stream().anyMatch(k -> k.startsWith(BLOCKS))).as("PRECONDITION replica 0 still holds block").isTrue();
        assertThat(dht.replicas.get(1).keySet().stream().anyMatch(k -> k.startsWith(BLOCKS))).as("PRECONDITION replica 1 dropped").isFalse();
        assertThat(store2.metadata(v1).await().unwrap().isPresent()).as("PRECONDITION store 2 reads meta through the quorum").isTrue();

        var r = store2.resolve(v1).await();

        assertThat(r.isSuccess()).as("T7a store 2 resolve: " + r).isTrue();
        assertThat(r.unwrap()).isEqualTo(bytesA);
    }

    @Test
    void T7t_slowQuorumReadOfABlock_resolvesInsideTheLeg() {
        fixture(400, 100, 800);
        deployOk(store1, v1, bytesA);
        dht.delayFirstGetOf = name -> name.startsWith(BLOCKS);
        dht.delayMillis = 4000;
        var started = System.nanoTime();

        var r = store2.resolve(v1).await(timeSpan(10).seconds());
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).as("PRECONDITION resolve returned before the held read (" + elapsedMs + " ms)").isLessThan(3500);
        assertThat(r.isSuccess()).as("T7t store 2 resolve inside the leg: " + r).isTrue();
    }
}
