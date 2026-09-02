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
import org.pragmatica.lang.Contract;


/// Listener for SWIM membership change events.
@Contract
public interface SwimMembershipListener {
    /// Called when a new member joins or a previously faulty member recovers.
    void onMemberJoined(SwimMember member);
    /// Called when a member is suspected of being unreachable.
    void onMemberSuspect(SwimMember member);
    /// Called when a suspected member is confirmed faulty.
    ///
    /// `firstHand` distinguishes verdict provenance (P1 death-path co-confirmation):
    /// `true`  — this node's OWN probe cycle timed out for the member
    ///           ([`SwimProtocol#transitionToFaulty`]). Direct local evidence.
    /// `false` — the FAULTY verdict was RECEIVED via gossip dissemination from another
    ///           node and applied to local membership. Second-hand. A second-hand
    ///           verdict reaches this callback ONLY when it was locally corroborated
    ///           (transport-observed peer-down); an uncorroborated second-hand FAULTY is
    ///           downgraded to SUSPECT inside the protocol and never surfaces here.
    void onMemberFaulty(SwimMember member, boolean firstHand);
    /// Called when a member is removed from the membership list.
    void onMemberLeft(NodeId nodeId);
}
