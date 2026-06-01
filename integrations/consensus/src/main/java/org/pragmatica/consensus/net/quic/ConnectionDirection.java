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

package org.pragmatica.consensus.net.quic;

import org.pragmatica.consensus.NodeId;

/// Determines QUIC connection direction based on NodeId ordering.
///
/// Lower NodeId always initiates (QUIC client role), higher NodeId
/// always accepts (QUIC server role). This eliminates duplicate
/// connection detection entirely — each pair has exactly one
/// connection with a deterministic initiator.
public sealed interface ConnectionDirection {

    /// Lower NodeId initiates the QUIC connection.
    /// Returns true if self should act as the QUIC client for this peer.
    static boolean shouldInitiate(NodeId self, NodeId peer) {
        return self.compareTo(peer) < 0;
    }

    record Unused() implements ConnectionDirection {}
}
