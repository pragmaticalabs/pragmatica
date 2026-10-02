package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactVersionsValue;

import static org.assertj.core.api.Assertions.assertThat;

/// #1778: the versions set is bounded on its PRESENT versions, and the bound is monotone: it only ever refuses
/// growth. A present version is never dropped, an archived one is never un-archived, an archive is always accepted.
class ArtifactVersionsValueBoundTest {
    private static ArtifactVersionsValue set(int maxLive, String... live) {
        var value = ArtifactVersionsValue.empty();

        for (var version : live) {
            value = ArtifactVersionsValue.added(version, maxLive).mergeInto(value);
        }

        return value;
    }

    @Test
    void mergeInto_refusesANewPresentVersion_pastTheBound_andKeepsEveryExistingOne() {
        var full = set(2, "1.0.0", "2.0.0");

        var merged = ArtifactVersionsValue.added("3.0.0", 2).mergeInto(full);

        assertThat(merged.live()).containsExactly("1.0.0", "2.0.0");
        assertThat(merged.contains("3.0.0")).as("refused, so the writer's re-read can report it").isFalse();
    }

    @Test
    void mergeInto_acceptsAnExistingVersionAgain_atTheBound_asANoOp() {
        var full = set(2, "1.0.0", "2.0.0");

        assertThat(ArtifactVersionsValue.added("2.0.0", 2).mergeInto(full)).isEqualTo(full);
    }

    @Test
    void mergeInto_alwaysAcceptsAnArchive_andArchivingFreesRoom_butNeverResurrects() {
        var full = set(2, "1.0.0", "2.0.0");

        var archived = ArtifactVersionsValue.archived("1.0.0").mergeInto(full);
        var later = ArtifactVersionsValue.added("3.0.0", 2).mergeInto(archived);
        var staleAdd = ArtifactVersionsValue.added("1.0.0", 2).mergeInto(later);

        assertThat(archived.live()).containsExactly("2.0.0");
        assertThat(later.live()).containsExactly("2.0.0", "3.0.0");
        assertThat(staleAdd.isArchived("1.0.0")).as("a stale add cannot un-archive").isTrue();
        assertThat(staleAdd.live()).containsExactly("2.0.0", "3.0.0");
    }

    @Test
    void mergeInto_archivingAnUnknownVersion_isRecordedWithoutUsingRoom() {
        var full = set(1, "1.0.0");

        var merged = ArtifactVersionsValue.archived("0.9.0").mergeInto(full);

        assertThat(merged.isArchived("0.9.0")).isTrue();
        assertThat(merged.live()).containsExactly("1.0.0");
    }
}
