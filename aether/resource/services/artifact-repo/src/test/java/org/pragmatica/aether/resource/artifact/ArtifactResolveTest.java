package org.pragmatica.aether.resource.artifact;

import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.artifact.ArtifactTestFixture.BLOCKS;
import static org.pragmatica.aether.resource.artifact.ArtifactTestFixture.artifactTestFixture;
import static org.pragmatica.aether.resource.artifact.ArtifactTestFixture.failureName;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Design 420, rows T7a (a block miss on the quorum replicas) and T7t (a slow quorum read of a block).
///
/// Each row has an enabled TRIPWIRE that asserts the behaviour at the rc6 pin, and the real assertion beside it,
/// `@Disabled`. PR-4e flips them: delete the tripwire, enable the real test.
class ArtifactResolveTest {
    private void blockMissingOnTheQuorumReplicasOnly(ArtifactTestFixture af) {
        af.dht.readOrder = List.of(1, 2, 0);
        af.deployOk(af.store1, af.v1, af.bytesA);
        af.dropBlocks(1, 2);
        assertThat(af.dht.replicas.get(0).keySet().stream().anyMatch(k -> k.startsWith(BLOCKS)))
            .as("PRECONDITION replica 0 still holds the block")
            .isTrue();
        assertThat(af.dht.replicas.get(1).keySet().stream().anyMatch(k -> k.startsWith(BLOCKS)))
            .as("PRECONDITION replica 1 dropped the block")
            .isFalse();
        assertThat(af.store2.metadata(af.v1).await().unwrap().isPresent())
            .as("PRECONDITION store 2 reads meta through the quorum")
            .isTrue();
    }

    private ArtifactTestFixture deployedWithASlowFirstBlockRead() {
        var af = new ArtifactTestFixture(400, 100, 800);

        af.deployOk(af.store1, af.v1, af.bytesA);
        af.dht.delayFirstGetOf = name -> name.startsWith(BLOCKS);
        af.dht.delayMillis = 4000;
        return af;
    }

    @Test
    void T7a_tripwire_missOnQuorumReplicasIsNotRecovered() {
        var af = artifactTestFixture();

        blockMissingOnTheQuorumReplicasOnly(af);

        var resolved = af.store2.resolve(af.v1).await();

        assertThat(failureName(resolved))
            .as("T7a TRIPWIRE (rc6 behaviour: no recover on a miss). PR-4e flips it: delete this test, enable T7a_real. Got: " + resolved)
            .isEqualTo("CorruptedArtifact");
    }

    @Disabled("PR-4e (recover through StorageTier.locate): enable when a block miss is recovered from another replica")
    @Test
    void T7a_real_missOnQuorumReplicasIsRecoveredByLocate() {
        var af = artifactTestFixture();

        blockMissingOnTheQuorumReplicasOnly(af);

        var resolved = af.store2.resolve(af.v1).await();

        assertThat(resolved.isSuccess()).as("T7a store 2 resolve: " + resolved).isTrue();
        assertThat(resolved.unwrap()).isEqualTo(af.bytesA);
    }

    @Test
    void T7t_tripwire_slowQuorumReadOfABlockTimesOut() {
        var af = deployedWithASlowFirstBlockRead();

        var resolved = af.store2.resolve(af.v1).await(timeSpan(10).seconds());

        assertThat(resolved.isFailure())
            .as("T7t TRIPWIRE (rc6 behaviour: the waterfall read is bounded only by the leg, so a held read times out). PR-4e flips it: delete this test, enable T7t_real. Got: " + resolved)
            .isTrue();
        assertThat(failureName(resolved)).as("T7t TRIPWIRE failure cause must be the Timeout. PR-4e flips it. Got: " + resolved).isEqualTo("Timeout");
    }

    @Disabled("PR-4e (locate bounds the read): enable when resolve returns inside the leg")
    @Test
    void T7t_real_slowQuorumReadOfABlockResolvesInsideTheLeg() {
        var af = deployedWithASlowFirstBlockRead();
        var started = System.nanoTime();

        var resolved = af.store2.resolve(af.v1).await(timeSpan(10).seconds());
        var elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).as("PRECONDITION resolve returned before the held read (" + elapsedMs + " ms)").isLessThan(3500);
        assertThat(resolved.isSuccess()).as("T7t store 2 resolve inside the leg: " + resolved).isTrue();
    }
}
