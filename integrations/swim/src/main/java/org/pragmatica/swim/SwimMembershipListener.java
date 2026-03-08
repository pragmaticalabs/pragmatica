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

package org.pragmatica.swim;

import org.pragmatica.consensus.NodeId;

/// Listener for SWIM membership change events.
public interface SwimMembershipListener {

    /// Called when a new member joins or a previously faulty member recovers.
    void onMemberJoined(SwimMember member);

    /// Called when a member is suspected of being unreachable.
    void onMemberSuspect(SwimMember member);

    /// Called when a suspected member is confirmed faulty.
    void onMemberFaulty(SwimMember member);

    /// Called when a member is removed from the membership list.
    void onMemberLeft(NodeId nodeId);
}
