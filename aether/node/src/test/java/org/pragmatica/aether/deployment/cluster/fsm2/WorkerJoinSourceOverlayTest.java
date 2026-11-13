// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// #3172: the source a `WorkerJoin` carries (read at enqueue) is overlaid on the seam snapshot before placement, because a sample taken behind
/// the join can be coalesced past it.
class WorkerJoinSourceOverlayTest {
    private static final NodeId W1 = node("worker-1");

    private static MbWorld world(Map<NodeId, String> snapshot) {
        return MbWorld.world(CORE_1, CORE_2, CORE_3)
                      .seams(seams -> seams.withMemberSource(snapshot))
                      .activate();
    }

    private static String communityOf(MbWorld w, NodeId node) {
        return w.h.store.get(new ActivationDirectiveKey(node))
                        .filter(ActivationDirectiveValue.class::isInstance)
                        .map(value -> ((ActivationDirectiveValue) value).communityId())
                        .or("");
    }

    /// Red when: the handler places against the snapshot alone, ignoring the source the join carries.
    @Test
    void joinSource_isUsedForPlacement_whenTheSnapshotHasNoneYet() {
        var w = world(Map.of());

        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, 21L), Option.some("eu")));
        assertThat(communityOf(w, W1)).isEqualTo("eu-w-0");
    }

    /// Red when: a blank or absent join source replaces a non-blank snapshot value.
    @Test
    void blankOrAbsentJoinSource_neverReplacesASnapshotValue() {
        var blank = world(Map.of(W1, "eu"));

        blank.h.deliver(new ClusterInput.WorkerJoin(W1, blank.admit(W1, 21L), Option.some(" ")));
        assertThat(communityOf(blank, W1)).isEqualTo("eu-w-0");
        var absent = world(Map.of(W1, "eu"));

        absent.h.deliver(new ClusterInput.WorkerJoin(W1, absent.admit(W1, 21L), Option.none()));
        assertThat(communityOf(absent, W1)).isEqualTo("eu-w-0");
    }

    /// Tripwire (#3172, ENABLED): with no source in the join and none in the snapshot the worker is placed in `default-w-0`, which is sticky. The
    /// production path never reaches this once membership has labelled the worker; if a change makes placement wait for or recover the source, this
    /// assertion must be updated deliberately.
    @Test
    void noSourceAnywhere_placesInDefault_tripwireFor3172() {
        var w = world(Map.of());

        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, 21L)));
        assertThat(communityOf(w, W1)).as("#3172 tripwire: absent source places in the sticky default community; update when placement learns to wait")
                                      .isEqualTo("default-w-0");
    }
}
