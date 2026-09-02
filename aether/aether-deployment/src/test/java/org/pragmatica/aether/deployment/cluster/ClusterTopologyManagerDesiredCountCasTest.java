// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// The fenced desired-count write loop (RFC-0018, #570) — `ClusterTopologyManagerRecord.applyDesiredCount`.
///
/// The applier's successor fence rejects a write built on a stale read, but the rejection is
/// invisible in the apply result (batch merging hands every submitter the full merged result list),
/// so the loop confirms SEMANTICALLY: after the apply resolves it re-reads and checks the count it
/// asked for landed, retrying from the fresh committed value when it did not. These tests script the
/// reader/applier pair to simulate winning, losing once, and losing every time — the last is the
/// mutation check's target: without the confirm-and-retry, "loses every time" would report success.
class ClusterTopologyManagerDesiredCountCasTest {

    private static ClusterConfigValue base() {
        return ClusterConfigValue.clusterConfigValue("toml",
                                                     "prod",
                                                     "1.0.0",
                                                     List.of(new TopologyEntry("eu", "core", 3)),
                                                     3,
                                                     9,
                                                     "hetzner",
                                                     1);
    }

    private static ClusterConfigValue putValue(List<KVCommand<AetherKey>> commands) {
        return commands.getFirst() instanceof KVCommand.Put<?, ?> put && put.value() instanceof ClusterConfigValue value
               ? value
               : null;
    }

    /// Store stand-in: the reader sees exactly what the scripted applier committed.
    private record Harness(AtomicReference<ClusterConfigValue> committed, AtomicInteger applies) {
        static Harness harness(ClusterConfigValue initial) {
            return new Harness(new AtomicReference<>(initial), new AtomicInteger());
        }

        Supplier<Option<ClusterConfigValue>> reader() {
            return () -> Option.option(committed.get());
        }

        /// Every apply COMMITS the submitted value — the fence never rejects. The happy path.
        Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> alwaysWins() {
            return commands -> {
                applies.incrementAndGet();
                committed.set(putValue(commands));

                return Promise.success(List.of());
            };
        }

        /// The first apply is LOST to a competitor (the fence rejected our write; a concurrent
        /// writer's landed instead), later applies win. `competitor` is what the store holds after
        /// the lost round — critically, WITHOUT our change.
        Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> losesFirstTo(ClusterConfigValue competitor) {
            return commands -> {
                if (applies.incrementAndGet() == 1) {
                    committed.set(competitor);
                } else {
                    committed.set(putValue(commands));
                }

                return Promise.success(List.of());
            };
        }

        /// Every apply is lost — a concurrent writer keeps advancing the config and our write never
        /// lands. The committed value advances each round (fresh version), so each retry recomputes
        /// against a NEW base and legitimately loses again.
        Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> alwaysLoses() {
            return commands -> {
                applies.incrementAndGet();
                committed.set(committed.get().withDesiredCount("us", "worker", applies.get()));

                return Promise.success(List.of());
            };
        }
    }

    @Test
    void applyDesiredCount_winsFirstTry_singleApply() {
        var harness = Harness.harness(base());

        var result = ClusterTopologyManagerRecord.applyDesiredCount(harness.reader(),
                                                                    harness.alwaysWins(),
                                                                    "eu",
                                                                    "core",
                                                                    5,
                                                                    ClusterTopologyManagerRecord.DESIRED_COUNT_CAS_ATTEMPTS)
                                                 .await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(harness.applies().get()).isEqualTo(1);
        assertThat(harness.committed().get().desiredCountFor("eu", "core")).isEqualTo(5);
    }

    /// The property stage 2 created the need for: two INDEPENDENT edits — our `eu/core=5` racing a
    /// competitor's `us/worker=9` — must BOTH survive. Pre-fence, the loser was silently discarded;
    /// with the fence + retry, the loser recomputes on top of the winner and lands.
    @Test
    void applyDesiredCount_losesOnce_retriesFromFreshValue_bothWritesSurvive() {
        var competitor = base().withDesiredCount("us", "worker", 9);
        var harness = Harness.harness(base());

        var result = ClusterTopologyManagerRecord.applyDesiredCount(harness.reader(),
                                                                    harness.losesFirstTo(competitor),
                                                                    "eu",
                                                                    "core",
                                                                    5,
                                                                    ClusterTopologyManagerRecord.DESIRED_COUNT_CAS_ATTEMPTS)
                                                 .await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(harness.applies().get()).isEqualTo(2);

        var settled = harness.committed().get();
        assertThat(settled.desiredCountFor("eu", "core")).as("our edit must land on retry").isEqualTo(5);
        assertThat(settled.desiredCountFor("us", "worker")).as("the competitor's edit must survive too").isEqualTo(9);
    }

    /// Exhaustion is a FAILURE, not a silent success — the whole point of confirming. A loop that
    /// reported success here would be worse than the race it replaces.
    @Test
    void applyDesiredCount_losesEveryTime_failsAfterBoundedAttempts() {
        var harness = Harness.harness(base());

        var result = ClusterTopologyManagerRecord.applyDesiredCount(harness.reader(),
                                                                    harness.alwaysLoses(),
                                                                    "eu",
                                                                    "core",
                                                                    5,
                                                                    ClusterTopologyManagerRecord.DESIRED_COUNT_CAS_ATTEMPTS)
                                                 .await();

        assertThat(result.isFailure()).isTrue();
        assertThat(harness.applies().get()).isEqualTo(ClusterTopologyManagerRecord.DESIRED_COUNT_CAS_ATTEMPTS);
        result.onFailure(cause -> assertThat(cause.message()).contains("lost the version race"));
    }

    @Test
    void applyDesiredCount_missingConfig_failsImmediately_noApply() {
        var harness = Harness.harness(null);

        var result = ClusterTopologyManagerRecord.applyDesiredCount(harness.reader(),
                                                                    harness.alwaysWins(),
                                                                    "eu",
                                                                    "core",
                                                                    5,
                                                                    ClusterTopologyManagerRecord.DESIRED_COUNT_CAS_ATTEMPTS)
                                                 .await();

        assertThat(result.isFailure()).isTrue();
        assertThat(harness.applies().get()).isZero();
    }
}
