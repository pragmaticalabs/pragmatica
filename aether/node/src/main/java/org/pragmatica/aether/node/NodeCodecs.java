package org.pragmatica.aether.node;

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

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Set;

import static org.pragmatica.serialization.SliceCodec.deterministicTag;
import static org.pragmatica.serialization.SliceCodec.readCompact;
import static org.pragmatica.serialization.SliceCodec.readString;
import static org.pragmatica.serialization.SliceCodec.writeCompact;
import static org.pragmatica.serialization.SliceCodec.writeString;

/// Registry of all node-level types for serialization.
/// Collects generated codec registries from all modules and adds manual entries
/// for types that can't use the annotation processor (e.g. shared-package conflicts).
@CodecFor({InetSocketAddress.class, MethodName.class, TimeSpan.class, URI.class, UUID.class, OffsetDateTime.class,
 Email.class, Url.class, NonBlankString.class, Uuid.class, IsoDateTime.class})
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
        all.addAll(org.pragmatica.aether.dht.DhtCodecs.CODECS);
        all.addAll(org.pragmatica.aether.http.handler.HandlerCodecs.CODECS);
        all.addAll(org.pragmatica.aether.http.handler.security.SecurityCodecs.CODECS);
        all.addAll(org.pragmatica.swim.SwimCodecs.CODECS);
        // Manual entries for types in shared packages (can't use processor without registry name conflict)
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

    /// InetSocketAddress codec — uses createUnresolved to avoid blocking DNS on deserialization.
    /// Wire format: hostname (string) + port (compact int).
    private static TypeCodec<InetSocketAddress> inetSocketAddressCodec() {
        return new TypeCodec<>(InetSocketAddress.class,
                               deterministicTag("java.net.InetSocketAddress"),
                               (codec, buf, val) -> {
                                   writeString(buf, val.getHostString());
                                   writeCompact(buf, val.getPort());
                               },
                               (codec, buf) -> InetSocketAddress.createUnresolved(readString(buf), readCompact(buf)));
    }

    /// TimeSpan codec — serializes as nanos (long).
    private static TypeCodec<TimeSpan> timeSpanCodec() {
        return new TypeCodec<>(TimeSpan.class,
                               deterministicTag("org.pragmatica.lang.io.TimeSpan"),
                               (codec, buf, val) -> buf.writeLong(val.nanos()),
                               (codec, buf) -> TimeSpan.timeSpan(buf.readLong())
                                                       .nanos());
    }

    /// Email codec — serializes as localPart (string) + domain (string).
    private static TypeCodec<Email> emailCodec() {
        return new TypeCodec<>(Email.class,
                               deterministicTag("org.pragmatica.lang.vo.Email"),
                               (codec, buf, val) -> {
                                   writeString(buf, val.localPart());
                                   writeString(buf, val.domain());
                               },
                               (codec, buf) -> new Email(readString(buf), readString(buf)));
    }

    /// Url codec — serializes as URI string.
    private static TypeCodec<Url> urlCodec() {
        return new TypeCodec<>(Url.class,
                               deterministicTag("org.pragmatica.lang.vo.Url"),
                               (codec, buf, val) -> writeString(buf,
                                                                val.uri()
                                                                   .toString()),
                               (codec, buf) -> new Url(URI.create(readString(buf))));
    }

    /// NonBlankString codec — serializes as string value.
    private static TypeCodec<NonBlankString> nonBlankStringCodec() {
        return new TypeCodec<>(NonBlankString.class,
                               deterministicTag("org.pragmatica.lang.vo.NonBlankString"),
                               (codec, buf, val) -> writeString(buf, val.value()),
                               (codec, buf) -> new NonBlankString(readString(buf)));
    }

    /// Uuid codec — serializes as string.
    private static TypeCodec<Uuid> uuidCodec() {
        return new TypeCodec<>(Uuid.class,
                               deterministicTag("org.pragmatica.lang.vo.Uuid"),
                               (codec, buf, val) -> writeString(buf,
                                                                val.value()
                                                                   .toString()),
                               (codec, buf) -> new Uuid(java.util.UUID.fromString(readString(buf))));
    }

    /// IsoDateTime codec — serializes as ISO 8601 string.
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
