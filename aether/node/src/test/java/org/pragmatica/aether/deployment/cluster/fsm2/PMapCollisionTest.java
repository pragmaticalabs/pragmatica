// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The collision bucket of [PMap]: distinct keys with one hash share a bucket, and the bucket is searched by key equality.
/// Pins `indexOfKey`: its loop bound runs one past the bucket when a distinct key is inserted into a bucket that already
/// holds two, which throws instead of appending.
class PMapCollisionTest {
    /// Every instance hashes alike, so distinct ids land in one collision bucket.
    private record Clash(int id) {
        @Override
        public int hashCode() {
            return 7;
        }
    }

    private static PMap<Clash, String> three() {
        return PMap.<Clash, String>empty()
                   .put(new Clash(1), "one")
                   .put(new Clash(2), "two")
                   .put(new Clash(3), "three");
    }

    @Test
    void put_thirdDistinctKeyInOneBucket_isAppended() {
        var map = three();

        assertThat(map.size()).isEqualTo(3);
        assertThat(map.get(new Clash(1)).or("absent")).isEqualTo("one");
        assertThat(map.get(new Clash(2)).or("absent")).isEqualTo("two");
        assertThat(map.get(new Clash(3)).or("absent")).isEqualTo("three");
    }

    @Test
    void put_existingKeyInABucket_replacesWithoutGrowing() {
        var map = three().put(new Clash(2), "TWO");

        assertThat(map.size()).isEqualTo(3);
        assertThat(map.get(new Clash(2)).or("absent")).isEqualTo("TWO");
        assertThat(map.get(new Clash(3)).or("absent")).isEqualTo("three");
    }

    @Test
    void get_absentKeyWithTheBucketsHash_isAbsent() {
        assertThat(three().get(new Clash(4)).isPresent()).isFalse();
    }

    @Test
    void remove_oneKeyOfABucket_leavesTheOthers() {
        var map = three().remove(new Clash(2));

        assertThat(map.size()).isEqualTo(2);
        assertThat(map.containsKey(new Clash(2))).isFalse();
        assertThat(map.get(new Clash(1)).or("absent")).isEqualTo("one");
        assertThat(map.get(new Clash(3)).or("absent")).isEqualTo("three");
    }
}
