package org.pragmatica.aether.resource.artifact;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.artifact.ArtifactTestFixture.BLOCKS;
import static org.pragmatica.aether.resource.artifact.ArtifactTestFixture.artifactTestFixture;
import static org.pragmatica.aether.resource.artifact.ArtifactTestFixture.failureName;

/// Design 420, rows T3 (deploy order) and T6a (no `files` key written).
///
/// Each row has an enabled TRIPWIRE that asserts the behaviour at the rc6 pin, and the real assertion beside it,
/// `@Disabled`. The PR named in the tripwire's message flips them: delete the tripwire, enable the real test.
class ArtifactStoreLifecycleTest {
    private final ArtifactTestFixture af = artifactTestFixture();

    private void failedFirstDeployOfX() {
        af.dht.putFailure = name -> name.startsWith(BLOCKS)
                                    ? Option.some(Causes.cause("chunk refused"))
                                    : Option.none();

        var x = af.store1.deploy(af.v1, af.bytesA).await();

        assertThat(x.isFailure()).as("PRECONDITION deploy X fails on its chunks: " + x).isTrue();
        x.onFailure(cause -> assertThat(cause.message()).as("PRECONDITION X failed for the chunk refusal")
                                                        .contains("chunk refused"));
        af.dht.putFailure = _ -> Option.none();
    }

    @Test
    void T3_tripwire_failedFirstDeployStillBindsItsDigest() {
        failedFirstDeployOfX();

        var y = af.store1.deploy(af.v1, af.bytesB).await();

        assertThat(failureName(y))
            .as("T3 TRIPWIRE (rc6 behaviour: X is bound before its chunks are written, so Y is a 409). PR-1b flips it: delete this test, enable T3_real. Got: " + y)
            .isEqualTo("ContentConflict");
    }

    @Disabled("PR-1b (write before bind): enable when a failed deploy leaves no binding")
    @Test
    void T3_real_failedFirstDeployDoesNotBlockADifferentLaterDeploy() {
        failedFirstDeployOfX();

        var y = af.store1.deploy(af.v1, af.bytesB).await();

        assertThat(y.isSuccess()).as("T3 deploy Y of other bytes: " + y).isTrue();
    }

    @Test
    void T6a_tripwire_deployWritesAFilesKey() {
        af.deployOk(af.store1, af.v1, af.bytesA);
        assertThat(af.dht.puts).as("PRECONDITION deploy wrote puts").isNotEmpty();

        assertThat(af.dht.puts.stream().filter(key -> key.endsWith("/files")).toList())
            .as("T6a TRIPWIRE (rc6 behaviour: registerFile writes the files key). PR-3c flips it: delete this test, enable T6a_real")
            .isNotEmpty();
    }

    @Disabled("PR-3c (registerFile removed): enable when a deploy writes no files key")
    @Test
    void T6a_real_deployWritesNoFilesKey() {
        af.deployOk(af.store1, af.v1, af.bytesA);
        assertThat(af.dht.puts).as("PRECONDITION deploy wrote puts").isNotEmpty();

        assertThat(af.dht.puts.stream().filter(key -> key.endsWith("/files")).toList())
            .as("T6a files keys put")
            .isEmpty();
    }
}
