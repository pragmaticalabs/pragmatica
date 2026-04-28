// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker;

import org.pragmatica.aether.invoke.InvokeCodecs;
import org.pragmatica.aether.slice.SliceCodecs;
import org.pragmatica.aether.slice.blueprint.BlueprintCodecs;
import org.pragmatica.aether.worker.bootstrap.BootstrapCodecs;
import org.pragmatica.aether.worker.heartbeat.HeartbeatCodecs;
import org.pragmatica.aether.worker.mutation.MutationCodecs;
import org.pragmatica.cluster.metrics.MetricsCodecs;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.rabia.RabiaCodecs;
import org.pragmatica.net.tcp.TcpCodecs;
import org.pragmatica.serialization.CodecFor;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SliceCodec.TypeCodec;
import org.pragmatica.swim.SwimCodecs;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Set;

import static org.pragmatica.serialization.SliceCodec.deterministicTag;
import static org.pragmatica.serialization.SliceCodec.readCompact;
import static org.pragmatica.serialization.SliceCodec.readString;
import static org.pragmatica.serialization.SliceCodec.writeCompact;
import static org.pragmatica.serialization.SliceCodec.writeString;


@CodecFor(InetSocketAddress.class) @SuppressWarnings("JBCT-STY-03") public sealed interface WorkerCodecs {
    record unused() implements WorkerCodecs{}

    static SliceCodec workerCodecs(SliceCodec parent) {
        var all = new ArrayList<TypeCodec<?>>();
        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(RabiaCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);
        all.addAll(TcpCodecs.CODECS);
        all.addAll(org.pragmatica.cluster.state.kvstore.KvstoreCodecs.CODECS);
        all.addAll(MetricsCodecs.CODECS);
        all.addAll(SliceCodecs.CODECS);
        all.addAll(org.pragmatica.aether.slice.kvstore.KvstoreCodecs.CODECS);
        all.addAll(BlueprintCodecs.CODECS);
        all.addAll(InvokeCodecs.CODECS);
        all.addAll(MutationCodecs.CODECS);
        all.addAll(BootstrapCodecs.CODECS);
        all.addAll(HeartbeatCodecs.CODECS);
        all.addAll(org.pragmatica.aether.worker.metrics.MetricsCodecs.CODECS);
        all.addAll(org.pragmatica.aether.worker.network.NetworkCodecs.CODECS);
        all.addAll(org.pragmatica.dht.DhtCodecs.CODECS);
        all.addAll(SwimCodecs.CODECS);
        all.add(inetSocketAddressCodec());
        var requiredTypes = collectRequiredTypes();
        return SliceCodec.sliceCodec(parent, all, requiredTypes);
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
}
