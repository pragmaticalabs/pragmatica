// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Set;

import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.vo.Email;
import org.pragmatica.lang.vo.IsoDateTime;
import org.pragmatica.lang.vo.NonBlankString;
import org.pragmatica.lang.vo.Url;
import org.pragmatica.lang.vo.Uuid;
import org.pragmatica.serialization.CodecFor;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SliceCodec.TypeCodec;

import static org.pragmatica.serialization.SliceCodec.deterministicTag;
import static org.pragmatica.serialization.SliceCodec.readCompact;
import static org.pragmatica.serialization.SliceCodec.readString;
import static org.pragmatica.serialization.SliceCodec.writeCompact;
import static org.pragmatica.serialization.SliceCodec.writeString;


@CodecFor({InetSocketAddress.class, MethodName.class, TimeSpan.class, URI.class, UUID.class, OffsetDateTime.class, Email.class, Url.class, NonBlankString.class, Uuid.class, IsoDateTime.class})
public sealed interface NodeCodecs {
    record unused() implements NodeCodecs {}

    static SliceCodec nodeCodecs(SliceCodec parent) {
        var all = new ArrayList<TypeCodec<?>>();

        all.addAll(org.pragmatica.consensus.ConsensusCodecs.CODECS);
        all.addAll(org.pragmatica.consensus.rabia.RabiaCodecs.CODECS);
        all.addAll(org.pragmatica.consensus.net.NetCodecs.CODECS);
        all.addAll(org.pragmatica.net.tcp.TcpCodecs.CODECS);
        all.addAll(org.pragmatica.cluster.state.kvstore.KvstoreCodecs.CODECS);
        all.addAll(org.pragmatica.cluster.metrics.MetricsCodecs.CODECS);
        all.addAll(org.pragmatica.dht.DhtCodecs.CODECS);
        all.addAll(org.pragmatica.aether.artifact.ArtifactCodecsSlice.CODECS);
        // SliceCodecs registry in org.pragmatica.aether.slice is contributed by four modules; reference each suffixed sub-registry to avoid shade collision.
        all.addAll(org.pragmatica.aether.slice.SliceCodecsSlice.CODECS);
        all.addAll(org.pragmatica.aether.slice.SliceCodecsSliceApi.CODECS);
        all.addAll(org.pragmatica.aether.slice.SliceCodecsNode.CODECS);
        all.addAll(org.pragmatica.aether.slice.SliceCodecsInvoke.CODECS);
        all.addAll(org.pragmatica.aether.slice.kvstore.KvstoreCodecsSlice.CODECS);
        all.addAll(org.pragmatica.aether.api.ApiCodecsNode.CODECS);
        all.addAll(org.pragmatica.aether.slice.generation.GenerationCodecsSlice.CODECS);
        all.addAll(org.pragmatica.aether.slice.blueprint.BlueprintCodecsSlice.CODECS);
        all.addAll(org.pragmatica.aether.invoke.InvokeCodecsInvoke.CODECS);
        all.addAll(org.pragmatica.aether.http.forward.ForwardCodecsInvoke.CODECS);
        // aether-stream wire types (replication, read-forward, stream-consensus) — without these the
        // active replication / catch-up / forward sends throw "No codec registered" over the cluster network.
        all.addAll(org.pragmatica.aether.stream.consensus.ConsensusCodecsStream.CODECS);
        all.addAll(org.pragmatica.aether.stream.replication.ReplicationCodecsStream.CODECS);
        all.addAll(org.pragmatica.aether.stream.forward.ForwardCodecsStream.CODECS);
        all.addAll(org.pragmatica.aether.dht.DhtCodecsInvoke.CODECS);
        all.addAll(org.pragmatica.aether.http.handler.HandlerCodecs.CODECS);
        all.addAll(org.pragmatica.aether.http.handler.security.SecurityCodecs.CODECS);
        all.addAll(org.pragmatica.swim.SwimCodecs.CODECS);
        all.add(methodNameCodec());
        all.add(inetSocketAddressCodec());
        all.add(timeSpanCodec());
        all.add(emailCodec());
        all.add(urlCodec());
        all.add(nonBlankStringCodec());
        all.add(uuidCodec());
        all.add(isoDateTimeCodec());
        var requiredTypes = collectRequiredTypes();

        return SliceCodec.sliceCodec(parent, all, requiredTypes);
    }

    private static Set<Class<?>> collectRequiredTypes() {
        var types = new java.util.HashSet<Class<?>>();

        types.addAll(org.pragmatica.swim.SwimCodecs.REQUIRED_TYPES);
        types.add(InetSocketAddress.class);
        types.add(MethodName.class);
        types.add(TimeSpan.class);
        types.add(Email.class);
        types.add(Url.class);
        types.add(NonBlankString.class);
        types.add(Uuid.class);
        types.add(IsoDateTime.class);

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

    private static TypeCodec<TimeSpan> timeSpanCodec() {
        return new TypeCodec<>(TimeSpan.class,
                               deterministicTag("org.pragmatica.lang.io.TimeSpan"),
                               (codec, buf, val) -> buf.writeLong(val.nanos()),
                               (codec, buf) -> TimeSpan.timeSpan(buf.readLong()).nanos());
    }

    private static TypeCodec<Email> emailCodec() {
        return new TypeCodec<>(Email.class,
                               deterministicTag("org.pragmatica.lang.vo.Email"),
                               (codec, buf, val) -> {
                                   writeString(buf, val.localPart());
                                   writeString(buf, val.domain());
                               },
                               (codec, buf) -> new Email(readString(buf), readString(buf)));
    }

    private static TypeCodec<Url> urlCodec() {
        return new TypeCodec<>(Url.class,
                               deterministicTag("org.pragmatica.lang.vo.Url"),
                               (codec, buf, val) -> writeString(buf,
                                                                val.uri().toString()),
                               (codec, buf) -> new Url(URI.create(readString(buf))));
    }

    private static TypeCodec<NonBlankString> nonBlankStringCodec() {
        return new TypeCodec<>(NonBlankString.class,
                               deterministicTag("org.pragmatica.lang.vo.NonBlankString"),
                               (codec, buf, val) -> writeString(buf, val.value()),
                               (codec, buf) -> new NonBlankString(readString(buf)));
    }

    private static TypeCodec<Uuid> uuidCodec() {
        return new TypeCodec<>(Uuid.class,
                               deterministicTag("org.pragmatica.lang.vo.Uuid"),
                               (codec, buf, val) -> writeString(buf,
                                                                val.value().toString()),
                               (codec, buf) -> new Uuid(java.util.UUID.fromString(readString(buf))));
    }

    private static TypeCodec<IsoDateTime> isoDateTimeCodec() {
        return new TypeCodec<>(IsoDateTime.class,
                               deterministicTag("org.pragmatica.lang.vo.IsoDateTime"),
                               (codec, buf, val) -> writeString(buf, val.toString()),
                               (codec, buf) -> new IsoDateTime(OffsetDateTime.parse(readString(buf))));
    }

    private static TypeCodec<MethodName> methodNameCodec() {
        return new TypeCodec<>(MethodName.class,
                               deterministicTag("org.pragmatica.aether.slice.MethodName"),
                               (codec, buf, val) -> writeString(buf, val.name()),
                               (codec, buf) -> new MethodName(readString(buf)));
    }
}
