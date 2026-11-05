// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the stamp a replica-applied ring entry puts on its notifications: the entry's `slot + 1`, the same revision the entry leaves in
/// the committed view (F1 mutation survivors akv-KVStore-01 `slot - 1` and akv-KVStore-02 stamp assignment deleted).
class KVStoreReplicaStampPinTest {
    private record Key(String id) implements StructuredKey {}

    private static final Key FIRST = new Key("first");
    private static final IncarnationTag TAG = IncarnationTag.incarnationTag(1, "A");

    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final KVStore<Key, String> store = new KVStore<>(router, new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final List<Long> stamps = new ArrayList<>();

    @Test
    void applyEffects_stampsNotificationsWithSlotPlusOne_forAnEntryAboveTheCommittedRevision() {
        router.addRoute(KVStoreNotification.ValuePut.class,
                        (KVStoreNotification.ValuePut<Key, String> _) -> stamps.add(store.notificationRevision().or(0L)));

        var applied = store.applyEffects(new ReplicaEntry(TAG, 4, 4, "id-4", List.of(new KVCommand.Put<>(FIRST, "one"))));

        assertThat(applied.isSuccess()).isTrue();
        assertThat(stamps).containsExactly(5L);
    }
}
