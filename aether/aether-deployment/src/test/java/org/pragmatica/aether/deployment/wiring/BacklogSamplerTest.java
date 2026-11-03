// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// N-5 (phase-3 design 3.1): the backlog WARN depth sampler. `deployment-fsm-backlog` is raised above a depth of 10,000 and resolved below 1,000.
class BacklogSamplerTest {
    private final ManualTimerService clock = new ManualTimerService();
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger node = new AtomicInteger();
    private final AtomicInteger cluster = new AtomicInteger();
    private final BacklogSampler.Alarm alarm = new BacklogSampler.Alarm() {
        @Override
        public void raised(String fsm, int depth) {
            events.add("raised " + fsm + " " + depth);
        }

        @Override
        public void cleared(String fsm, int depth) {
            events.add("cleared " + fsm + " " + depth);
        }
    };
    private final BacklogSampler sampler = BacklogSampler.backlogSampler(List.of(new BacklogSampler.Watched("node", node::get),
                                                                                 new BacklogSampler.Watched("cluster", cluster::get)),
                                                                         alarm,
                                                                         clock);

    @Test
    void theThresholds_areTheDesignsTenThousandAndOneThousand_andTheCadenceFiveSeconds() {
        assertThat(BacklogSampler.RAISE_ABOVE).isEqualTo(10_000);
        assertThat(BacklogSampler.CLEAR_BELOW).isEqualTo(1_000);
        assertThat(BacklogSampler.INTERVAL_MS).isEqualTo(5_000L);
    }

    @Test
    void aDepthAtTheRaiseThreshold_raisesNothing_onePastItRaises() {
        node.set(10_000);
        sampler.poll();

        assertThat(events).as("above, not at").isEmpty();

        node.set(10_001);
        sampler.poll();

        assertThat(events).containsExactly("raised node 10001");
    }

    @Test
    void aRaisedAlarm_isNotRaisedAgain_whileTheDepthStaysAbove() {
        node.set(20_000);
        sampler.poll();
        node.set(15_000);
        sampler.poll();
        sampler.poll();

        assertThat(events).containsExactly("raised node 20000");
    }

    @Test
    void theAlarm_staysRaisedBetweenTheThresholds_andClearsBelowTheLower() {
        node.set(12_000);
        sampler.poll();
        node.set(5_000);
        sampler.poll();
        node.set(1_000);
        sampler.poll();

        assertThat(events).as("hysteresis: 5,000 and 1,000 do not clear").containsExactly("raised node 12000");

        node.set(999);
        sampler.poll();

        assertThat(events).containsExactly("raised node 12000", "cleared node 999");
    }

    @Test
    void aClearedAlarm_isNotClearedAgain_andARiseAfterItRaisesAgain() {
        node.set(12_000);
        sampler.poll();
        node.set(0);
        sampler.poll();
        sampler.poll();
        node.set(11_000);
        sampler.poll();

        assertThat(events).containsExactly("raised node 12000", "cleared node 0", "raised node 11000");
    }

    @Test
    void aDepthBetweenTheThresholds_neverRaises() {
        node.set(5_000);
        sampler.poll();

        assertThat(events).isEmpty();
    }

    @Test
    void eachFsmHasItsOwnAlarm() {
        node.set(12_000);
        cluster.set(100);
        sampler.poll();
        cluster.set(30_000);
        node.set(0);
        sampler.poll();

        assertThat(events).containsExactly("raised node 12000", "cleared node 0", "raised cluster 30000");
    }

    @Test
    void start_samplesAtOnce_thenEveryInterval_untilStopped() {
        node.set(20_000);
        sampler.start();

        assertThat(events).as("the first sample is at start").containsExactly("raised node 20000");

        node.set(0);
        clock.advance(BacklogSampler.INTERVAL_MS - 1);

        assertThat(events).as("not before the interval").hasSize(1);

        clock.advance(1);

        assertThat(events).containsExactly("raised node 20000", "cleared node 0");

        sampler.stop();
        assertThat(clock.armedCount()).as("a stopped sampler holds no timer").isZero();

        node.set(30_000);
        clock.advance(BacklogSampler.INTERVAL_MS * 3);

        assertThat(events).as("a stopped sampler says nothing").hasSize(2);
    }

    @Test
    void start_samplesAgainAfterEveryInterval() {
        var samples = new AtomicInteger();
        var counting = BacklogSampler.backlogSampler(List.of(BacklogSampler.Watched.watched("node", samples::incrementAndGet)), alarm, clock);

        counting.start();
        clock.advance(BacklogSampler.INTERVAL_MS);
        clock.advance(BacklogSampler.INTERVAL_MS);
        clock.advance(BacklogSampler.INTERVAL_MS);

        assertThat(samples).as("one at start, one per interval").hasValue(4);
    }

    /// A tick already running when `stop` is called cannot be recalled by the timer: it must find the sampler stopped, sample nothing, and arm nothing.
    @Test
    void aTickThatEscapedTheCancellation_findsTheSamplerStopped_andDoesNothing() {
        var armed = new ArrayList<Runnable>();
        var samples = new AtomicInteger();
        var uncancellable = BacklogSampler.backlogSampler(List.of(BacklogSampler.Watched.watched("node", samples::incrementAndGet)),
                                                          alarm,
                                                          (body, delayMs) -> {
                                                              armed.add(body);

                                                              return () -> {};
                                                          });

        uncancellable.start();
        uncancellable.stop();
        armed.getFirst().run();

        assertThat(samples).as("only the sample taken at start").hasValue(1);
        assertThat(armed).as("and no further tick armed").hasSize(1);
    }

    @Test
    void stop_beforeStart_isHarmless() {
        sampler.stop();

        assertThat(clock.armedCount()).isZero();
    }
}
