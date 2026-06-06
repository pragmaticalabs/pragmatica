// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ClusterEvent.AccessDenied;
import org.pragmatica.aether.api.ClusterEvent.AlertInjected;
import org.pragmatica.aether.api.ClusterEvent.BackupCreated;
import org.pragmatica.aether.api.ClusterEvent.BackupRestored;
import org.pragmatica.aether.api.ClusterEvent.BlueprintDeleted;
import org.pragmatica.aether.api.ClusterEvent.BlueprintDeployed;
import org.pragmatica.aether.api.ClusterEvent.CommunityMetricsSnapshot;
import org.pragmatica.aether.api.ClusterEvent.CommunityScaleRequest;
import org.pragmatica.aether.api.ClusterEvent.ConfigChanged;
import org.pragmatica.aether.api.ClusterEvent.ConnectionEstablished;
import org.pragmatica.aether.api.ClusterEvent.ConnectionFailed;
import org.pragmatica.aether.api.ClusterEvent.DeploymentCompleted;
import org.pragmatica.aether.api.ClusterEvent.DeploymentFailed;
import org.pragmatica.aether.api.ClusterEvent.DeploymentStarted;
import org.pragmatica.aether.api.ClusterEvent.GenerationChanged;
import org.pragmatica.aether.api.ClusterEvent.LeaderElected;
import org.pragmatica.aether.api.ClusterEvent.LeaderLost;
import org.pragmatica.aether.api.ClusterEvent.NodeFailed;
import org.pragmatica.aether.api.ClusterEvent.NodeJoined;
import org.pragmatica.aether.api.ClusterEvent.NodeLeft;
import org.pragmatica.aether.api.ClusterEvent.NodeLifecycleChanged;
import org.pragmatica.aether.api.ClusterEvent.QuorumEstablished;
import org.pragmatica.aether.api.ClusterEvent.QuorumLost;
import org.pragmatica.aether.api.ClusterEvent.ScaleDown;
import org.pragmatica.aether.api.ClusterEvent.ScaleUp;
import org.pragmatica.aether.api.ClusterEvent.SelfDrainInitiated;
import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.aether.api.ClusterEvent.SliceFailure;
import org.pragmatica.aether.api.ClusterEvent.StreamDeleted;
import org.pragmatica.aether.api.ClusterEvent.StreamMemoryExceeded;
import org.pragmatica.aether.api.ClusterEvent.StreamRegistered;
import org.pragmatica.aether.api.ClusterEvent.TraceInjected;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SliceCodec.TypeCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Round-trip coverage for the sealed [ClusterEvent] hierarchy through the project's
/// `@Codec`-generated codec framework (increment B5a). Encodes via the polymorphic
/// `SliceCodec.encode`/`decode` byte[] path that the partition stream transport will use,
/// then asserts the decoded event equals the original — including the `details` map, the
/// `at()` HLC timestamp, and (for stream events) the carried [ResourceAddress].
///
/// Codecs are sourced from the runtime registry exactly as production builds it:
/// `NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs())`, which now includes the
/// generated `ApiCodecsNode.CODECS`.
class ClusterEventCodecTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

    private static HlcTimestamp at(long millis, int counter, String node) {
        return new HlcTimestamp(HlcTimestamp.pack(millis, counter), new NodeId(node));
    }

    private static Map<String, String> details(String... kv) {
        var map = new LinkedHashMap<String, String>();

        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }

        return Map.copyOf(map);
    }

    private static ResourceAddress resourceAddress() {
        return ResourceAddress.resourceAddress("ns", "events", "1.2.3").unwrap();
    }

    /// Polymorphic round-trip via the byte[] codec path. Encoding uses the runtime
    /// dispatch (concrete-class -> tag), decoding reads the tag back to the concrete variant.
    @SuppressWarnings("unchecked")
    private static <T extends ClusterEvent> T roundTrip(T event) {
        return (T) (ClusterEvent) CODEC.decode(CODEC.encode(event));
    }

    @Test
    void nodeJoined_roundTrips() {
        var original = new NodeJoined(at(1_000L, 1, "n1"), Severity.INFO, "node joined", details("nodeId", "n1"));

        assertThat(roundTrip(original)).isEqualTo(original);
    }

    @Test
    void nodeFailed_roundTrips() {
        var original = new NodeFailed(at(2_000L, 7, "n2"), Severity.CRITICAL, "node failed",
                                      details("nodeId", "n2", "reason", "timeout"));
        var decoded = roundTrip(original);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(decoded.at()).isEqualTo(original.at());
        assertThat(decoded.details()).isEqualTo(original.details());
    }

    @Test
    void leaderElected_roundTrips() {
        var original = new LeaderElected(at(3_000L, 0, "leader"), Severity.INFO, "leader elected",
                                         details("leader", "leader"));

        assertThat(roundTrip(original)).isEqualTo(original);
    }

    @Test
    void selfDrainInitiated_roundTrips() {
        var original = new SelfDrainInitiated(at(4_000L, 3, "n3"), Severity.WARNING, "self drain",
                                              details("nodeId", "n3", "reason", "quorum-disappeared", "graceMs", "5000"));

        assertThat(roundTrip(original)).isEqualTo(original);
    }

    @Test
    void streamMemoryExceeded_codecRoundTrip() {
        var original = new StreamMemoryExceeded(at(4_500L, 8, "n3"), Severity.WARNING,
                                                "Off-heap budget exhausted (create-floor) for stream 'orders' (4 parts): need 2097152 bytes, 1153433 available of 134217728",
                                                details("streamName", "orders",
                                                        "partitions", "4",
                                                        "phase", "create-floor",
                                                        "requestedBytes", "2097152",
                                                        "availableBytes", "1153433",
                                                        "maxTotalBytes", "134217728",
                                                        "consistencyMode", "EVENTUAL",
                                                        "nodeId", "n3"));
        var decoded = roundTrip(original);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.severity()).isEqualTo(Severity.WARNING);
        assertThat(decoded.details()).containsEntry("phase", "create-floor")
                                     .containsEntry("streamName", "orders")
                                     .containsEntry("nodeId", "n3");
    }

    @Test
    void streamRegistered_roundTrips_withAddress() {
        var addr = resourceAddress();
        var original = new StreamRegistered(at(5_000L, 2, "n4"), Severity.INFO, "stream registered",
                                            details("stream", "ns:events:1.2.3"), addr);
        var decoded = roundTrip(original);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.address()).isEqualTo(addr);
    }

    @Test
    void streamDeleted_roundTrips_withAddress() {
        var addr = resourceAddress();
        var original = new StreamDeleted(at(6_000L, 9, "n5"), Severity.WARNING, "stream deleted",
                                         details("stream", "ns:events:1.2.3"), addr);
        var decoded = roundTrip(original);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.address()).isEqualTo(addr);
    }

    @Test
    void alertInjected_roundTrips() {
        var original = new AlertInjected(at(7_000L, 1, "n6"), Severity.CRITICAL, "alert",
                                         details("alertId", "injected-7000-1", "metric", "cpu", "value", "0.99"));

        assertThat(roundTrip(original)).isEqualTo(original);
    }

    @Test
    void traceInjected_roundTrips() {
        var original = new TraceInjected(at(8_000L, 4, "n7"), Severity.INFO, "trace",
                                         details("requestId", "r1", "traceId", "t1", "operation", "op",
                                                 "durationMs", "12", "depth", "3"));

        assertThat(roundTrip(original)).isEqualTo(original);
    }

    @Test
    void emptyDetailsMap_roundTrips() {
        var original = new ScaleUp(at(9_000L, 0, "n8"), Severity.INFO, "scale up", Map.of());
        var decoded = roundTrip(original);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.details()).isEmpty();
    }

    @Test
    void multiEntryDetailsMap_roundTrips() {
        var original = new ConfigChanged(at(10_000L, 5, "n9"), Severity.WARNING, "config changed",
                                         details("a", "1", "b", "2", "c", "3", "d", "4", "e", "5"));
        var decoded = roundTrip(original);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.details()).hasSize(5);
    }

    @Test
    void unicodeSummary_roundTrips() {
        var original = new BackupCreated(at(11_000L, 1, "n10"), Severity.INFO,
                                         "привіт 🌍 backup",
                                         details("emoji", "🚀"));
        var decoded = roundTrip(original);

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.summary()).isEqualTo(original.summary());
        assertThat(decoded.details().get("emoji")).isEqualTo("🚀");
    }

    @Test
    void encodingIsDeterministic() {
        var original = new QuorumEstablished(at(12_000L, 6, "n11"), Severity.INFO, "quorum",
                                             details("size", "3", "term", "7"));

        assertThat(CODEC.encode(original)).isEqualTo(CODEC.encode(original));
    }

    /// Encode all 30 closed-set variants into a single buffer (a list), decode them back, and
    /// assert order and concrete type are preserved through the polymorphic tag dispatch.
    @Test
    void allClosedVariants_listRoundTrip_preservesOrderAndType() {
        var events = allClosedVariants();
        var buf = io.netty.buffer.Unpooled.buffer();

        try {
            SliceCodec.writeCompact(buf, events.size());

            for (var event : events) {
                CODEC.write(buf, event);
            }

            var decoded = new ArrayList<ClusterEvent>();
            var count = SliceCodec.readCompact(buf);

            for (int i = 0; i < count; i++) {
                decoded.add(CODEC.read(buf));
            }

            assertThat(decoded).hasSize(events.size());
            assertThat(decoded).isEqualTo(events);

            for (int i = 0; i < events.size(); i++) {
                assertThat(decoded.get(i).getClass()).isEqualTo(events.get(i).getClass());
            }
        } finally {
            buf.release();
        }
    }

    private static List<ClusterEvent> allClosedVariants() {
        var ts = at(100_000L, 1, "src");
        var sev = Severity.INFO;
        var d = details("k", "v");
        var addr = resourceAddress();

        return List.of(
            new NodeJoined(ts, sev, "NodeJoined", d),
            new NodeLeft(ts, sev, "NodeLeft", d),
            new NodeFailed(ts, sev, "NodeFailed", d),
            new LeaderElected(ts, sev, "LeaderElected", d),
            new LeaderLost(ts, sev, "LeaderLost", d),
            new QuorumEstablished(ts, sev, "QuorumEstablished", d),
            new QuorumLost(ts, sev, "QuorumLost", d),
            new DeploymentStarted(ts, sev, "DeploymentStarted", d),
            new DeploymentCompleted(ts, sev, "DeploymentCompleted", d),
            new DeploymentFailed(ts, sev, "DeploymentFailed", d),
            new ScaleUp(ts, sev, "ScaleUp", d),
            new ScaleDown(ts, sev, "ScaleDown", d),
            new SliceFailure(ts, sev, "SliceFailure", d),
            new ConnectionEstablished(ts, sev, "ConnectionEstablished", d),
            new ConnectionFailed(ts, sev, "ConnectionFailed", d),
            new CommunityScaleRequest(ts, sev, "CommunityScaleRequest", d),
            new CommunityMetricsSnapshot(ts, sev, "CommunityMetricsSnapshot", d),
            new AccessDenied(ts, sev, "AccessDenied", d),
            new NodeLifecycleChanged(ts, sev, "NodeLifecycleChanged", d),
            new ConfigChanged(ts, sev, "ConfigChanged", d),
            new BackupCreated(ts, sev, "BackupCreated", d),
            new BackupRestored(ts, sev, "BackupRestored", d),
            new BlueprintDeployed(ts, sev, "BlueprintDeployed", d),
            new BlueprintDeleted(ts, sev, "BlueprintDeleted", d),
            new GenerationChanged(ts, sev, "GenerationChanged", d),
            new StreamRegistered(ts, sev, "StreamRegistered", d, addr),
            new StreamDeleted(ts, sev, "StreamDeleted", d, addr),
            new AlertInjected(ts, sev, "AlertInjected", d),
            new TraceInjected(ts, sev, "TraceInjected", d),
            new SelfDrainInitiated(ts, sev, "SelfDrainInitiated", d),
            new StreamMemoryExceeded(ts, sev, "StreamMemoryExceeded", d)
        );
    }

    /// `ExtendedEvent` is the non-sealed extension hatch: it gets NO parent-level codec (the
    /// processor skips it). A concrete app-provided variant carries its own codec. This proves the
    /// extension path round-trips end-to-end when the app registers its variant's codec, mirroring
    /// how the framework adds manual codecs (e.g. InetSocketAddress in NodeCodecs).
    @Test
    void extendedEvent_roundTrips_viaOwnCodec() {
        var registry = SliceCodec.sliceCodec(CODEC, List.of(TestExtendedEvent.CODEC));
        var original = new TestExtendedEvent(at(13_000L, 2, "ext"), Severity.WARNING, "extended",
                                             details("plugin", "demo"), "com.example:custom");

        ClusterEvent decoded = registry.decode(registry.encode(original));

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded).isInstanceOf(ExtendedEvent.class);
        assertThat(((ExtendedEvent) decoded).discriminator()).isEqualTo("com.example:custom");
    }

    /// Test-local concrete `ExtendedEvent`. Hand-written `TypeCodec` (the manual-codec pattern the
    /// framework uses for non-generatable types) since extension events are app-owned.
    record TestExtendedEvent(HlcTimestamp at, Severity severity, String summary, Map<String, String> details,
                             String discriminator) implements ExtendedEvent {
        static final int TAG = SliceCodec.deterministicTag("org.pragmatica.aether.api.ClusterEventCodecTest.TestExtendedEvent");

        static final TypeCodec<TestExtendedEvent> CODEC = new TypeCodec<>(
            TestExtendedEvent.class, TAG,
            (codec, buf, value) -> {
                SliceCodec.writeCompact(buf, org.pragmatica.hlc.HlcTimestampCodec.TAG);
                org.pragmatica.hlc.HlcTimestampCodec.writeBody(codec, buf, value.at());
                SliceCodec.writeCompact(buf, value.severity().ordinal());
                SliceCodec.writeString(buf, value.summary());
                codec.write(buf, value.details());
                SliceCodec.writeString(buf, value.discriminator());
            },
            ClusterEventCodecTest::readExtended);
    }

    @SuppressWarnings("unchecked")
    private static TestExtendedEvent readExtended(SliceCodec codec, ByteBuf buf) {
        SliceCodec.readCompact(buf);
        var at = org.pragmatica.hlc.HlcTimestampCodec.readBody(codec, buf);
        var severity = Severity.values()[SliceCodec.readCompact(buf)];
        var summary = SliceCodec.readString(buf);
        var detailsMap = (Map<String, String>) codec.read(buf);
        var discriminator = SliceCodec.readString(buf);

        return new TestExtendedEvent(at, severity, summary, detailsMap, discriminator);
    }
}
