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
}
