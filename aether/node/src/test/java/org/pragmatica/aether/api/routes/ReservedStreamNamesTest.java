// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamEngineKey;
import org.pragmatica.aether.stream.topic.DurableTopicNames;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1282: the reserved kind prefixes are declared once in `slice-api` ([StreamEngineKey#RESERVED_KIND_PREFIXES])
/// because the blueprint parser cannot see the modules that OWN those names. This module sees all of them,
/// so it pins the shared list to the canonical owners: if a naming convention ever moves, both the
/// Management-API guard and the blueprint `External` check would silently stop matching real streams.
class ReservedStreamNamesTest {

    @Test
    void reservedKindPrefixes_matchTheCanonicalOwnersOfEachStreamKind() {
        assertThat(StreamEngineKey.RESERVED_KIND_PREFIXES).containsExactly(ResourceAddress.SYSTEM_NAMESPACE + ":",
                                                                          DurableTopicNames.TOPIC_STREAM_PREFIX,
                                                                          EntityPartitionArc.ARC_PREFIX);
    }
}
