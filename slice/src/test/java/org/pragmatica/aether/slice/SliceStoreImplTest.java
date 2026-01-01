package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceStoreImpl.EntryState;
import org.pragmatica.aether.slice.SliceStoreImpl.LoadedSliceEntry;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.net.URL;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SliceStoreImplTest {

    private SliceRegistry registry;
    private SharedLibraryClassLoader sharedLoader;
    private Artifact artifact;

    @BeforeEach
    void setUp() {
        registry = SliceRegistry.sliceRegistry();
        sharedLoader = new SharedLibraryClassLoader(getClass().getClassLoader());
        artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
    }

    // === LoadedSliceEntry Tests ===

    @Test
    void loaded_slice_entry_returns_artifact() {
        var slice = createTestSlice();
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());
        var entry = new LoadedSliceEntry(artifact, slice, classLoader, EntryState.LOADED);

        assertThat(entry.artifact()).isEqualTo(artifact);
    }

    @Test
    void loaded_slice_entry_returns_slice() {
        var slice = createTestSlice();
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());
        var entry = new LoadedSliceEntry(artifact, slice, classLoader, EntryState.LOADED);

        assertThat(entry.slice()).isSameAs(slice);
    }

    @Test
    void loaded_slice_entry_with_state_returns_new_entry() {
        var slice = createTestSlice();
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());
        var entry = new LoadedSliceEntry(artifact, slice, classLoader, EntryState.LOADED);

        var activeEntry = entry.withState(EntryState.ACTIVE);

        assertThat(activeEntry.state()).isEqualTo(EntryState.ACTIVE);
        assertThat(activeEntry.artifact()).isEqualTo(artifact);
        assertThat(activeEntry.sliceInstance()).isSameAs(slice);
        assertThat(activeEntry.classLoader()).isSameAs(classLoader);
    }

    // === Factory Method Tests ===

    @Test
    void slice_store_factory_creates_instance() {
        var store = SliceStore.sliceStore(registry, List.of(), sharedLoader);

        assertThat(store).isNotNull();
        assertThat(store.loaded()).isEmpty();
    }

    // === Activation Tests ===

    @Test
    void activate_calls_start_on_slice() {
        var startCalled = new AtomicBoolean(false);
        var slice = createTestSlice(() -> {
            startCalled.set(true);
            return Promise.success(Unit.unit());
        }, () -> Promise.success(Unit.unit()));

        var store = createStoreWithPreloadedSlice(slice, EntryState.LOADED);

        store.activateSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);

        assertThat(startCalled.get()).isTrue();
    }

    @Test
    void activate_transitions_to_active_state() {
        var slice = createTestSlice();
        var store = createStoreWithPreloadedSlice(slice, EntryState.LOADED);

        store.activateSlice(artifact)
             .await()
             .onSuccess(loaded -> {
                 var entry = (LoadedSliceEntry) loaded;
                 assertThat(entry.state()).isEqualTo(EntryState.ACTIVE);
             })
             .onFailureRun(Assertions::fail);
    }

    @Test
    void activate_already_active_returns_success() {
        var startCount = new AtomicInteger(0);
        var slice = createTestSlice(() -> {
            startCount.incrementAndGet();
            return Promise.success(Unit.unit());
        }, () -> Promise.success(Unit.unit()));

        var store = createStoreWithPreloadedSlice(slice, EntryState.ACTIVE);

        store.activateSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);

        // Should not call start again
        assertThat(startCount.get()).isEqualTo(0);
    }

    @Test
    void activate_not_loaded_fails() {
        var store = SliceStore.sliceStore(registry, List.of(), sharedLoader);

        store.activateSlice(artifact)
             .await()
             .onSuccessRun(Assertions::fail)
             .onFailure(cause -> assertThat(cause.message()).contains("not loaded"));
    }

    // === Deactivation Tests ===

    @Test
    void deactivate_calls_stop_on_slice() {
        var stopCalled = new AtomicBoolean(false);
        var slice = createTestSlice(
                () -> Promise.success(Unit.unit()),
                () -> {
                    stopCalled.set(true);
                    return Promise.success(Unit.unit());
                });

        var store = createStoreWithPreloadedSlice(slice, EntryState.ACTIVE);

        store.deactivateSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);

        assertThat(stopCalled.get()).isTrue();
    }

    @Test
    void deactivate_transitions_to_loaded_state() {
        var slice = createTestSlice();
        var store = createStoreWithPreloadedSlice(slice, EntryState.ACTIVE);

        store.deactivateSlice(artifact)
             .await()
             .onSuccess(loaded -> {
                 var entry = (LoadedSliceEntry) loaded;
                 assertThat(entry.state()).isEqualTo(EntryState.LOADED);
             })
             .onFailureRun(Assertions::fail);
    }

    @Test
    void deactivate_already_loaded_returns_success() {
        var stopCount = new AtomicInteger(0);
        var slice = createTestSlice(
                () -> Promise.success(Unit.unit()),
                () -> {
                    stopCount.incrementAndGet();
                    return Promise.success(Unit.unit());
                });

        var store = createStoreWithPreloadedSlice(slice, EntryState.LOADED);

        store.deactivateSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);

        // Should not call stop
        assertThat(stopCount.get()).isEqualTo(0);
    }

    @Test
    void deactivate_not_loaded_fails() {
        var store = SliceStore.sliceStore(registry, List.of(), sharedLoader);

        store.deactivateSlice(artifact)
             .await()
             .onSuccessRun(Assertions::fail)
             .onFailure(cause -> assertThat(cause.message()).contains("not loaded"));
    }

    // === Unload Tests ===

    @Test
    void unload_removes_from_loaded_list() {
        var slice = createTestSlice();
        var store = createStoreWithPreloadedSlice(slice, EntryState.LOADED);

        assertThat(store.loaded()).hasSize(1);

        store.unloadSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);

        assertThat(store.loaded()).isEmpty();
    }

    @Test
    void unload_active_calls_stop_first() {
        var stopCalled = new AtomicBoolean(false);
        var slice = createTestSlice(
                () -> Promise.success(Unit.unit()),
                () -> {
                    stopCalled.set(true);
                    return Promise.success(Unit.unit());
                });

        var store = createStoreWithPreloadedSlice(slice, EntryState.ACTIVE);

        store.unloadSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);

        assertThat(stopCalled.get()).isTrue();
    }

    @Test
    void unload_loaded_does_not_call_stop() {
        var stopCount = new AtomicInteger(0);
        var slice = createTestSlice(
                () -> Promise.success(Unit.unit()),
                () -> {
                    stopCount.incrementAndGet();
                    return Promise.success(Unit.unit());
                });

        var store = createStoreWithPreloadedSlice(slice, EntryState.LOADED);

        store.unloadSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);

        assertThat(stopCount.get()).isEqualTo(0);
    }

    @Test
    void unload_nonexistent_succeeds() {
        var store = SliceStore.sliceStore(registry, List.of(), sharedLoader);

        store.unloadSlice(artifact)
             .await()
             .onFailureRun(Assertions::fail);
    }

    // === Loaded List Tests ===

    @Test
    void loaded_returns_all_entries() {
        var slice1 = createTestSlice();
        var slice2 = createTestSlice();
        var artifact1 = Artifact.artifact("org.example:slice1:1.0.0").unwrap();
        var artifact2 = Artifact.artifact("org.example:slice2:1.0.0").unwrap();

        var store = SliceStoreImpl.sliceStore(registry, List.of(), sharedLoader);
        addPreloadedSlice(store, artifact1, slice1, EntryState.LOADED);
        addPreloadedSlice(store, artifact2, slice2, EntryState.ACTIVE);

        var loaded = store.loaded();

        assertThat(loaded).hasSize(2);
        assertThat(loaded.stream().map(SliceStore.LoadedSlice::artifact))
                .containsExactlyInAnyOrder(artifact1, artifact2);
    }

    // === Helper Methods ===

    private Slice createTestSlice() {
        return createTestSlice(
                () -> Promise.success(Unit.unit()),
                () -> Promise.success(Unit.unit())
                              );
    }

    private Slice createTestSlice(
            java.util.function.Supplier<Promise<Unit>> startSupplier,
            java.util.function.Supplier<Promise<Unit>> stopSupplier
                                 ) {
        return new Slice() {
            @Override
            public Promise<Unit> start() {
                return startSupplier.get();
            }

            @Override
            public Promise<Unit> stop() {
                return stopSupplier.get();
            }

            @Override
            public List<SliceMethod<?, ?>> methods() {
                return List.of();
            }
        };
    }

    private SliceStore createStoreWithPreloadedSlice(Slice slice, EntryState state) {
        var store = SliceStoreImpl.sliceStore(registry, List.of(), sharedLoader);
        addPreloadedSlice(store, artifact, slice, state);
        return store;
    }

    @SuppressWarnings("unchecked")
    private void addPreloadedSlice(SliceStore store, Artifact artifact, Slice slice, EntryState state) {
        // Access internal map via reflection-like approach
        // Since SliceStoreRecord is a record, we need to work with its entries map
        var impl = (SliceStoreImpl.SliceStoreRecord) store;
        var classLoader = new SliceClassLoader(new URL[0], getClass().getClassLoader());
        var entry = new LoadedSliceEntry(artifact, slice, classLoader, state);
        impl.entries().put(artifact, entry);
    }
}
