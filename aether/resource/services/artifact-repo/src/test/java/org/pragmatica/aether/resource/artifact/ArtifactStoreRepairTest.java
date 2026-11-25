package org.pragmatica.aether.resource.artifact;

import java.util.Arrays;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.artifact.ArtifactTestFixture.CHUNK;
import static org.pragmatica.aether.resource.artifact.ArtifactTestFixture.artifactTestFixture;
import static org.pragmatica.aether.resource.artifact.ArtifactTestFixture.blockKey;
import static org.pragmatica.aether.resource.artifact.ArtifactTestFixture.failureName;
import static org.pragmatica.aether.resource.artifact.ArtifactTestFixture.pattern;

/// Design 420, rows T1 and T2: a dropped block must be written back by a deploy of the same bytes.
///
/// Each row has an enabled TRIPWIRE that asserts the behaviour at the rc6 pin (the defect), and the real assertion
/// beside it, `@Disabled`. The PR named in the tripwire's message flips them: delete the tripwire, enable the real test.
class ArtifactStoreRepairTest {
    private final ArtifactTestFixture af = artifactTestFixture();

    private void droppedBlockThenIdenticalRePut() {
        af.deployOk(af.store1, af.v1, af.bytesA).unwrap();
        assertThat(af.blocksHeld()).as("PRECONDITION block written by first deploy").isTrue();
        assertThat(af.store2.metadata(af.v1).await().unwrap().isPresent()).as("PRECONDITION store 2 sees meta").isTrue();
        af.dropBlocks(0, 1, 2);
        assertThat(af.blocksHeld()).as("PRECONDITION block dropped from every replica").isFalse();

        var again = af.store1.deploy(af.v1, af.bytesA).await();

        assertThat(again.isSuccess()).as("PRECONDITION identical re-PUT accepted: " + again).isTrue();
    }

    private void droppedChunkThenDeployOfAnotherCoordinateWithTheSameFirstChunk() {
        af.deployOk(af.store1, af.v1, af.bytesA).unwrap();
        assertThat(af.dht.union()).as("PRECONDITION chunk c written by deploy A").containsKey(blockKey(af.bytesA));
        af.dropBlocks(0, 1, 2);
        assertThat(af.dht.union()).as("PRECONDITION chunk c dropped").doesNotContainKey(blockKey(af.bytesA));

        var bFirstEqualsC = Arrays.copyOf(af.bytesA, CHUNK + 1000);

        System.arraycopy(pattern(1000, 5), 0, bFirstEqualsC, CHUNK, 1000);
        af.deployOk(af.store1, af.v2, bFirstEqualsC).unwrap();
    }

    @Test
    void T1_tripwire_identicalRePutDoesNotReassertTheBlock() {
        droppedBlockThenIdenticalRePut();

        var resolved = af.store2.resolve(af.v1).await();

        assertThat(failureName(resolved))
            .as("T1 TRIPWIRE (rc6 behaviour: the re-PUT skips the block write). PR-2 flips it: delete this test, enable T1_real. Got: " + resolved)
            .isEqualTo("CorruptedArtifact");
    }

    @Disabled("PR-2 (writeShared): enable when the identical re-PUT writes the block back")
    @Test
    void T1_real_identicalRePutReassertsTheBlock() {
        droppedBlockThenIdenticalRePut();

        var resolved = af.store2.resolve(af.v1).await();

        assertThat(resolved.isSuccess()).as("T1 store 2 resolve after identical re-PUT: " + resolved).isTrue();
        assertThat(resolved.unwrap()).isEqualTo(af.bytesA);
    }

    @Test
    void T2_tripwire_newDeployDoesNotRewriteADroppedChunk() {
        droppedChunkThenDeployOfAnotherCoordinateWithTheSameFirstChunk();

        assertThat(af.dht.union())
            .as("T2 TRIPWIRE (rc6 behaviour: deduplicateBlock credits the record and writes nothing). PR-2 flips it: delete this test, enable T2_real")
            .doesNotContainKey(blockKey(af.bytesA));
    }

    @Disabled("PR-2 (writeShared): enable when a new deploy writes a chunk whose record exists but whose block is gone")
    @Test
    void T2_real_newDeployChunkEqualToADroppedOneIsWrittenToTheSharedTier() {
        droppedChunkThenDeployOfAnotherCoordinateWithTheSameFirstChunk();

        assertThat(af.dht.union()).as("T2 dht.union() holds c after deploy B").containsKey(blockKey(af.bytesA));
    }
}
