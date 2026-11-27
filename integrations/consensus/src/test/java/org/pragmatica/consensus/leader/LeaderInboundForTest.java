/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.leader;

import org.pragmatica.consensus.leader.fsm.LeaderElectionContext;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/// Test seam (#3846 round 2): the [LeaderElectionContext.Inbound] receiver for tests that stand in for the inbound
/// boundary (`LeaderManager`) over a context they built themselves. The context grants its one Inbound to the first
/// `claimInbound()`; this seam makes that claim once per context and returns the same receiver to every later call, so a
/// test and the `FsmBackedLeaderManager` it builds share it. A context built by `LeaderElectionFsm.leaderElectionFsm` was
/// already claimed by its builder: use `FsmWithContext.inbound()` for it, never this seam.
public final class LeaderInboundForTest {
    private static final Map<LeaderElectionContext, LeaderElectionContext.Inbound> CLAIMED =
        Collections.synchronizedMap(new WeakHashMap<>());

    private LeaderInboundForTest() {}

    public static LeaderElectionContext.Inbound inbound(LeaderElectionContext ctx) {
        return CLAIMED.computeIfAbsent(ctx, LeaderElectionContext::claimInbound);
    }
}
