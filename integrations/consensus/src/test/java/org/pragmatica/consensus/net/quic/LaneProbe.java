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

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.ByteBuf;

/// #1578 test message: a marker addressed to ONE lane, so a test can prove which lanes carry traffic.
/// The receiver cannot see the lane a frame arrived on, so the marker names it.
record LaneProbe(NodeId sender, int laneIndex, String marker) implements ProtocolMessage {
    static final SliceCodec.TypeCodec<LaneProbe> CODEC =
        new SliceCodec.TypeCodec<>(LaneProbe.class,
                                   SliceCodec.deterministicTag(LaneProbe.class.getName()),
                                   LaneProbe::writeBody,
                                   LaneProbe::readBody);

    static LaneProbe laneProbe(NodeId sender, StreamType lane, String marker) {
        return new LaneProbe(sender, lane.streamIndex(), marker);
    }

    @Override
    public StreamType streamType() {
        return StreamType.fromIndex(laneIndex).unwrap();
    }

    /// Framework, consensus and net codecs plus the probe's own.
    static SliceCodec codec() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();

        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);
        all.add(CODEC);

        return SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), List.copyOf(all));
    }

    private static void writeBody(SliceCodec codec, ByteBuf buf, LaneProbe probe) {
        codec.write(buf, probe.sender().id());
        codec.write(buf, probe.laneIndex());
        codec.write(buf, probe.marker());
    }

    private static LaneProbe readBody(SliceCodec codec, ByteBuf buf) {
        String sender = codec.read(buf);
        Integer laneIndex = codec.read(buf);
        String marker = codec.read(buf);

        return new LaneProbe(new NodeId(sender), laneIndex, marker);
    }
}
