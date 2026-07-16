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

import java.util.Map;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;


/// Pull-channel view of SWIM health at a point in time.
///
/// Per the membership-architecture spec (§4.2): exposes the current
/// per-peer [`SwimHealth`] for all peers SWIM is currently aware of.
/// Returned by [`SwimProtocol#currentHealth`].
///
/// Immutable snapshot — modifications to SWIM state after construction
/// are not reflected in this view.
public record HealthSnapshot(Map<NodeId, SwimHealth> peerHealth) {
    public HealthSnapshot {
        peerHealth = peerHealth == null
                     ? Map.of()
                     : Map.copyOf(peerHealth);
    }

    public static HealthSnapshot healthSnapshot(Map<NodeId, SwimHealth> peerHealth) {
        return new HealthSnapshot(peerHealth);
    }

    public Option<SwimHealth> healthOf(NodeId peer) {
        return option(peerHealth.get(peer));
    }
}
