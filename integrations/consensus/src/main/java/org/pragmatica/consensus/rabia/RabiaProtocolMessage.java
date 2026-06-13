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

package org.pragmatica.consensus.rabia;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;

/// Message types for the Rabia consensus protocol.
@Codec
public sealed interface RabiaProtocolMessage extends ProtocolMessage {
    @Override
    default StreamType streamType() {
        return StreamType.CONSENSUS;
    }

    /// Synchronous protocol messages (part of the consensus rounds).
    sealed interface Synchronous extends RabiaProtocolMessage {
        /// Initial proposal from a node.
        record Propose<C extends Command>(NodeId sender, Phase phase, Batch<C> value)
        implements Synchronous {}

        /// Round 1 vote message.
        record VoteRound1(NodeId sender, Phase phase, StateValue stateValue)
        implements Synchronous {}

        /// Round 2 vote message.
        record VoteRound2(NodeId sender, Phase phase, StateValue stateValue)
        implements Synchronous {}

        /// Decision broadcast message.
        record Decision<C extends Command>(NodeId sender,
                                           Phase phase,
                                           StateValue stateValue,
                                           Batch<C> value)
        implements Synchronous {}

        /// State synchronization response. Travels on the dedicated SYNC lane (not CONSENSUS) so
        /// it is not head-of-line-blocked by consensus round traffic during a joiner's catch-up.
        record SyncResponse<C extends Command>(NodeId sender, SavedState<C> state) implements Synchronous {
            @Override
            public StreamType streamType() {
                return StreamType.SYNC;
            }
        }
    }

    /// Asynchronous protocol messages (outside consensus rounds).
    sealed interface Asynchronous extends RabiaProtocolMessage {
        /// State synchronization request. Travels on the dedicated SYNC lane (not CONSENSUS) so a
        /// far-behind joiner's SyncRequest retries do not flood the consensus round traffic.
        record SyncRequest(NodeId sender) implements Asynchronous {
            @Override
            public StreamType streamType() {
                return StreamType.SYNC;
            }
        }

        /// Distribute a new batch to all nodes.
        record NewBatch<C extends Command>(NodeId sender, Batch<C> batch) implements Asynchronous {}
    }
}
