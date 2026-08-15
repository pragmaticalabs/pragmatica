// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Set;

import org.pragmatica.aether.invoke.InvokeCodecsInvoke;
import org.pragmatica.aether.slice.SliceCodecsInvoke;
import org.pragmatica.aether.slice.SliceCodecsNode;
import org.pragmatica.aether.slice.SliceCodecsSlice;
import org.pragmatica.aether.slice.SliceCodecsSliceApi;
import org.pragmatica.aether.slice.blueprint.BlueprintCodecsSlice;
import org.pragmatica.aether.worker.bootstrap.BootstrapCodecsNode;
import org.pragmatica.aether.worker.heartbeat.HeartbeatCodecsNode;
import org.pragmatica.aether.worker.mutation.MutationCodecsNode;
import org.pragmatica.cluster.metrics.MetricsCodecs;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.rabia.RabiaCodecs;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TcpCodecs;
import org.pragmatica.serialization.CodecFor;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SliceCodec.TypeCodec;
import org.pragmatica.swim.SwimCodecs;

import static org.pragmatica.serialization.SliceCodec.deterministicTag;
import static org.pragmatica.serialization.SliceCodec.readCompact;
import static org.pragmatica.serialization.SliceCodec.readString;
import static org.pragmatica.serialization.SliceCodec.writeCompact;
import static org.pragmatica.serialization.SliceCodec.writeString;


@CodecFor({InetSocketAddress.class, TimeSpan.class})
@SuppressWarnings("JBCT-STY-03")
public sealed interface WorkerCodecs {
    record unused() implements WorkerCodecs {}

    static SliceCodec workerCodecs(SliceCodec parent) {
        var all = new ArrayList<TypeCodec<?>>();

        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(RabiaCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);
        all.addAll(TcpCodecs.CODECS);
        all.addAll(org.pragmatica.cluster.state.kvstore.KvstoreCodecs.CODECS);
        all.addAll(MetricsCodecs.CODECS);
        // SliceCodecs registry in org.pragmatica.aether.slice is contributed by four modules; reference each suffixed sub-registry to avoid shade collision.
        all.addAll(SliceCodecsSlice.CODECS);
        all.addAll(SliceCodecsSliceApi.CODECS);
        all.addAll(SliceCodecsNode.CODECS);
        all.addAll(SliceCodecsInvoke.CODECS);
        all.addAll(org.pragmatica.aether.slice.kvstore.KvstoreCodecsSlice.CODECS);
        all.addAll(org.pragmatica.aether.api.ApiCodecsNode.CODECS);
        all.addAll(BlueprintCodecsSlice.CODECS);
        all.addAll(InvokeCodecsInvoke.CODECS);
        all.addAll(MutationCodecsNode.CODECS);
        all.addAll(BootstrapCodecsNode.CODECS);
        all.addAll(HeartbeatCodecsNode.CODECS);
        all.addAll(org.pragmatica.aether.worker.metrics.MetricsCodecs.CODECS);
        all.addAll(org.pragmatica.aether.worker.network.NetworkCodecsNode.CODECS);
        all.addAll(org.pragmatica.dht.DhtCodecs.CODECS);
        all.addAll(SwimCodecs.CODECS);
        all.add(inetSocketAddressCodec());
        all.add(timeSpanCodec());
        var requiredTypes = collectRequiredTypes();

        return SliceCodec.systemCodec(parent, all, requiredTypes);
    }

    private static Set<Class<?>> collectRequiredTypes() {
        var types = new java.util.HashSet<Class<?>>();

        types.addAll(SwimCodecs.REQUIRED_TYPES);
        types.add(InetSocketAddress.class);

        return types;
    }

    private static TypeCodec<InetSocketAddress> inetSocketAddressCodec() {
        return new TypeCodec<>(InetSocketAddress.class,
                               deterministicTag("java.net.InetSocketAddress"),
                               (codec, buf, val) -> {
                                   writeString(buf, val.getHostString());
                                   writeCompact(buf, val.getPort());
                               },
                               (codec, buf) -> InetSocketAddress.createUnresolved(readString(buf), readCompact(buf)));
    }

    /// `SwimConfig` carries `TimeSpan` fields, so `SwimCodecs.REQUIRED_TYPES` demands a codec for it and
    /// this registry never supplied one — `workerCodecs` threw on every call. Nothing calls it in
    /// production, which is exactly why it went unnoticed; the pinning test builds both registries and
    /// found it. Byte-identical to `NodeCodecs#timeSpanCodec`: the two must agree, because a worker and
    /// a core exchange these values.
    private static TypeCodec<TimeSpan> timeSpanCodec() {
        return new TypeCodec<>(TimeSpan.class,
                               deterministicTag("org.pragmatica.lang.io.TimeSpan"),
                               (codec, buf, val) -> buf.writeLong(val.nanos()),
                               (codec, buf) -> TimeSpan.timeSpan(buf.readLong()).nanos());
    }
}
