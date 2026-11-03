// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Survivor pins for [PMap] (F1 closure, lane fsmf1): each test names the line whose mutation it holds.
class PMapSurvivorPinsTest {
    /// Every instance hashes alike, so distinct ids land in one collision bucket.
    private record Clash(int id) {
        @Override
        public int hashCode() {
            return 7;
        }
    }

    private static PMap<Integer, Integer> filled(ScanMeter meter, int size) {
        var map = PMap.<Integer, Integer>empty(meter);

        for (var i = 0; i < size; i++) {
            map = map.put(i, i);
        }

        return map;
    }

    /// Red when: a map of exactly `SCAN_THRESHOLD` entries counts its iteration as a scan (`>` weakened to `>=`).
    @Test
    void entries_atTheScanThreshold_isNotCounted_onePastItIs() {
        var meter = ScanMeter.scanMeter();
        var atThreshold = filled(meter, PMap.SCAN_THRESHOLD);

        atThreshold.entries();
        assertThat(meter.count()).as("at the threshold: not a scan").isZero();
        atThreshold.put(-1, -1).entries();
        assertThat(meter.count()).as("one past it: a scan").isEqualTo(1L);
    }

    /// Red when: removing the key at index 0 of a collision bucket finds nothing (`at < 0` weakened to `<= 0`).
    @Test
    void remove_everyKeyOfAThreeKeyBucket_removesIt() {
        for (var id = 1; id <= 3; id++) {
            var map = PMap.<Clash, String>empty()
                          .put(new Clash(1), "one")
                          .put(new Clash(2), "two")
                          .put(new Clash(3), "three")
                          .remove(new Clash(id));

            assertThat(map.size()).as("key " + id + " removed").isEqualTo(2);
            assertThat(map.containsKey(new Clash(id))).as("key " + id + " gone").isFalse();
        }
    }

    /// Red when: the same, for a two-key bucket (which collapses to the survivor).
    @Test
    void remove_everyKeyOfATwoKeyBucket_leavesTheOther() {
        for (var id = 1; id <= 2; id++) {
            var other = 3 - id;
            var map = PMap.<Clash, String>empty()
                          .put(new Clash(1), "one")
                          .put(new Clash(2), "two")
                          .remove(new Clash(id));

            assertThat(map.size()).isEqualTo(1);
            assertThat(map.containsKey(new Clash(id))).isFalse();
            assertThat(map.containsKey(new Clash(other))).isTrue();
        }
    }
}
