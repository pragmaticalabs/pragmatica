// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.provenance;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.AppendLog.EpochKey;
import org.pragmatica.storage.AppendLog.EpochStart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1596: the log key of an owner epoch, and the order the log enforces between consecutive entries.
class ProvenanceEntryTest {
    @Test
    void key_roundTripsThroughTheLog_withAndWithoutTheIncarnationUlid() {
        var plain = ProvenanceEntry.provenanceEntry(Epoch.epoch(7, 3), Option.none(), 40);
        var withUlid = ProvenanceEntry.provenanceEntry(Epoch.epoch(7, 3), Option.some("01J9ZQ3V8K2M4N6P8R0T2V4X6Z"), 40);

        assertThat(decoded(plain)).isEqualTo(plain);
        assertThat(decoded(withUlid)).isEqualTo(withUlid);
        assertThat(plain.key().unwrap()).isNotEqualTo(withUlid.key().unwrap());
    }

    @Test
    void order_acceptsOnlyAStrictlyLaterEpoch() {
        var e1 = key(Epoch.epoch(1, 0), Option.none());
        var e2 = key(Epoch.epoch(1, 1), Option.none());

        assertThat(ProvenanceEntry.ORDER.follows(e2, e1)).isTrue();
        assertThat(ProvenanceEntry.ORDER.follows(e1, e2)).isFalse();
        assertThat(ProvenanceEntry.ORDER.follows(e1, e1)).isFalse();
    }

    /// #1625's shape: the same epoch minted under two incarnations. Neither follows the other, so one log can never
    /// hold both, and the two compare unequal as provenance.
    @Test
    void sameEpochDifferentUlid_followsNeitherWay_andIsNotTheSameEpoch() {
        var a = ProvenanceEntry.provenanceEntry(Epoch.epoch(4, 0), Option.some("A"), 0);
        var b = ProvenanceEntry.provenanceEntry(Epoch.epoch(4, 0), Option.some("B"), 0);

        assertThat(ProvenanceEntry.ORDER.follows(a.key().unwrap(), b.key().unwrap())).isFalse();
        assertThat(ProvenanceEntry.ORDER.follows(b.key().unwrap(), a.key().unwrap())).isFalse();
        assertThat(a.sameEpoch(b)).isFalse();
    }

    @Test
    void provenanceEntry_refusesAKeyThisCodecDidNotWrite() {
        ProvenanceEntry.provenanceEntry(new EpochStart(EpochKey.epochKey("seven").unwrap(), 0))
                       .onSuccess(_ -> fail("a foreign key must not decode as an epoch"));
    }

    /// TRIPWIRE: the key must carry EVERY component of [Epoch]. If #1529 part 2 adds `incarnation` to Epoch and the
    /// token is not extended, two epochs that differ only in it become ONE key, and two histories of different
    /// lineages compare equal -- a false CONSISTENT. When this fails, add the new component to
    /// `ProvenanceEntry.token`/`decode`, then update the expected list here.
    @Test
    void key_carriesEveryEpochComponent() {
        var components = Arrays.stream(Epoch.class.getRecordComponents())
                               .map(component -> component.getName())
                               .toList();

        assertThat(components).as("Epoch changed shape: extend ProvenanceEntry's key token to cover it")
                              .containsExactly("rabiaTerm", "localCounter");
    }

    private static ProvenanceEntry decoded(ProvenanceEntry entry) {
        return ProvenanceEntry.provenanceEntry(new EpochStart(entry.key().unwrap(), entry.startOffset())).unwrap();
    }

    private static EpochKey key(Epoch epoch, Option<String> ulid) {
        return ProvenanceEntry.provenanceEntry(epoch, ulid, 0).key().unwrap();
    }
}
