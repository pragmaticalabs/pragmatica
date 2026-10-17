// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;


/// Missing observations have a bounded grace at leadership acquisition, not permanent presence.
public interface CommunityReachability extends CommunityLivenessView {
    Unit beginLeadership();

    static CommunityReachability communityReachability(TimeSpan absence,
                                                       Function<NodeId, Option<TimeSpan>> observationAge,
                                                       TimeSource clock) {
        record reachability(TimeSpan absence,
                            Function<NodeId, Option<TimeSpan>> observationAge,
                            TimeSource clock,
                            AtomicLong acquiredAt) implements CommunityReachability {
            @Override
            public Unit beginLeadership() {
                acquiredAt.set(clock.nanoTime());

                return Unit.unit();
            }

            @Override
            public boolean isAbsent(NodeId node) {
                return observationAge.apply(node)
                                     .or(this::leadershipAge)
                                     .compareTo(absence) >= 0;
            }

            private TimeSpan leadershipAge() {
                return TimeSpan.timeSpan(clock.nanoTime() - acquiredAt.get()).nanos();
            }
        }

        return new reachability(absence, observationAge, clock, new AtomicLong(clock.nanoTime()));
    }
}
