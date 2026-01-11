package org.pragmatica.aether.infra.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CacheServiceTest {
    private CacheService cache;

    @BeforeEach
    void setUp() {
        cache = CacheService.cacheService();
    }

    @AfterEach
    void tearDown() {
        cache.stop().await();
    }

    @Test
    void set_and_get_value() {
        cache.set("key1", "value1").await();

        cache.get("key1")
             .await()
             .onFailure(c -> Assertions.fail())
             .onSuccess(opt -> opt.onPresent(value -> assertThat(value).isEqualTo("value1"))
                                  .onEmptyRun(Assertions::fail));
    }

    @Test
    void get_returns_empty_for_missing_key() {
        cache.get("nonexistent")
             .await()
             .onFailure(c -> Assertions.fail())
             .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
    }

    @Test
    void delete_removes_existing_key() {
        cache.set("key1", "value1").await();

        cache.delete("key1")
             .await()
             .onFailure(c -> Assertions.fail())
             .onSuccess(deleted -> assertThat(deleted).isTrue());

        cache.get("key1")
             .await()
             .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
    }

    @Test
    void delete_returns_false_for_missing_key() {
        cache.delete("nonexistent")
             .await()
             .onFailure(c -> Assertions.fail())
             .onSuccess(deleted -> assertThat(deleted).isFalse());
    }

    @Test
    void exists_returns_true_for_existing_key() {
        cache.set("key1", "value1").await();

        cache.exists("key1")
             .await()
             .onFailure(c -> Assertions.fail())
             .onSuccess(exists -> assertThat(exists).isTrue());
    }

    @Test
    void exists_returns_false_for_missing_key() {
        cache.exists("nonexistent")
             .await()
             .onFailure(c -> Assertions.fail())
             .onSuccess(exists -> assertThat(exists).isFalse());
    }

    @Test
    void set_with_ttl_expires_entry() throws InterruptedException {
        cache.set("key1", "value1", Duration.ofMillis(50)).await();

        // Should exist immediately
        cache.exists("key1")
             .await()
             .onSuccess(exists -> assertThat(exists).isTrue());

        // Wait for expiry
        Thread.sleep(100);

        // Should be gone
        cache.exists("key1")
             .await()
             .onSuccess(exists -> assertThat(exists).isFalse());
    }

    @Test
    void expire_sets_ttl_on_existing_key() throws InterruptedException {
        cache.set("key1", "value1").await();

        cache.expire("key1", Duration.ofMillis(50))
             .await()
             .onSuccess(result -> assertThat(result).isTrue());

        Thread.sleep(100);

        cache.exists("key1")
             .await()
             .onSuccess(exists -> assertThat(exists).isFalse());
    }

    @Test
    void expire_returns_false_for_missing_key() {
        cache.expire("nonexistent", Duration.ofMinutes(1))
             .await()
             .onFailure(c -> Assertions.fail())
             .onSuccess(result -> assertThat(result).isFalse());
    }

    @Test
    void ttl_returns_remaining_time() {
        cache.set("key1", "value1", Duration.ofSeconds(10)).await();

        cache.ttl("key1")
             .await()
             .onFailure(c -> Assertions.fail())
             .onSuccess(opt -> {
                 assertThat(opt.isPresent()).isTrue();
                 opt.onPresent(remaining -> assertThat(remaining.toSeconds()).isGreaterThan(0));
             });
    }

    @Test
    void ttl_returns_empty_for_key_without_ttl() {
        cache.set("key1", "value1").await();

        cache.ttl("key1")
             .await()
             .onFailure(c -> Assertions.fail())
             .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
    }

    @Test
    void ttl_returns_empty_for_missing_key() {
        cache.ttl("nonexistent")
             .await()
             .onFailure(c -> Assertions.fail())
             .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
    }

    @Test
    void factory_method_creates_instance() {
        var service = CacheService.cacheService();
        assertThat(service).isNotNull();
        service.stop().await();
    }

    // ========== Batch Operations ==========

    @Test
    void getMulti_returns_existing_values() {
        cache.set("key1", "value1").await();
        cache.set("key2", "value2").await();

        cache.getMulti(java.util.Set.of("key1", "key2", "key3"))
             .await()
             .onFailure(c -> Assertions.fail())
             .onSuccess(result -> {
                 assertThat(result).hasSize(2);
                 assertThat(result.get("key1")).isEqualTo("value1");
                 assertThat(result.get("key2")).isEqualTo("value2");
                 assertThat(result.containsKey("key3")).isFalse();
             });
    }

    @Test
    void setMulti_sets_multiple_values() {
        cache.setMulti(java.util.Map.of("key1", "value1", "key2", "value2")).await();

        cache.get("key1").await()
             .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo("value1")));
        cache.get("key2").await()
             .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo("value2")));
    }

    @Test
    void deleteMulti_deletes_multiple_keys() {
        cache.set("key1", "value1").await();
        cache.set("key2", "value2").await();
        cache.set("key3", "value3").await();

        cache.deleteMulti(java.util.Set.of("key1", "key2", "nonexistent"))
             .await()
             .onSuccess(count -> assertThat(count).isEqualTo(2));

        cache.exists("key1").await().onSuccess(exists -> assertThat(exists).isFalse());
        cache.exists("key3").await().onSuccess(exists -> assertThat(exists).isTrue());
    }

    // ========== Counter Operations ==========

    @Test
    void increment_creates_counter_if_not_exists() {
        cache.increment("counter")
             .await()
             .onSuccess(value -> assertThat(value).isEqualTo(1L));
    }

    @Test
    void increment_increments_existing_counter() {
        cache.set("counter", "5").await();

        cache.increment("counter")
             .await()
             .onSuccess(value -> assertThat(value).isEqualTo(6L));
    }

    @Test
    void incrementBy_adds_delta() {
        cache.set("counter", "10").await();

        cache.incrementBy("counter", 5)
             .await()
             .onSuccess(value -> assertThat(value).isEqualTo(15L));
    }

    @Test
    void decrement_decrements_counter() {
        cache.set("counter", "10").await();

        cache.decrement("counter")
             .await()
             .onSuccess(value -> assertThat(value).isEqualTo(9L));
    }

    // ========== Pattern Operations ==========

    @Test
    void keys_returns_matching_keys() {
        cache.set("user:1", "alice").await();
        cache.set("user:2", "bob").await();
        cache.set("item:1", "widget").await();

        cache.keys("user:*")
             .await()
             .onSuccess(keys -> {
                 assertThat(keys).hasSize(2);
                 assertThat(keys).containsExactlyInAnyOrder("user:1", "user:2");
             });
    }

    @Test
    void deletePattern_deletes_matching_keys() {
        cache.set("user:1", "alice").await();
        cache.set("user:2", "bob").await();
        cache.set("item:1", "widget").await();

        cache.deletePattern("user:*")
             .await()
             .onSuccess(count -> assertThat(count).isEqualTo(2));

        cache.exists("user:1").await().onSuccess(exists -> assertThat(exists).isFalse());
        cache.exists("item:1").await().onSuccess(exists -> assertThat(exists).isTrue());
    }

    // ========== Statistics ==========

    @Test
    void stats_tracks_hits_and_misses() {
        cache.set("key1", "value1").await();

        cache.get("key1").await();  // hit
        cache.get("key1").await();  // hit
        cache.get("missing").await();  // miss

        cache.stats()
             .await()
             .onSuccess(stats -> {
                 assertThat(stats.hits()).isEqualTo(2);
                 assertThat(stats.misses()).isEqualTo(1);
                 assertThat(stats.hitRate()).isCloseTo(0.666, org.assertj.core.data.Offset.offset(0.01));
             });
    }

    @Test
    void clear_removes_all_entries() {
        cache.set("key1", "value1").await();
        cache.set("key2", "value2").await();

        cache.clear().await();

        cache.exists("key1").await().onSuccess(exists -> assertThat(exists).isFalse());
        cache.exists("key2").await().onSuccess(exists -> assertThat(exists).isFalse());
    }

    // ========== Configuration ==========

    @Test
    void cacheConfig_with_custom_settings() {
        var config = CacheConfig.cacheConfig("test-cache", 100)
                                .fold(_ -> null, c -> c);
        assertThat(config).isNotNull();
        assertThat(config.name()).isEqualTo("test-cache");
        assertThat(config.maxSize()).isEqualTo(100);

        var customCache = CacheService.cacheService(config);
        assertThat(customCache).isNotNull();
        customCache.stop().await();
    }
}
