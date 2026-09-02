// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityCreateForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityDeleteForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityUpdateForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityUpdateForwardResponse;
import org.pragmatica.serialization.FrameworkCodecs;

import static org.assertj.core.api.Assertions.assertThatCode;

/// The #492 defect class, second occurrence (#596 wire pair): a `@Codec` package's generated
/// registry is INERT until NodeCodecs aggregates it, and an unaggregated wire type fails at the
/// transport with no sender-visible error — every entity owner-forward silently vanished, each
/// sender burned its full correlation timeout, and two integration campaigns measured the system
/// as one that can only write when the client happens to hit the owner directly. This pins the
/// four entityforward codecs into the aggregated node codec, so removing the aggregation line
/// goes red here instead of in a five-hour cluster run. (`lookupByClass` THROWS on a miss —
/// which is also what the transport swallowed silently.)
class NodeCodecsRoutedTypesTest {

    @Test
    void nodeCodecs_resolveEveryEntityForwardWireType() {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

        assertThatCode(() -> codec.lookupByClass(EntityUpdateForward.class))
            .as("EntityUpdateForward must be encodable — senders forward updates through it")
            .doesNotThrowAnyException();
        assertThatCode(() -> codec.lookupByClass(EntityCreateForward.class))
            .as("EntityCreateForward must be encodable — #596 was filed on creates failing")
            .doesNotThrowAnyException();
        assertThatCode(() -> codec.lookupByClass(EntityDeleteForward.class))
            .as("EntityDeleteForward must be encodable")
            .doesNotThrowAnyException();
        assertThatCode(() -> codec.lookupByClass(EntityUpdateForwardResponse.class))
            .as("the response leg must be encodable, or every forward times out on a silent answer")
            .doesNotThrowAnyException();
    }
}
