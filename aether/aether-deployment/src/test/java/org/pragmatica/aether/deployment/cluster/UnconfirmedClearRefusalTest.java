// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager.UnconfirmedClearRefusal;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;


/// #2062: what an operator reads when a clear is refused. Each message names what the operator needs to act: the node, the instance and its state,
/// and the way out.
class UnconfirmedClearRefusalTest {
    private static final NodeId NODE = new NodeId("core-old");

    @Test
    void anInstanceStillListed_namesNodeInstanceStateAndTheForceWayOut() {
        assertThat(new UnconfirmedClearRefusal.InstanceStillListed(NODE, "i-1", "RUNNING").message())
            .contains("core-old").contains("i-1").contains("RUNNING").contains("terminate it first").contains("force");
    }

    @Test
    void aProviderThatCannotBeAsked_namesNodeCauseAndTheWayOut() {
        assertThat(new UnconfirmedClearRefusal.ProviderUnreachable(NODE, "API down").message())
            .contains("core-old").contains("API down").contains("nothing was cleared").contains("force");
    }

    @Test
    void aChangedMark_saysTheClearWasNotConfirmedCommitted_notThatNothingWasCleared() {
        var message = new UnconfirmedClearRefusal.Changed(NODE).message();

        assertThat(message).contains("core-old").contains("not confirmed committed").contains("read the mark again");
        assertThat(message).doesNotContain("nothing was cleared");
    }

    @Test
    void anExternalNode_saysThereIsNoProviderToCheckAndTheWayOut() {
        assertThat(new UnconfirmedClearRefusal.ExternalNodeUnchecked(NODE).message())
            .contains("core-old").contains("no provider to check this external node; use --force after confirming it is gone");
    }

    @Test
    void aLiveNode_saysItsInstanceMustNotBeTerminated() {
        assertThat(new UnconfirmedClearRefusal.NodeLive(NODE).message()).contains("core-old").contains("shows life").contains("must not be terminated");
    }

    @Test
    void noMark_andNotActive_areSaidPlainly() {
        assertThat(new UnconfirmedClearRefusal.NotMarked(NODE).message()).contains("core-old").contains("no unconfirmed-termination mark");
        assertThat(new UnconfirmedClearRefusal.NotActive().message()).contains("active leader");
    }
}
