// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1200 — the delivered form of the cluster's current gossip key.
class GossipKeyMaterialTest {
    private static final byte[] KEY = filled((byte) 0x5a);
    private static final String ENCODED = Base64.getEncoder().encodeToString(KEY);

    @Test
    void gossipKeyMaterial_wellFormed_parsesIdAndKey() {
        GossipKeyMaterial.gossipKeyMaterial("7:" + ENCODED)
                         .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()))
                         .onSuccess(material -> {
                             assertThat(material.keyId()).isEqualTo(7);
                             assertThat(material.key()).isEqualTo(KEY);
                         });
    }

    @Test
    void render_thenParse_roundTrips() {
        var parsed = GossipKeyMaterial.gossipKeyMaterial(GossipKeyMaterial.render(42, ENCODED)).unwrap();

        assertThat(parsed.keyId()).isEqualTo(42);
        assertThat(parsed.asGossipKey().key()).isEqualTo(KEY);
        assertThat(parsed.asGossipKey().keyId()).isEqualTo(42);
    }

    @Test
    void gossipKeyMaterial_surroundingWhitespace_isTolerated_asAFileTrailerIs() {
        assertThat(GossipKeyMaterial.gossipKeyMaterial("  3:" + ENCODED + "  ").isSuccess()).isTrue();
    }

    @Test
    void gossipKeyMaterial_malformed_isRefused_andNeverEchoesTheValue() {
        for (var bad : new String[]{"", "no-separator", ":" + ENCODED, "x:" + ENCODED, "1:not base64!!", "1:" + Base64.getEncoder().encodeToString(new byte[16]),
                                    "1:" + Base64.getEncoder().encodeToString(new byte[33])}) {
            var outcome = GossipKeyMaterial.gossipKeyMaterial(bad);

            assertThat(outcome.isFailure()).as("rejected: '%s'", bad).isTrue();
            outcome.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("accepted " + bad))
                   .onFailure(cause -> assertThat(cause.message()).doesNotContain(ENCODED));
        }
    }

    @Test
    void toString_redactsTheKey() {
        var material = GossipKeyMaterial.gossipKeyMaterial("9:" + ENCODED).unwrap();

        assertThat(material.toString()).contains("keyId=9").doesNotContain(ENCODED).doesNotContain(Arrays.toString(KEY));
    }

    private static byte[] filled(byte value) {
        var key = new byte[32];

        Arrays.fill(key, value);

        return key;
    }
}
