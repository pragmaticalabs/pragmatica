// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 F2: `cluster upgrade --wait` polls one endpoint, and the run replaces that very node. The poll must carry on through a live
/// member; before this it failed every poll until the bound and ended `TimedOut` although the run completed.
class UpgradeStatusPollTest {
    private static final String A = "http://10.0.0.1:8080";
    private static final String B = "http://10.0.0.2:8080";
    private static final String C = "http://10.0.0.3:8080";

    private static final String TOPOLOGY = """
            {"nodeDetails":[
              {"nodeId":"n1","health":"CONNECTED","address":"10.0.0.1:6000"},
              {"nodeId":"n2","health":"CONNECTED","address":"10.0.0.2:6000"},
              {"nodeId":"n3","health":"CONNECTED","address":"10.0.0.3:6000"},
              {"nodeId":"n4","health":"DISCOVERED","address":"10.0.0.4:6000"}]}
            """;

    private final AtomicReference<String> inForce = new AtomicReference<>(A);
    private final Set<String> alive = new HashSet<>(Set.of(A, B, C));
    private final List<String> notes = new ArrayList<>();

    private UpgradeStatusPoll poll(String topology) {
        return UpgradeStatusPoll.upgradeStatusPoll(A,
                                                   this::status,
                                                   () -> Result.success(topology),
                                                   inForce::set,
                                                   notes::add);
    }

    private Result<String> status() {
        return alive.contains(inForce.get())
               ? Result.success("{\"present\":true,\"state\":\"RUNNING\",\"from\":\"" + inForce.get() + "\"}")
               : Causes.cause("connection refused: " + inForce.get()).result();
    }

    @Test
    void poll_polledNodeReplacedMidRun_carriesOnThroughALiveMember() {
        var poll = poll(TOPOLOGY);

        assertThat(poll.get().isSuccess()).as("CONTROL: the first poll is answered by the endpoint it was given").isTrue();
        alive.remove(A);

        var after = poll.get();

        assertThat(after.isSuccess()).as("the replaced node's poll must continue elsewhere, got: " + after).isTrue();
        assertThat(inForce.get()).isEqualTo(B);
        assertThat(notes).singleElement().asString().contains(A).contains(B);
    }

    @Test
    void poll_afterFailingOver_staysOnTheNewEndpoint_andFollowsItOnward() {
        var poll = poll(TOPOLOGY);

        poll.get();
        alive.remove(A);
        poll.get();
        alive.remove(B);

        var after = poll.get();

        assertThat(after.isSuccess()).isTrue();
        assertThat(inForce.get()).isEqualTo(C);
    }

    @Test
    void poll_noMemberAnswers_returnsTheFailure_andLeavesTheEndpointInForceUnchanged() {
        var poll = poll(TOPOLOGY);

        poll.get();
        alive.clear();

        assertThat(poll.get().isFailure()).isTrue();
        assertThat(inForce.get()).as("a failed sweep must not leave the endpoint on the last candidate it tried").isEqualTo(A);
    }

    @Test
    void poll_beforeAnyAnswer_hasNoMembersToFailOverTo() {
        alive.remove(A);
        var poll = poll(TOPOLOGY);

        assertThat(poll.get().isFailure()).isTrue();
        assertThat(inForce.get()).isEqualTo(A);
    }

    @Test
    void liveMemberEndpoints_keepsOnlyLinkedMembers_underTheEndpointsSchemeAndPort() {
        assertThat(UpgradeStatusPoll.liveMemberEndpoints("https://10.0.0.1:5160", TOPOLOGY)).containsExactly("https://10.0.0.2:5160",
                                                                                                          "https://10.0.0.3:5160");
    }

    @Test
    void liveMemberEndpoints_unreadableTopology_isEmpty() {
        assertThat(UpgradeStatusPoll.liveMemberEndpoints(A, "not json")).isEmpty();
    }

    @Test
    void wait_whenThePolledNodeIsReplacedBeforeTheRunEnds_stillSeesCompleted() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var poll = UpgradeStatusPoll.upgradeStatusPoll(A,
                                                       () -> bodyFor(calls.incrementAndGet()),
                                                       () -> Result.success(TOPOLOGY),
                                                       inForce::set,
                                                       notes::add);

        var clock = new java.util.concurrent.atomic.AtomicLong();
        // a clock that advances on every read, so a poll that never completes ends TimedOut instead of looping
        var outcome = UpgradeRunWait.await(poll, () -> clock.addAndGet(1_000L), _ -> {}, 60_000L, 1L, _ -> {});

        assertThat(outcome).isInstanceOf(UpgradeRunWait.Completed.class);
    }

    /// Poll 1 answers RUNNING from A; A is then gone (every poll against it fails); the run's last word comes from B.
    private Result<String> bodyFor(int call) {
        if (call == 1) {
            return Result.success("{\"present\":true,\"state\":\"RUNNING\",\"targetVersion\":\"2\"}");
        }

        return A.equals(inForce.get())
               ? Causes.cause("connection refused").result()
               : Result.success("{\"present\":true,\"state\":\"COMPLETED\",\"targetVersion\":\"2\"}");
    }
}
