// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVStore.RevisionedSnapshot;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// #3274: the store's `exact` flag says whether the content is the state at `committedRevision`. Only a snapshot install sets it (content
/// and label in one mutation); an adoption that moves the label past the content clears it; reset and the apply path leave it.
class KVStoreExactLabelTest {
    private record Key(String id) implements StructuredKey {}

    private static final Key WARNING = new Key("warning");
    private static final Key OTHER = new Key("other");

    /// Decodes every snapshot as the map the test put in `next`.
    private static final class InstallingDeserializer implements Deserializer {
        volatile Map<StructuredKey, Object> next = Map.of();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T read(ByteBuf buffer) {
            return (T) next;
        }
    }

    private final InstallingDeserializer deserializer = new InstallingDeserializer();
    private final KVStore<Key, String> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, deserializer);

    private RevisionedSnapshot<Key, String> read() {
        return store.snapshotWithRevision(key -> key.equals(WARNING));
    }

    private void install(Map<StructuredKey, Object> content, long nextSlot) {
        deserializer.next = content;
        assertThat(store.restoreCommittedSnapshot(new byte[]{1}, nextSlot).isSuccess()).isTrue();
    }

    private void apply(Key key, String value, long nextSlot) {
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(key, value))), nextSlot);
    }

    @Test
    void snapshotWithRevision_genesisStore_isExactAtRevisionZero() {
        var snapshot = read();

        assertThat(snapshot.revision()).isZero();
        assertThat(snapshot.exact()).isTrue();
        assertThat(snapshot.entries()).isEmpty();
    }

    @Test
    void snapshotWithRevision_appliedSlots_stayExact_andCopyOnlyMatchingKeys() {
        apply(WARNING, "w", 3);
        apply(OTHER, "o", 4);

        var snapshot = read();

        assertThat(snapshot.revision()).isEqualTo(4);
        assertThat(snapshot.exact()).as("the apply path moves content and label together").isTrue();
        assertThat(snapshot.entries()).containsExactly(Map.entry(WARNING, "w"));
    }

    @Test
    void restoreCommittedSnapshot_setsExact_andLabelsTheInstalledContent() {
        store.onPhaseAdopted(9);
        assertThat(read().exact()).isFalse();

        install(Map.of(WARNING, "installed"), 12);

        var snapshot = read();

        assertThat(snapshot.revision()).isEqualTo(12);
        assertThat(snapshot.exact()).as("mutation: drop `exact = true` from the install").isTrue();
        assertThat(snapshot.entries()).containsExactly(Map.entry(WARNING, "installed"));
    }

    @Test
    void onPhaseAdopted_atOrBelowTheLabel_leavesExact() {
        install(Map.of(WARNING, "installed"), 12);

        store.onPhaseAdopted(12);
        store.onPhaseAdopted(7);

        assertThat(read().revision()).isEqualTo(12);
        assertThat(read().exact()).as("an adoption that does not move the label passes no slot").isTrue();
    }

    @Test
    void onPhaseAdopted_pastTheInstalledContent_raisesTheLabel_andClearsExact() {
        install(Map.of(WARNING, "installed"), 12);

        store.onPhaseAdopted(15);

        assertThat(read().revision()).isEqualTo(15);
        assertThat(read().exact()).as("mutation: keep `exact` when the adoption moves the label").isFalse();
    }

    @Test
    void onPhaseAdopted_emptySnapshotBranch_overAppliedContent_clearsExact() {
        apply(WARNING, "w", 5);

        // RE's empty-snapshot branch: no install, the source's phase adopted over content the restore did not touch.
        store.onPhaseAdopted(9);

        assertThat(read().revision()).isEqualTo(9);
        assertThat(read().exact()).isFalse();
    }

    @Test
    void exactCleared_staysCleared_acrossApplyAndCompletedPhases_untilTheNextInstall() {
        store.onPhaseAdopted(9);

        apply(WARNING, "w", 10);
        store.onPhaseCompleted(11);

        assertThat(read().revision()).isEqualTo(11);
        assertThat(read().exact()).as("the apply path leaves `exact`: content past an unapplied gap is still not the state at the label").isFalse();

        install(Map.of(WARNING, "installed"), 20);

        assertThat(read().exact()).isTrue();
    }

    @Test
    void reset_returnsToRevisionZero_andLeavesExact() {
        install(Map.of(WARNING, "installed"), 12);
        store.reset();

        assertThat(read().revision()).isZero();
        assertThat(read().exact()).isTrue();
        assertThat(read().entries()).isEmpty();

        store.onPhaseAdopted(4);
        store.reset();

        assertThat(read().revision()).isZero();
        assertThat(read().exact()).as("reset leaves `exact`; revision 0 is what a reader refuses").isFalse();
    }
}
