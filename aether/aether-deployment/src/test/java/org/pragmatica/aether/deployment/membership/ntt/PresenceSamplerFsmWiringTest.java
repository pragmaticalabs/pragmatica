// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.ntt.PresenceSampler.presenceSampler;

/// Wiring proof for the sampler to FSM seam: a real [PresenceSampler] wired the way `AetherNode` wires it
/// (`presenceSampler.onDownHysteresisCrossing(membershipFsm::onDownHysteresisMet)`) into a real
/// [MembershipFsm]. A core the FSM promoted on one healthy sample, but the sampler never admitted (it
/// needs `K_UP`), must still reach Departing and leave `coreMembers()` once it goes absent.
class PresenceSamplerFsmWiringTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId C = NodeId.randomNodeId();
    private static final TimeSpan INTERVAL = TimeSpan.timeSpan(100).millis();
    private static final int K_UP = 2;
    private static final int K_DOWN = 3;

    private Map<NodeId, SwimHealth> liveness;
    private AtomicLong clock;
    private PresenceSampler sampler;
    private MembershipFsm fsm;

    @BeforeEach
    void setUp() {
        liveness = new HashMap<>();
        clock = new AtomicLong(0);
        sampler = presenceSampler(SELF,
                                  () -> HealthSnapshot.healthSnapshot(Map.copyOf(liveness)),
                                  INTERVAL,
                                  K_UP,
                                  K_DOWN,
                                  clock::get,
                                  () -> {});
        fsm = MembershipFsm.membershipFsm();
        sampler.onDownHysteresisCrossing(fsm::onDownHysteresisMet);
    }

    @Test
    void sample_neverAdmittedCore_goneAbsentAndSuspect_departsAndLeavesCoreMembers() {
        promoteCore(C);
        sampleTimes(K_UP - 1); // FSM is Member after one healthy sample; the sampler has not admitted C
        assertThat(sampler.currentMembers()).containsExactly(SELF);

        liveness.remove(C);
        fsm.onSwimFaulty(C, 2L);
        assertThat(fsm.memberStates()).containsEntry(C, "Suspect");
        sampleTimes(K_DOWN);

        assertThat(fsm.memberStates()).containsEntry(C, "Departing");
        assertThat(fsm.coreMembers()).doesNotContain(C);
    }

    @Test
    void sample_crossingLandsOnMember_nextWindowDepartsOnceSuspect() {
        promoteCore(C);
        sampleTimes(K_UP - 1);

        liveness.remove(C);
        sampleTimes(K_DOWN); // first crossing finds a Member and is absorbed
        assertThat(fsm.memberStates()).containsEntry(C, "Member");

        fsm.onSwimFaulty(C, 2L);
        sampleTimes(K_DOWN); // re-delivered crossing finds a Suspect

        assertThat(fsm.memberStates()).containsEntry(C, "Departing");
        assertThat(fsm.coreMembers()).doesNotContain(C);
    }

    private void promoteCore(NodeId id) {
        liveness.put(id, SwimHealth.HEALTHY);
        fsm.onMemberDescriptor(NodeInfo.nodeInfo(id, new NodeAddress(id.id(), 6000), Map.of(NodeInfo.LABEL_ROLE, "core")));
        fsm.onSwimHealthy(id, 1L);
    }

    private void sampleTimes(int times) {
        for (var i = 0; i < times; i++) {
            clock.addAndGet(1_000_000L);
            sampler.sample();
        }
    }
}
