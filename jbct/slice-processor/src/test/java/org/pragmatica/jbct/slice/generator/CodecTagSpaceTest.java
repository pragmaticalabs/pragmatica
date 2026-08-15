// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/// [CodecTagSpace] is a deliberate copy of `SliceCodec#hashedTag`, kept so the processor can flag a
/// collision at compile time without dragging `serialization-api` (and Netty) onto every application's
/// annotation-processor path.
///
/// A copy needs a tripwire. The probe below is pinned identically by
/// `SliceCodecTest#hashedTag_pinnedProbeValue` on the other side, so a change to either derivation
/// fails a build instead of quietly turning the compile-time check into one that reports collisions
/// the runtime does not have — or misses the ones it does.
class CodecTagSpaceTest {

    @Test
    void hashedTag_pinnedProbeValue_matchesSliceCodecDerivation() {
        assertEquals(1785154, CodecTagSpace.hashedTag("com.example.CollisionProbe"));
    }

    /// The historical collision pair, kept on this side too: these two names shared tag 7612 under the
    /// pre-FNV derivation, and a processor that reproduced that clustering would report collisions the
    /// runtime no longer has.
    @Test
    void hashedTag_theHistoricalCollisionPair_noLongerCollides() {
        var checkpointValue = CodecTagSpace.hashedTag("org.pragmatica.aether.slice.kvstore.AetherValue.EntityCheckpointValue");
        var healthHint = CodecTagSpace.hashedTag("org.pragmatica.cluster.metrics.HealthHintWire");

        assertTrue(checkpointValue != healthHint);
    }

    @Test
    void hashedTag_anyName_landsInTheUserRange() {
        for (var name : new String[]{"a", "com.example.Alpha", "org.other.Delta", ""}) {
            var tag = CodecTagSpace.hashedTag(name);

            assertTrue(tag >= CodecTagSpace.USER_TAG_BASE && tag < CodecTagSpace.USER_TAG_LIMIT,
                       "Tag %d for \"%s\" is outside the user range".formatted(tag, name));
        }
    }
}
