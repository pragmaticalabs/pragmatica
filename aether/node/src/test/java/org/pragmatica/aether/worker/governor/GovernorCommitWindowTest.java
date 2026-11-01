// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// #2542, the batch rule: one window's announcements are one submission, bounded in time and in size.
class GovernorCommitWindowTest {
    private static final NodeId A = new NodeId("worker-a");
    private static final NodeId B = new NodeId("worker-b");
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan SHORT = TimeSpan.timeSpan(30).millis();

    private final List<List<KVCommand<AetherKey>>> submissions = new CopyOnWriteArrayList<>();

    private GovernorCommitWindow window(TimeSpan window, int cap) {
        return new GovernorCommitWindow(commands -> {
            submissions.add(new ArrayList<>(commands));
            return Promise.success(commands.stream().map(c -> (Object) c).toList());
        }, window, cap);
    }

    private static AetherKey key(String community) {
        return AetherKey.GovernorAnnouncementKey.forCommunity(community);
    }

    private static KVCommand<AetherKey> command(AetherKey key, String id) {
        return new KVCommand.Remove<>(key);
    }

    @Test
    void enqueue_fullWindow_submitsOnceAtTheCap() {
        var window = window(LONG, 3);

        window.enqueue(key("c1"), A, id -> command(key("c1"), id));
        window.enqueue(key("c2"), A, id -> command(key("c2"), id));
        assertThat(submissions).as("below the cap and inside the window: nothing sent yet").isEmpty();
        window.enqueue(key("c3"), A, id -> command(key("c3"), id));

        assertThat(submissions).hasSize(1);
        assertThat(submissions.getFirst()).hasSize(3);
    }

    @Test
    void enqueue_windowElapses_submitsTheGatheredCommandsAsOne() {
        var window = window(SHORT, 16);

        var first = window.enqueue(key("c1"), A, id -> command(key("c1"), id));
        var second = window.enqueue(key("c2"), B, id -> command(key("c2"), id));
        first.outcomes().await(TimeSpan.timeSpan(5).seconds());

        assertThat(second.outcomes()).as("both tickets wait on the same submission").isSameAs(first.outcomes());
        assertThat(submissions).hasSize(1);
        assertThat(submissions.getFirst()).hasSize(2);
    }

    @Test
    void enqueue_sameKeySameNominee_collapsesToOneCommandUnderOneId() {
        var window = window(LONG, 2);

        var first = window.enqueue(key("c1"), A, id -> command(key("c1"), id));
        var second = window.enqueue(key("c1"), A, id -> command(key("c1"), id));

        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(submissions).as("one key held, cap of 2 not reached").isEmpty();
        window.enqueue(key("c2"), A, id -> command(key("c2"), id));
        assertThat(submissions.getFirst()).as("the repeated write was dropped").hasSize(2);
    }

    @Test
    void enqueue_sameKeyOtherNominee_submitsTheEarlierClaimFirst() {
        var window = window(LONG, 16);

        var earlier = window.enqueue(key("c1"), A, id -> command(key("c1"), id));
        var competing = window.enqueue(key("c1"), B, id -> command(key("c1"), id));

        assertThat(submissions).as("the earlier claim went out when the competing one arrived").hasSize(1);
        assertThat(earlier.outcomes()).isNotSameAs(competing.outcomes());
        assertThat(competing.transactionId()).isNotEqualTo(earlier.transactionId());
    }

    @Test
    void enqueue_capOfOne_submitsEachOnItsOwn() {
        var window = window(LONG, 1);

        window.enqueue(key("c1"), A, id -> command(key("c1"), id));
        window.enqueue(key("c2"), A, id -> command(key("c2"), id));

        assertThat(submissions).hasSize(2);
        assertThat(submissions).allSatisfy(batch -> assertThat(batch).hasSize(1));
    }

    @Test
    void enqueue_afterAnEarlySubmission_theStaleTimerDoesNotCutTheNextWindowShort() throws InterruptedException {
        var window = window(TimeSpan.timeSpan(400).millis(), 2);

        window.enqueue(key("c1"), A, id -> command(key("c1"), id));
        window.enqueue(key("c2"), A, id -> command(key("c2"), id));
        assertThat(submissions).hasSize(1);
        Thread.sleep(250);
        var third = window.enqueue(key("c3"), A, id -> command(key("c3"), id));
        Thread.sleep(250);

        assertThat(submissions).as("the first window's timer (400 ms) has fired, the third command's (650 ms) has not").hasSize(1);
        third.outcomes().await(TimeSpan.timeSpan(5).seconds());
        assertThat(submissions).hasSize(2);
    }
}
