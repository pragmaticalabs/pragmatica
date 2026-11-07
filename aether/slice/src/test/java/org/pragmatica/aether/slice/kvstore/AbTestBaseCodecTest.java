// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestBaseKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestBaseValue;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;

import static org.assertj.core.api.Assertions.assertThat;

/// #2713: the A/B index travels the binary path consensus replication and snapshot transfer use (tags 2225 key, 2226 value). Each pin
/// has an arming counterpart: two records differing only in the component must decode differently.
class AbTestBaseCodecTest {
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), KvstoreCodecsSlice.CODECS);
    private static final ArtifactBase BASE = ArtifactBase.artifactBase("org.example:orders-slice").unwrap();
    private static final ArtifactBase OTHER = ArtifactBase.artifactBase("org.example:other-slice").unwrap();

    @Test
    void abTestBaseKey_roundTrips_andDistinguishesTheBase() {
        var key = AbTestBaseKey.abTestBaseKey(BASE);

        assertThat(roundTrip(key)).isEqualTo(key);
        assertThat(roundTrip(key)).isNotEqualTo(roundTrip(AbTestBaseKey.abTestBaseKey(OTHER)));
    }

    @Test
    void abTestBaseValue_roundTrips_andDistinguishesTheLiveTests() {
        var value = AbTestBaseValue.abTestBaseValue("t1=ACTIVE,t2=CONCLUDING");

        assertThat(roundTrip(value)).isEqualTo(value);
        assertThat(roundTrip(value)).isNotEqualTo(roundTrip(AbTestBaseValue.abTestBaseValue("t1=ROLLING_BACK,t2=CONCLUDING")));
    }

    @Test
    void abTestBaseKey_textForm_parsesBackAndRefusesAForeignPrefix() {
        var key = AbTestBaseKey.abTestBaseKey(BASE);

        assertThat(AbTestBaseKey.abTestBaseKey(key.asString()).unwrap()).isEqualTo(key);
        assertThat(AbTestBaseKey.abTestBaseKey("ab-test/" + BASE.asString()).isFailure()).isTrue();
    }

    private static <T> T roundTrip(T original) {
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, original);

        return CODEC.read(buffer);
    }
}
