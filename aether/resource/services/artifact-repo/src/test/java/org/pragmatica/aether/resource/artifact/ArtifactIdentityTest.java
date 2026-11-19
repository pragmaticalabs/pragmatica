package org.pragmatica.aether.resource.artifact;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.SoftAssertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.artifact.ArtifactTestFixture.artifactTestFixture;
import static org.pragmatica.aether.resource.artifact.ArtifactTestFixture.failureName;

/// Design 420, rows T5 (identity readers) and T6b (archive dating without the `files` key).
///
/// Each row has an enabled TRIPWIRE that asserts the behaviour at the rc6 pin, and the real assertion beside it,
/// `@Disabled`. The PR named in the tripwire's message flips them: delete the tripwire, enable the real test.
class ArtifactIdentityTest {
    private final ArtifactTestFixture af = artifactTestFixture();

    private void deployedThenMetaKeyDroppedThenRetentionElapsed() {
        af.deployOk(af.store1, af.v1, af.bytesA);
        af.dropKeys("/meta");
        assertThat(af.keyHeld("/meta")).as("PRECONDITION meta key dropped").isFalse();
        af.advanceDays(8);
    }

    private void deployedThenFilesKeyDroppedThenRetentionElapsed() {
        af.deployOk(af.store1, af.v1, af.bytesA);
        af.dropKeys("/files");
        assertThat(af.keyHeld("/files")).as("PRECONDITION files key dropped").isFalse();
        af.advanceDays(8);
    }

    @Test
    void T5_tripwire_identityReadersStillReadTheMetaKey() {
        deployedThenMetaKeyDroppedThenRetentionElapsed();
        var soft = new SoftAssertions();

        soft.assertThat(failureName(af.store2.resolve(af.v1).await()))
            .as("T5 TRIPWIRE resolve (rc6 behaviour: reads the meta key). PR-3b flips it: delete this test, enable T5_real")
            .isEqualTo("NotFound");
        soft.assertThat(af.store2.exists(af.v1).await().unwrap()).as("T5 TRIPWIRE exists (PR-3b flips it)").isFalse();
        soft.assertThat(af.store2.metadata(af.v1).await().unwrap().isPresent()).as("T5 TRIPWIRE metadata present (PR-3b flips it)").isFalse();
        soft.assertThat(failureName(af.store2.archive(af.v1).await())).as("T5 TRIPWIRE archive (PR-3b flips it)").isEqualTo("VersionNotFound");
        soft.assertAll();
    }

    @Disabled("PR-3b (identity from the shared index): enable when the readers do not read the meta key")
    @Test
    void T5_real_identityReadersReadTheIndexNotTheMetaKey() {
        deployedThenMetaKeyDroppedThenRetentionElapsed();
        var soft = new SoftAssertions();
        var resolved = af.store2.resolve(af.v1).await();

        soft.assertThat(resolved.isSuccess()).as("T5 resolve: " + resolved).isTrue();
        soft.assertThat(af.store2.exists(af.v1).await().unwrap()).as("T5 exists").isTrue();
        soft.assertThat(af.store2.metadata(af.v1).await().unwrap().isPresent()).as("T5 metadata present").isTrue();

        var archived = af.store2.archive(af.v1).await();

        soft.assertThat(archived.isSuccess()).as("T5 archive: " + archived).isTrue();
        soft.assertAll();
    }

    @Test
    void T6b_tripwire_archiveStillDatesFromTheFilesKey() {
        deployedThenFilesKeyDroppedThenRetentionElapsed();

        var archived = af.store1.archive(af.v1).await();

        assertThat(failureName(archived))
            .as("T6b TRIPWIRE (rc6 behaviour: archive dating reads the files key). PR-3c flips it: delete this test, enable T6b_real. Got: " + archived)
            .isEqualTo("VersionNotFound");
    }

    @Disabled("PR-3c (archive dating from the index): enable when archive does not read the files key")
    @Test
    void T6b_real_archiveDatesWithoutTheFilesKey() {
        deployedThenFilesKeyDroppedThenRetentionElapsed();

        var archived = af.store1.archive(af.v1).await();

        assertThat(archived.isSuccess()).as("T6b archive after retention with files key absent: " + archived).isTrue();
    }
}
