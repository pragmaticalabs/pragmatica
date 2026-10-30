package org.pragmatica.dht.storage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.hlc.HlcTimestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #2258 spec 3.2 item 8 at the engine (NT-126, DHT half): the engine reports the transitions of the bounded prefix to its
/// listener once per episode, never per put. `NEAR_FULL` at 85% of live plus tombstone bytes and its partner below 80%;
/// `FULL` at the first refusal of an episode and its partner when live bytes fall below 85%.
class DhtCapacityEventsTest {
    private static final long CAP = 1_000L;
    private static final long BASE = HlcTimestamp.pack(System.currentTimeMillis(), 0);

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final MemoryStorageEngine engine = engine();
    private long version = BASE;

    private MemoryStorageEngine engine() {
        var accounting = DhtPrefixAccounting.dhtPrefixAccounting(CAP);

        accounting.listener(new DhtCapacityListener() {
            @Override
            public void nearFull(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {
                events.add("nearFull " + prefix + " " + liveBytes + "+" + tombstoneBytes + "/" + maxBytes);
            }

            @Override
            public void nearFullCleared(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {
                events.add("nearFullCleared " + prefix + " " + liveBytes + "+" + tombstoneBytes + "/" + maxBytes);
            }

            @Override
            public void full(String prefix, long usedBytes, long maxBytes, long refusedBytes) {
                events.add("full " + prefix + " " + usedBytes + "/" + maxBytes + " refused " + refusedBytes);
            }

            @Override
            public void fullCleared(String prefix, long usedBytes, long maxBytes) {
                events.add("fullCleared " + prefix + " " + usedBytes + "/" + maxBytes);
            }
        });

        return memoryStorageEngine(OwnerEpochGate.noOp(), accounting);
    }

    private static byte[] key(String name) {
        return ("stream-segments/" + name).getBytes(StandardCharsets.UTF_8);
    }

    private boolean put(String name, int size) {
        return engine.putVersioned(key(name), new byte[size], ++version, 0L, 0L, 0L, false).await().isSuccess();
    }

    private void remove(String name) {
        engine.removeVersioned(key(name), ++version, 0L, 0L, 0L).await();
    }

    private void collectTombstones() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(new NodeId("n1"));
        engine.entries().await().or(List.of()).forEach(entry -> engine.collectTombstones(ring, ring.partitionFor(entry.key()), Long.MAX_VALUE).await());
    }

    @Test
    void theTransitions_areReportedOncePerEpisode_andClosedByTheirPartners() {
        for (var i = 1; i <= 8; i++) {
            put("k" + i, 100);
        }
        assertThat(events).as("800 bytes is 80%: below the 85% warning").isEmpty();

        put("k9", 100);
        assertThat(events).as("900 bytes crossed 85%").containsExactly("nearFull stream-segments 900+0/1000");

        assertThat(put("k10", 100)).as("exactly the cap is accepted").isTrue();
        assertThat(events).as("no second near-full in the same episode").hasSize(1);

        assertThat(put("k11", 100)).as("past the cap").isFalse();
        assertThat(events).containsExactly("nearFull stream-segments 900+0/1000", "full stream-segments 1000/1000 refused 100");

        assertThat(put("k12", 250)).isFalse();
        assertThat(put("k13", 100)).isFalse();
        assertThat(events).as("MT-54: later refusals of the same episode raise nothing").hasSize(2);

        remove("k10");
        assertThat(events).as("900 live is still above 85%: the refusal episode goes on").hasSize(2);

        remove("k9");
        assertThat(events).as("800 live is below 85%: the refusal episode ends")
                          .containsExactly("nearFull stream-segments 900+0/1000",
                                           "full stream-segments 1000/1000 refused 100",
                                           "fullCleared stream-segments 800/1000");

        collectTombstones();
        assertThat(events).as("800 is not below 80%").hasSize(3);

        remove("k8");
        assertThat(events.getLast()).as("700 live plus the 82-byte tombstone is below 80%")
                                    .isEqualTo("nearFullCleared stream-segments 700+82/1000");
        assertThat(events).hasSize(4);
    }

    @Test
    void aNewEpisode_raisesAgain() {
        for (var i = 1; i <= 10; i++) {
            put("k" + i, 100);
        }
        put("k11", 100);
        for (var i = 1; i <= 10; i++) {
            remove("k" + i);
        }
        collectTombstones();
        assertThat(events).extracting(event -> event.split(" ")[0])
                          .containsExactly("nearFull", "full", "fullCleared", "nearFullCleared");

        for (var i = 1; i <= 10; i++) {
            put("n" + i, 100);
        }
        put("n11", 100);

        assertThat(events).extracting(event -> event.split(" ")[0])
                          .containsExactly("nearFull", "full", "fullCleared", "nearFullCleared", "nearFull", "full");
    }

    /// Tombstones are never capped, but they are heap: they count toward `NEAR_FULL`, which warns and refuses nothing.
    @Test
    void tombstones_countTowardNearFull_butNeverRefuse() {
        for (var i = 0; i < 10; i++) {
            remove("t" + (10 + i));
        }
        assertThat(events).as("10 tombstones of 83 bytes are 83%").isEmpty();

        remove("t30");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).startsWith("nearFull stream-segments 0+913/1000");
        assertThat(put("k1", 1_000)).as("the cap is on live bytes only").isTrue();
        assertThat(events).as("no refusal, no FULL").hasSize(1);
    }

    @Test
    void anUnboundedPrefix_reportsNothing() {
        var other = new CopyOnWriteArrayList<String>();
        var accounting = DhtPrefixAccounting.dhtPrefixAccounting(CAP);

        accounting.listener(new DhtCapacityListener() {
            @Override
            public void nearFull(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {
                other.add(prefix);
            }

            @Override
            public void nearFullCleared(String prefix, long liveBytes, long tombstoneBytes, long maxBytes) {
                other.add(prefix);
            }

            @Override
            public void full(String prefix, long usedBytes, long maxBytes, long refusedBytes) {
                other.add(prefix);
            }

            @Override
            public void fullCleared(String prefix, long usedBytes, long maxBytes) {
                other.add(prefix);
            }
        });
        var engine = memoryStorageEngine(OwnerEpochGate.noOp(), accounting);

        engine.putVersioned("artifacts/a".getBytes(StandardCharsets.UTF_8), new byte[5_000], BASE, 0L, 0L, 0L, false).await();

        assertThat(other).isEmpty();
    }
}
