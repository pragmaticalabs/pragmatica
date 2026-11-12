// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2995 — `killNode(null)` fails with a cause instead of an NPE, and the relay test's await cannot pass while no core has been asked.
class EmberKillNodeNullIdTest {
    @Test
    void killNode_refusesNullIdWithCause_insteadOfNpe() {
        var result = EmberCluster.emberCluster(1).killNode(null, false);

        assertThat(result.isResolved()).isTrue();
        result.await()
              .onSuccess(_ -> Assertions.fail("a null id must fail"))
              .onFailure(cause -> assertThat(cause.message()).contains("null"));
    }

    @Test
    void emptyStalledCore_isNotFollowedByASourcelessJoiner() {
        assertThat(EmberReplicaRelayTest.followsStalledCore(Option.none(), null)).isFalse();
        assertThat(EmberReplicaRelayTest.followsStalledCore(Option.some("c1"), "c1")).isTrue();
        assertThat(EmberReplicaRelayTest.followsStalledCore(Option.none(), "c1")).isFalse();
    }
}
