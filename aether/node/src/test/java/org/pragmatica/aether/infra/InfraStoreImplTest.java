package org.pragmatica.aether.infra;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InfraStoreImplTest {

    private InfraStoreImpl store;

    @BeforeEach
    void setUp() {
        store = InfraStoreImpl.infraStoreImpl();
    }

    @Test
    void getOrCreate_creates_new_instance_when_not_exists() {
        var instance = store.getOrCreate("org.example:infra-cache", "1.0.0", String.class, () -> "created");

        assertThat(instance).isEqualTo("created");
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void getOrCreate_returns_existing_instance_for_same_version() {
        var counter = new AtomicInteger(0);

        var first = store.getOrCreate("org.example:infra-cache", "1.0.0", Integer.class, counter::incrementAndGet);
        var second = store.getOrCreate("org.example:infra-cache", "1.0.0", Integer.class, counter::incrementAndGet);

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void getOrCreate_creates_separate_instances_for_different_versions() {
        var v1 = store.getOrCreate("org.example:infra-cache", "1.0.0", String.class, () -> "v1");
        var v2 = store.getOrCreate("org.example:infra-cache", "2.0.0", String.class, () -> "v2");

        assertThat(v1).isEqualTo("v1");
        assertThat(v2).isEqualTo("v2");
    }

    @Test
    void getOrCreate_strips_qualifier_for_version_matching() {
        var v1 = store.getOrCreate("org.example:infra-cache", "1.0.0-SNAPSHOT", String.class, () -> "snapshot");
        var v2 = store.getOrCreate("org.example:infra-cache", "1.0.0", String.class, () -> "release");

        assertThat(v1).isEqualTo("snapshot");
        assertThat(v2).isEqualTo("snapshot");
    }

    @Test
    void get_returns_empty_list_for_unknown_artifact() {
        var instances = store.get("org.example:unknown", String.class);

        assertThat(instances).isEmpty();
    }

    @Test
    void get_returns_all_versions_for_artifact() {
        store.getOrCreate("org.example:infra-cache", "1.0.0", String.class, () -> "v1");
        store.getOrCreate("org.example:infra-cache", "2.0.0", String.class, () -> "v2");

        var instances = store.get("org.example:infra-cache", String.class);

        assertThat(instances).hasSize(2);
        assertThat(instances.stream()
                            .map(VersionedInstance::instance)
                            .toList()).containsExactlyInAnyOrder("v1", "v2");
    }

    @Test
    void get_filters_by_type() {
        store.getOrCreate("org.example:infra-cache", "1.0.0", String.class, () -> "string");
        store.getOrCreate("org.example:infra-cache", "2.0.0", Integer.class, () -> 42);

        var stringInstances = store.get("org.example:infra-cache", String.class);
        var intInstances = store.get("org.example:infra-cache", Integer.class);

        assertThat(stringInstances).hasSize(1);
        assertThat(stringInstances.getFirst()
                                  .instance()).isEqualTo("string");
        assertThat(intInstances).hasSize(1);
        assertThat(intInstances.getFirst()
                               .instance()).isEqualTo(42);
    }

    @Test
    void clear_removes_all_instances() {
        store.getOrCreate("org.example:infra-cache", "1.0.0", String.class, () -> "v1");
        store.getOrCreate("org.example:infra-db", "1.0.0", String.class, () -> "db");

        store.clear();

        assertThat(store.size()).isZero();
        assertThat(store.get("org.example:infra-cache", String.class)).isEmpty();
    }

    @Test
    void getOrCreate_is_thread_safe() throws InterruptedException {
        var counter = new AtomicInteger(0);
        var threads = 10;
        var latch = new CountDownLatch(threads);
        var results = new Integer[threads];

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            new Thread(() -> {
                results[idx] = store.getOrCreate("org.example:infra-cache", "1.0.0", Integer.class, counter::incrementAndGet);
                latch.countDown();
            }).start();
        }

        latch.await();

        // All threads should get the same instance
        for (int i = 0; i < threads; i++) {
            assertThat(results[i]).isEqualTo(1);
        }
        // Factory should only be called once
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void different_artifacts_are_independent() {
        var cache = store.getOrCreate("org.example:infra-cache", "1.0.0", String.class, () -> "cache");
        var db = store.getOrCreate("org.example:infra-db", "1.0.0", String.class, () -> "db");

        assertThat(cache).isEqualTo("cache");
        assertThat(db).isEqualTo("db");
        assertThat(store.size()).isEqualTo(2);
    }
}
