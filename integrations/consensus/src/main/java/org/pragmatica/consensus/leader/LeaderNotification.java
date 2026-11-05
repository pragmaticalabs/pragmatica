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

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.Message;


public sealed interface LeaderNotification extends Message.Local {
    /// `replayed` (KVStore dispatcher design §3.5 R3): the change was raised while the node store was delivering a replay
    /// burst, captured where the change is raised. A receiver behind a react executor runs where `isReplaying()` always
    /// reads false, so an announcing receiver reads this bit instead (v-2653 F3).
    record LeaderChange(Option<NodeId> leaderId, boolean localNodeIsLeader, boolean replayed) implements LeaderNotification {
        /// A change raised outside any replay burst.
        public LeaderChange(Option<NodeId> leaderId, boolean localNodeIsLeader) {
            this(leaderId, localNodeIsLeader, false);
        }
    }

    static LeaderChange leaderChange(Option<NodeId> leaderId, boolean localNodeIsLeader) {
        return new LeaderChange(leaderId, localNodeIsLeader);
    }

    static LeaderChange leaderChange(Option<NodeId> leaderId, boolean localNodeIsLeader, boolean replayed) {
        return new LeaderChange(leaderId, localNodeIsLeader, replayed);
    }
}
