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

import java.util.HashMap;
import java.util.stream.Stream;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound1;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound2;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SystemTags;

import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.StateMachine.Batch.emptyBatch;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;
import static org.assertj.core.api.Assertions.assertThat;


/// #2625 wire layer: `Propose`, `VoteRound1` and `VoteRound2` carry the sender's process [Incarnation] under flag-day
/// tags 2980..2982 (old 31, 33 and 34 stay pinned under retired names, so they are never reused), `Incarnation` has 2983,
/// and each ballot round-trips the incarnation losslessly through the consensus codec.
class RabiaBallotIncarnationWireTest {
    private static final String PREFIX = "org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.";
    private static final NodeId NODE = NodeId.nodeId("incarnation-1").unwrap();
    private static final Incarnation INCARNATION = Incarnation.incarnation(0x5EED_CAFE_F00DL);
    private static final Phase PHASE = Phase.phase(41);

    private static final SliceCodec CODEC = SliceCodec.sliceCodec(frameworkCodecs(),
                                                                  Stream.concat(ConsensusCodecs.CODECS.stream(),
                                                                                RabiaCodecs.CODECS.stream())
                                                                        .toList());

    @Test
    void ballotsAndIncarnation_haveTheirFlagDayTags_andTheOldTagsStayRetired() {
        assertThat(SystemTags.tagFor(PREFIX + "Propose")).isEqualTo(2980);
        assertThat(SystemTags.tagFor(PREFIX + "VoteRound1")).isEqualTo(2981);
        assertThat(SystemTags.tagFor(PREFIX + "VoteRound2")).isEqualTo(2982);
        assertThat(SystemTags.tagFor("org.pragmatica.consensus.rabia.Incarnation")).isEqualTo(2983);
        assertThat(SystemTags.tagFor(PREFIX + "ProposeRetiredRc6")).isEqualTo(31);
        assertThat(SystemTags.tagFor(PREFIX + "VoteRound1RetiredRc6")).isEqualTo(33);
        assertThat(SystemTags.tagFor(PREFIX + "VoteRound2RetiredRc6")).isEqualTo(34);
    }

    @Test
    void ballotsAndIncarnation_areRegisteredInTheConsensusCodecUnderTheirPins() {
        var tagsByName = new HashMap<String, Integer>();

        CODEC.registeredTypes().forEach((type, codec) -> tagsByName.put(type.getCanonicalName(), codec.tag()));
        assertThat(tagsByName.get(PREFIX + "Propose")).isEqualTo(2980);
        assertThat(tagsByName.get(PREFIX + "VoteRound1")).isEqualTo(2981);
        assertThat(tagsByName.get(PREFIX + "VoteRound2")).isEqualTo(2982);
        assertThat(tagsByName.get("org.pragmatica.consensus.rabia.Incarnation")).isEqualTo(2983);
    }

    @Test
    void propose_roundTripsItsIncarnation() {
        var sent = new Propose<Command>(NODE, 7, PHASE, emptyBatch(), Option.none(), INCARNATION);
        Propose<Command> received = CODEC.decode(CODEC.encode(sent));

        assertThat(received.incarnation()).isEqualTo(INCARNATION);
        assertThat(received).isEqualTo(sent);
    }

    @Test
    void voteRound1_roundTripsItsIncarnation() {
        var sent = new VoteRound1(NODE, 7, PHASE, 2, StateValue.V1, INCARNATION);
        VoteRound1 received = CODEC.decode(CODEC.encode(sent));

        assertThat(received.incarnation()).isEqualTo(INCARNATION);
        assertThat(received).isEqualTo(sent);
    }

    @Test
    void voteRound2_roundTripsItsIncarnation() {
        var sent = new VoteRound2(NODE, 7, PHASE, 2, StateValue.V0, INCARNATION);
        VoteRound2 received = CODEC.decode(CODEC.encode(sent));

        assertThat(received.incarnation()).isEqualTo(INCARNATION);
        assertThat(received).isEqualTo(sent);
    }

    /// The test constructors stamp the placeholder the engine also passes until the acceptance lane wires the real one.
    @Test
    void convenienceConstructors_stampUnstamped() {
        assertThat(new Propose<Command>(NODE, PHASE, emptyBatch()).incarnation()).isEqualTo(Incarnation.UNSTAMPED);
        assertThat(new VoteRound1(NODE, PHASE, StateValue.V0).incarnation()).isEqualTo(Incarnation.UNSTAMPED);
        assertThat(new VoteRound2(NODE, PHASE, StateValue.V0).incarnation()).isEqualTo(Incarnation.UNSTAMPED);
    }
}
