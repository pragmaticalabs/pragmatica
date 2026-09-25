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
import org.pragmatica.lang.Option;
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
        /// One immutable proposal for a log slot in a fixed voter epoch.
        record Propose<C extends Command>(NodeId sender,
                                          long epoch,
                                          Phase phase,
                                          Batch<C> value,
                                          Option<ClusterConfig> reconfiguration) implements Synchronous {
            public Propose(NodeId sender, Phase phase, Batch<C> value) {
                this(sender, 0, phase, value, Option.none());
            }

            public Propose(NodeId sender, long epoch, Phase phase, Batch<C> value) {
                this(sender, epoch, phase, value, Option.none());
            }
        }

        record VoteRound1(NodeId sender, long epoch, Phase phase, long round, StateValue stateValue) implements Synchronous {
            public VoteRound1(NodeId sender, Phase phase, StateValue stateValue) {
                this(sender, 0, phase, 0, stateValue);
            }

            public VoteRound1(NodeId sender, Phase phase, long round, StateValue stateValue) {
                this(sender, 0, phase, round, stateValue);
            }
        }

        record VoteRound2(NodeId sender, long epoch, Phase phase, long round, StateValue stateValue) implements Synchronous {
            public VoteRound2(NodeId sender, Phase phase, StateValue stateValue) {
                this(sender, 0, phase, 0, stateValue);
            }

            public VoteRound2(NodeId sender, Phase phase, long round, StateValue stateValue) {
                this(sender, 0, phase, round, stateValue);
            }
        }

        record Decision<C extends Command>(NodeId sender,
                                           long epoch,
                                           Phase phase,
                                           StateValue stateValue,
                                           Batch<C> value,
                                           Option<ClusterConfig> reconfiguration) implements Synchronous {
            public Decision(NodeId sender, Phase phase, StateValue stateValue, Batch<C> value) {
                this(sender, 0, phase, stateValue, value, Option.none());
            }

            public Decision(NodeId sender, long epoch, Phase phase, StateValue stateValue, Batch<C> value) {
                this(sender, epoch, phase, stateValue, value, Option.none());
            }
        }

        /// State synchronization response. Travels on the dedicated SYNC lane (not CONSENSUS) so
        /// it is not head-of-line-blocked by consensus round traffic during a joiner's catch-up.
        /// `responder` says whether `state` came from a live engine or a stopped/syncing one (#667);
        /// the adoption rule in `RabiaEngine` weighs the two differently.
        record SyncResponse<C extends Command>(NodeId sender, SavedState<C> state, ResponderState responder) implements Synchronous {
            @Override
            public StreamType streamType() {
                return StreamType.SYNC;
            }
        }
    }

    /// Asynchronous protocol messages (outside consensus rounds).
    sealed interface Asynchronous extends RabiaProtocolMessage {
        /// Requests retained ballots for one binary round of an unfinished slot.
        record RoundRequest(NodeId sender, long epoch, Phase phase, long round) implements Asynchronous {
            public RoundRequest(NodeId sender, Phase phase, long round) {
                this(sender, 0, phase, round);
            }
        }

        record ReconfigurationRequest(NodeId sender, long epoch, ClusterConfig target) implements Asynchronous {}

        record ConfigurationTransfer<C extends Command>(NodeId sender, ConfigurationHandoff<C> handoff) implements Asynchronous {
            @Override
            public StreamType streamType() {
                return StreamType.SYNC;
            }
        }

        record ConfigurationInstalled(NodeId sender,
                                      VoterConfiguration configuration,
                                      Phase nextSlot,
                                      boolean requestAcknowledgements) implements Asynchronous {
            public ConfigurationInstalled(NodeId sender, VoterConfiguration configuration, Phase nextSlot) {
                this(sender, configuration, nextSlot, false);
            }
        }

        /// State synchronization request. Travels on the dedicated SYNC lane (not CONSENSUS) so a
        /// far-behind joiner's SyncRequest retries do not flood the consensus round traffic.
        /// Explicit refusal: this responder cannot encode a bounded full-state reply.
        record SyncRejected(NodeId sender, long epoch) implements Asynchronous {}

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
