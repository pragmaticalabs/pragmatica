// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVCommand.Remove;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// T17 (drain D1 §3.1): the per-key-class index and `typedEntriesWhen`. After every kind of storage write the read returns exactly the tracked
/// entries of `storage`; an untracked class and a call from the delivering thread are fatal; the guard decides none versus entries.
class KVStoreKeyClassIndexTest {
    private record Tracked(String id) implements StructuredKey {}

    private record Other(String id) implements StructuredKey {}

    private static final StructuredKey GUARD_KEY = new Other("guard");
    private static final Predicate<Object> OPEN = "open"::equals;

    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private Map<StructuredKey, Object> restored = new HashMap<>();

    private KVStore<StructuredKey, Object> store() {
        return new KVStore<>(router, stubSerializer(), stubDeserializer());
    }

    private KVStore<StructuredKey, Object> trackedStore() {
        var store = store();

        store.trackKeyClass(Tracked.class);
        put(store, GUARD_KEY, "open");

        return store;
    }

    @Test
    void puts_andRemoves_throughTheProcessPath_keepTheIndexEqualToStorage() {
        var store = trackedStore();

        put(store, new Tracked("a"), "1");
        put(store, new Tracked("b"), "2");
        put(store, new Other("x"), "3");
        assertMatchesStorage(store);
        assertThat(entries(store)).containsOnlyKeys(new Tracked("a"), new Tracked("b"));

        store.process(store.createBatch(List.of(new Remove<>(new Tracked("a"), Option.none()))));
        assertMatchesStorage(store);
        assertThat(entries(store)).containsOnlyKeys(new Tracked("b"));
    }

    @Test
    void applyEffects_putAndRemove_keepTheIndexEqualToStorage() {
        var replica = trackedStore();

        applyEffects(replica, 0, List.of(new Put<>(new Tracked("a"), "1"), new Put<>(new Tracked("b"), "2")));
        assertMatchesStorage(replica);
        assertThat(entries(replica)).containsOnlyKeys(new Tracked("a"), new Tracked("b"));

        applyEffects(replica, 1, List.of(new Remove<>(new Tracked("a"), Option.none())));
        assertMatchesStorage(replica);
        assertThat(entries(replica)).containsOnlyKeys(new Tracked("b"));
    }

    @Test
    void restoreCommittedSnapshot_thatDropsAndAddsTrackedKeys_keepsTheIndexEqualToStorage() {
        var store = trackedStore();

        put(store, new Tracked("old"), "1");
        restored = image(Map.of(new Tracked("new"), "2"));

        assertThat(store.restoreCommittedSnapshot(new byte[0], 5).isSuccess()).isTrue();
        assertMatchesStorage(store);
        assertThat(entries(store)).containsOnlyKeys(new Tracked("new"));
    }

    @Test
    void restoreSnapshot_thatAddsTrackedKeys_seesThem() {
        var store = trackedStore();

        restored = image(Map.of(new Tracked("n1"), "1", new Tracked("n2"), "2"));

        assertThat(store.restoreSnapshot(new byte[0]).isSuccess()).isTrue();
        assertMatchesStorage(store);
        assertThat(entries(store)).containsOnlyKeys(new Tracked("n1"), new Tracked("n2"));
    }

    @Test
    void restoreSnapshot_thatDropsTrackedKeys_forgetsThem() {
        var store = trackedStore();

        put(store, new Tracked("gone"), "1");
        put(store, new Tracked("kept"), "2");
        restored = image(Map.of(new Tracked("kept"), "2"));

        assertThat(store.restoreSnapshot(new byte[0]).isSuccess()).isTrue();
        assertMatchesStorage(store);
        assertThat(entries(store)).containsOnlyKeys(new Tracked("kept"));
    }

    @Test
    void reset_emptiesTheIndex_andTheAbsentGuardKeyReadsNone() {
        var store = trackedStore();

        put(store, new Tracked("a"), "1");
        store.reset();

        assertThat(store.snapshot()).isEmpty();
        assertThat(typed(store, OPEN).isPresent()).as("the guard key is gone after the reset").isFalse();

        put(store, GUARD_KEY, "open");

        assertThat(entries(store)).as("the reset emptied the index, not only storage").isEmpty();
    }

    @Test
    void trackKeyClass_afterPuts_seesTheKeysAlreadyStored() {
        var store = store();

        put(store, GUARD_KEY, "open");
        put(store, new Tracked("a"), "1");
        put(store, new Tracked("b"), "2");
        store.trackKeyClass(Tracked.class);

        assertThat(entries(store)).containsOnlyKeys(new Tracked("a"), new Tracked("b"));
    }

    @Test
    void untrackedClass_isFatal_neverAnEmptyMap() {
        var store = store();

        put(store, GUARD_KEY, "open");
        put(store, new Tracked("a"), "1");

        assertThatThrownBy(() -> typed(store, OPEN)).isInstanceOf(IllegalStateException.class)
                                                    .hasMessageContaining(Tracked.class.getName());
    }

    @Test
    void absentGuardKey_returnsNone_withoutCallingTheGuard() {
        var store = store();
        var called = new AtomicBoolean();

        store.trackKeyClass(Tracked.class);
        put(store, new Tracked("a"), "1");

        assertThat(typed(store, value -> markCalled(called)).isPresent()).isFalse();
        assertThat(called).as("the guard is not called for an absent guard key").isFalse();
    }

    @Test
    void failingGuard_returnsNone_andPassingGuardReturnsEntries() {
        var store = trackedStore();

        put(store, new Tracked("a"), "1");

        assertThat(typed(store, _ -> false).isPresent()).isFalse();
        assertThat(typed(store, OPEN).isPresent()).isTrue();
    }

    @Test
    void readerRacingAnInstall_seesThePreOrPostInstallSet_neverAMix() throws Exception {
        var store = trackedStore();
        var setA = trackedImage("a", 40);
        var setB = trackedImage("b", 40);
        var mixed = new AtomicReference<Map<Tracked, Object>>();
        var done = new AtomicBoolean();

        install(store, setA);

        var reader = Thread.ofPlatform().daemon().start(() -> readUntilDone(store, setA, setB, mixed, done));

        for (var i = 0; i < 400; i++) {
            install(store, i % 2 == 0 ? setB : setA);
        }

        done.set(true);
        reader.join(TimeUnit.SECONDS.toMillis(30));

        assertThat(reader.isAlive()).isFalse();
        assertThat(mixed.get()).as("a reader saw a set that is neither the pre- nor the post-install one").isNull();
    }

    @Test
    void callFromTheInlineDeliveringThread_fails() {
        var store = trackedStore();
        var caught = new AtomicReference<Throwable>();
        var calledOn = new AtomicReference<Thread>();

        router.addRoute(ValuePut.class, _ -> callOnDeliveryThread(store, caught, calledOn));
        put(store, new Tracked("a"), "1");

        assertThat(caught.get()).isInstanceOf(IllegalStateException.class);
        assertThat(calledOn.get()).as("inline delivery runs on the thread that applied the write").isSameAs(Thread.currentThread());
    }

    @Test
    void callFromTheDispatcherThread_fails() throws Exception {
        var delivered = new CountDownLatch(1);
        var caught = new AtomicReference<Throwable>();
        var calledOn = new AtomicReference<Thread>();
        var store = new KVStore<StructuredKey, Object>(router,
                                                        stubSerializer(),
                                                        stubDeserializer(),
                                                        NotificationDelivery.dispatched("t17", DispatcherFailureSink.LoggingSink.INSTANCE));

        try {
            store.trackKeyClass(Tracked.class);
            put(store, GUARD_KEY, "open");
            router.addRoute(ValuePut.class, _ -> callAndRelease(store, caught, calledOn, delivered));
            put(store, new Tracked("a"), "1");

            assertThat(delivered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(caught.get()).isInstanceOf(IllegalStateException.class);
            assertThat(calledOn.get().getName()).as("the call ran on the dispatcher thread").isEqualTo(store.dispatcherThreadName());
            assertThat(calledOn.get()).isNotSameAs(Thread.currentThread());
        } finally {
            store.close();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void readUntilDone(KVStore<StructuredKey, Object> store,
                                      Map<StructuredKey, Object> setA,
                                      Map<StructuredKey, Object> setB,
                                      AtomicReference<Map<Tracked, Object>> mixed,
                                      AtomicBoolean done) {
        while (!done.get()) {
            var seen = entries(store);

            if (!isTrackedPart(setA, seen) && !isTrackedPart(setB, seen)) {
                mixed.set(seen);
                return;
            }
        }
    }

    private static boolean isTrackedPart(Map<StructuredKey, Object> image, Map<Tracked, Object> seen) {
        return trackedOnly(image).equals(seen);
    }

    private static Map<Tracked, Object> trackedOnly(Map<? extends StructuredKey, Object> storage) {
        return storage.entrySet()
                      .stream()
                      .filter(entry -> entry.getKey() instanceof Tracked)
                      .collect(Collectors.toMap(entry -> (Tracked) entry.getKey(), Map.Entry::getValue));
    }

    private Map<StructuredKey, Object> trackedImage(String prefix, int count) {
        var image = new HashMap<StructuredKey, Object>();

        image.put(GUARD_KEY, "open");

        for (var i = 0; i < count; i++) {
            image.put(new Tracked(prefix + i), "v" + i);
        }

        return image;
    }

    private void install(KVStore<StructuredKey, Object> store, Map<StructuredKey, Object> image) {
        restored = image;
        assertThat(store.restoreSnapshot(new byte[0]).isSuccess()).isTrue();
    }

    private static Map<StructuredKey, Object> image(Map<Tracked, Object> tracked) {
        var image = new HashMap<StructuredKey, Object>(tracked);

        image.put(GUARD_KEY, "open");

        return image;
    }

    private static void callOnDeliveryThread(KVStore<StructuredKey, Object> store,
                                             AtomicReference<Throwable> caught,
                                             AtomicReference<Thread> calledOn) {
        calledOn.set(Thread.currentThread());
        try {
            typed(store, OPEN);
        } catch (IllegalStateException e) {
            caught.set(e);
        }
    }

    private static void callAndRelease(KVStore<StructuredKey, Object> store,
                                       AtomicReference<Throwable> caught,
                                       AtomicReference<Thread> calledOn,
                                       CountDownLatch delivered) {
        callOnDeliveryThread(store, caught, calledOn);
        delivered.countDown();
    }

    private static boolean markCalled(AtomicBoolean called) {
        called.set(true);
        return true;
    }

    private static Option<Map<Tracked, Object>> typed(KVStore<StructuredKey, Object> store, Predicate<Object> guard) {
        return store.typedEntriesWhen(GUARD_KEY, guard, Tracked.class, Object.class);
    }

    private static Map<Tracked, Object> entries(KVStore<StructuredKey, Object> store) {
        return typed(store, OPEN).or(Map.of());
    }

    private static void assertMatchesStorage(KVStore<StructuredKey, Object> store) {
        assertThat(entries(store)).isEqualTo(trackedOnly(store.snapshot()));
    }

    private static void put(KVStore<StructuredKey, Object> store, StructuredKey key, Object value) {
        store.process(store.createBatch(List.of(new Put<>(key, value))));
    }

    private static void applyEffects(KVStore<StructuredKey, Object> replica, long slot, List<KVCommand<?>> effects) {
        var entry = new ReplicaEntry(IncarnationTag.NONE, slot, slot, "batch-" + slot, effects);

        assertThat(replica.applyEffects(entry).isSuccess()).isTrue();
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T read(ByteBuf byteBuf) {
                return (T) restored;
            }
        };
    }
}
