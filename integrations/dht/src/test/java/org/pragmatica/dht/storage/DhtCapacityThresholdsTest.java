package org.pragmatica.dht.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTMessage.KeyValue;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #2258 spec 3.2 item 8, the engine's thresholds to the byte and the paths that move them: `NEAR_FULL` at 85% of live plus
/// tombstone bytes (rounded up), cleared below 80%; `FULL` cleared when live bytes fall below 85%; every path that frees
/// bytes ends an episode.
class DhtCapacityThresholdsTest {
    private static final long BASE = HlcTimestamp.pack(System.currentTimeMillis(), 0);

    private final List<String> events = new CopyOnWriteArrayList<>();
    private long version = BASE;

    private MemoryStorageEngine engine(long cap) {
        var accounting = DhtPrefixAccounting.dhtPrefixAccounting(cap);

        accounting.listener(new DhtCapacityListener() {
            @Override
            public void nearFull(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {
                events.add("nearFull");
            }

            @Override
            public void nearFullCleared(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {
                events.add("nearFullCleared");
            }

            @Override
            public void full(String prefix, long usedBytes, long maxBytes, long refusedBytes) {
                events.add("full");
            }

            @Override
            public void fullCleared(String prefix, long usedBytes, long maxBytes) {
                events.add("fullCleared");
            }
        });

        return memoryStorageEngine(OwnerEpochGate.noOp(), accounting);
    }

    private static byte[] key(String name) {
        return ("stream-segments/" + name).getBytes(StandardCharsets.UTF_8);
    }

    private boolean put(MemoryStorageEngine engine, String name, int size) {
        return engine.putVersioned(key(name), new byte[size], ++version, 0L, 0L, 0L, false).await().isSuccess();
    }

    @Test
    void nearFull_isRaisedAtExactly85Percent() {
        var engine = engine(1_000L);

        put(engine, "a", 849);
        assertThat(events).as("849 of 1000").isEmpty();

        put(engine, "a", 850);
        assertThat(events).as("850 of 1000").containsExactly("nearFull");
    }

    @Test
    void theThresholds_roundUp_whenTheCapIsNotAMultipleOfOneHundred() {
        var engine = engine(1_050L);

        put(engine, "a", 892);
        assertThat(events).as("85% of 1050 is 892.5: 892 is below it").isEmpty();

        put(engine, "a", 893);
        assertThat(events).containsExactly("nearFull");
    }

    @Test
    void theRefusalEpisode_endsBelow85Percent_notAtIt() {
        var engine = engine(1_000L);

        for (var i = 1; i <= 8; i++) {
            put(engine, "k" + i, 100);
        }
        put(engine, "half", 50);
        put(engine, "big", 150);
        assertThat(put(engine, "over", 1)).isFalse();
        assertThat(events).containsExactly("nearFull", "full");

        engine.removeVersioned(key("big"), ++version, 0L, 0L, 0L).await();
        assertThat(events).as("850 live is not below 85%").containsExactly("nearFull", "full");

        engine.removeVersioned(key("half"), ++version, 0L, 0L, 0L).await();
        assertThat(events).as("800 live is").containsExactly("nearFull", "full", "fullCleared");
    }

    private void everyPathThatFreesBytes_closesTheNearFullEpisode(Consumer<MemoryStorageEngine> path) {
        events.clear();
        var engine = engine(1_000L);

        put(engine, "a", 900);
        assertThat(events).containsExactly("nearFull");

        path.accept(engine);

        assertThat(events).containsExactly("nearFull", "nearFullCleared");
    }

    @Test
    void everyPathThatFreesBytes_closesTheNearFullEpisode() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(new NodeId("n1"));
        var tombstone = new KeyValue(key("a"), new byte[0], BASE + 1_000_000, 0L, 0L, 0L, true);
        var paths = List.<Consumer<MemoryStorageEngine>>of(engine -> engine.removeVersioned(key("a"), BASE + 1_000_000, 0L, 0L, 0L).await(),
                                                           engine -> engine.putReplica(key("a"), new byte[10], BASE + 1_000_000, 0L, 0L, 0L).await(),
                                                           engine -> engine.putReplica(tombstone, true).await(),
                                                           engine -> engine.dropPartition(ring, ring.partitionFor(key("a"))).await(),
                                                           engine -> engine.removeIfExactly(key("a"), version, 0L, 0L, 0L).await(),
                                                           engine -> engine.restoreIfExactly(key("a"), version, 0L, 0L, 0L, Option.none()).await(),
                                                           engine -> engine.remove(key("a")).await(),
                                                           engine -> engine.clear().await(),
                                                           engine -> engine.shutdown().await());

        paths.forEach(this::everyPathThatFreesBytes_closesTheNearFullEpisode);
    }

    @Test
    void collectingTombstones_closesTheNearFullEpisodeTheyOpened() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(new NodeId("n1"));
        var engine = engine(1_000L);

        for (var i = 0; i < 11; i++) {
            engine.removeVersioned(key("t" + (10 + i)), ++version, 0L, 0L, 0L).await();
        }
        assertThat(events).containsExactly("nearFull");

        engine.entries().await().or(List.of()).forEach(entry -> engine.collectTombstones(ring, ring.partitionFor(entry.key()), Long.MAX_VALUE).await());

        assertThat(events).containsExactly("nearFull", "nearFullCleared");
    }
}
