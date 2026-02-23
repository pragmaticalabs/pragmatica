package org.pragmatica.aether.resource.interceptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class TieredCacheTest {

    private InMemoryCache l1;
    private InMemoryCache l2;
    private TieredCache tiered;

    @BeforeEach
    void setUp() {
        l1 = InMemoryCache.inMemoryCache(60, 100);
        l2 = InMemoryCache.inMemoryCache(60, 100);
        tiered = TieredCache.tieredCache(l1, l2);
    }

    @Test
    void get_l1Hit_returnsWithoutCheckingL2() {
        l1.put("key1", "l1-value").await();

        var result = tiered.get("key1").await().fold(_ -> Option.none(), v -> v);

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isEqualTo("l1-value");
    }

    @Test
    void get_l1MissL2Hit_promotesToL1() {
        l2.put("key1", "l2-value").await();

        var result = tiered.get("key1").await().fold(_ -> Option.none(), v -> v);

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap()).isEqualTo("l2-value");

        var l1Result = l1.get("key1").await().fold(_ -> Option.none(), v -> v);
        assertThat(l1Result.isPresent()).as("Value should be promoted to L1").isTrue();
        assertThat(l1Result.unwrap()).isEqualTo("l2-value");
    }

    @Test
    void get_bothMiss_returnsNone() {
        var result = tiered.get("key1").await().fold(_ -> Option.none(), v -> v);

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void put_writesToBothLevels() {
        tiered.put("key1", "value").await();

        var l1Result = l1.get("key1").await().fold(_ -> Option.none(), v -> v);
        var l2Result = l2.get("key1").await().fold(_ -> Option.none(), v -> v);

        assertThat(l1Result.isPresent()).isTrue();
        assertThat(l1Result.unwrap()).isEqualTo("value");
        assertThat(l2Result.isPresent()).isTrue();
        assertThat(l2Result.unwrap()).isEqualTo("value");
    }

    @Test
    void remove_removesFromBothLevels() {
        l1.put("key1", "v1").await();
        l2.put("key1", "v2").await();

        tiered.remove("key1").await();

        var l1Result = l1.get("key1").await().fold(_ -> Option.none(), v -> v);
        var l2Result = l2.get("key1").await().fold(_ -> Option.none(), v -> v);

        assertThat(l1Result.isEmpty()).isTrue();
        assertThat(l2Result.isEmpty()).isTrue();
    }
}
