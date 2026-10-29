package org.pragmatica.config;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.config.DynamicConfigurationProvider.dynamicConfigurationProvider;

/// F6: the overlay is written key by key, so a reader that copies it while a writer is mid-sequence must still
/// get a state the put sequence produced. The writer rewrites KEYS keys in a fixed order to the next round
/// number; a produced state is therefore a non-increasing run of values (earlier keys are already on the new
/// round) whose spread is at most one. A snapshot that reads an early key before the round and a later key after
/// it is torn: its run increases somewhere.
class DynamicConfigurationProviderSnapshotTest {
    private static final int KEYS = 200;
    private static final long DEADLINE_NANOS = 3_000_000_000L;
    private static final long PAUSE_NANOS = 300_000L;

    private final DynamicConfigurationProvider provider = dynamicConfigurationProvider(ConfigurationProvider.builder()
                                                                                                           .build());

    @Test
    void overlayMap_neverTorn_whileTheOverlayIsRewritten() {
        assertThat(countTornSnapshots(provider::overlayMap)).as("torn overlayMap() snapshots").isZero();
    }

    @Test
    void asMap_neverTorn_whileTheOverlayIsRewritten() {
        assertThat(countTornSnapshots(provider::asMap)).as("torn asMap() snapshots").isZero();
    }

    private int countTornSnapshots(Supplier<Map<String, String>> snapshot) {
        var stop = new AtomicBoolean();
        var torn = new AtomicInteger();
        var writer = Thread.ofPlatform().daemon().start(() -> rewrite(stop));
        var deadline = System.nanoTime() + DEADLINE_NANOS;
        var reads = 0;

        while (System.nanoTime() < deadline) {
            reads++;
            if (isTorn(snapshot.get())) {
                torn.incrementAndGet();
            }
        }
        stop.set(true);
        join(writer);

        assertThat(reads).as("the reader made progress").isPositive();
        return torn.get();
    }

    private void rewrite(AtomicBoolean stop) {
        for (int round = 1; !stop.get(); round++) {
            for (int i = 0; i < KEYS; i++) {
                provider.put(key(i), Integer.toString(round));
            }
            LockSupport.parkNanos(PAUSE_NANOS);
        }
    }

    /// Round 1 may be partial: the keys present must then be a prefix of the write order.
    private static boolean isTorn(Map<String, String> snapshot) {
        var values = new ArrayList<Integer>();

        for (int i = 0; i < KEYS; i++) {
            var value = snapshot.get(key(i));

            if (value == null) {
                return values.size() != snapshot.size();
            }
            values.add(Integer.parseInt(value));
        }
        return !producedState(values);
    }

    private static boolean producedState(ArrayList<Integer> values) {
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) > values.get(i - 1)) {
                return false;
            }
        }
        return values.getFirst() - values.getLast() <= 1;
    }

    private static String key(int i) {
        return "k" + String.format("%03d", i);
    }

    private static void join(Thread writer) {
        try {
            writer.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
