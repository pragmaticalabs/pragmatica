package org.pragmatica.aether.node;

import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SliceCodec.TypeCodec;

import java.util.ArrayList;

import static org.pragmatica.serialization.SliceCodec.deterministicTag;
import static org.pragmatica.serialization.SliceCodec.readString;
import static org.pragmatica.serialization.SliceCodec.writeString;

/// Registry of all node-level types for serialization.
/// Collects generated codec registries from all modules and adds manual entries
/// for types that can't use the annotation processor (e.g. shared-package conflicts).
public sealed interface NodeCodecs {
    record unused() implements NodeCodecs {}

    static SliceCodec nodeCodecs(SliceCodec parent) {
        var all = new ArrayList<TypeCodec<?>>();
        // Generated registries (produced by @Codec annotation processor)
        all.addAll(org.pragmatica.consensus.ConsensusCodecs.CODECS);
        all.addAll(org.pragmatica.consensus.rabia.RabiaCodecs.CODECS);
        all.addAll(org.pragmatica.consensus.net.NetCodecs.CODECS);
        all.addAll(org.pragmatica.net.tcp.TcpCodecs.CODECS);
        all.addAll(org.pragmatica.cluster.state.kvstore.KvstoreCodecs.CODECS);
        all.addAll(org.pragmatica.cluster.metrics.MetricsCodecs.CODECS);
        all.addAll(org.pragmatica.dht.DhtCodecs.CODECS);
        all.addAll(org.pragmatica.aether.artifact.ArtifactCodecs.CODECS);
        all.addAll(org.pragmatica.aether.slice.SliceCodecs.CODECS);
        all.addAll(org.pragmatica.aether.slice.kvstore.KvstoreCodecs.CODECS);
        all.addAll(org.pragmatica.aether.slice.blueprint.BlueprintCodecs.CODECS);
        all.addAll(org.pragmatica.aether.invoke.InvokeCodecs.CODECS);
        all.addAll(org.pragmatica.aether.http.forward.ForwardCodecs.CODECS);
        all.addAll(org.pragmatica.aether.http.handler.HandlerCodecs.CODECS);
        all.addAll(org.pragmatica.aether.http.handler.security.SecurityCodecs.CODECS);
        // Manual entries for types in shared packages (can't use processor without registry name conflict)
        all.add(methodNameCodec());
        return SliceCodec.sliceCodec(parent, all);
    }

    private static TypeCodec<MethodName> methodNameCodec() {
        return new TypeCodec<>(MethodName.class,
                               deterministicTag("org.pragmatica.aether.slice.MethodName"),
                               (codec, buf, val) -> writeString(buf, val.name()),
                               (codec, buf) -> new MethodName(readString(buf)));
    }
}
