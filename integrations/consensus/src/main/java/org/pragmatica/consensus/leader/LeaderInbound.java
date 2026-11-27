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

/// #3846: the capability to enter the node's serial executor from OUTSIDE the leader FSM. `LeaderElectionContext#serially`,
/// `#submit` and `#stop` each take one, so only code that holds it can hand the executor a task or an event without an
/// episode identity: the inbound boundary ([LeaderManager]'s entry points, where an external input becomes the next event
/// of the node's order). The leader FSM (`leader.fsm`: states, pre-vote, the context itself) arms timers and promise
/// callbacks only through `serializedFor`, which carries the episode that armed them.
///
/// GUARANTEE: no code in `leader.fsm` can arm a task outside the episode fence. MECHANISM: the constructor is private and
/// the only factory is package-private to `leader`, so a type in `leader.fsm` cannot obtain an instance (a compile error,
/// not a convention). The type carries no state: holding it is the whole permission.
public final class LeaderInbound {
    private static final LeaderInbound INBOUND = new LeaderInbound();

    private LeaderInbound() {}

    static LeaderInbound leaderInbound() {
        return INBOUND;
    }
}
