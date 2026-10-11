// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.aether.slice.stream.PublishOutcome;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// #1338: end-to-end pins for the system-write gate and the `systemWriter` mark. Two in-JVM nodes, A (origin) and B (owner of
/// every partition), wired through the REAL [StreamForwardClient] and [StreamForwardHandler]; the refusal arms use only the
/// public API of a non-system origin. Each pin names the line it keeps alive: the client and router send the mark, the
/// router keeps the grant for a local batch, a slice publisher binding never creates a system ring, the
/// materialize-then-retry append keeps the mark, and every public owner-append overload refuses without it.
class SystemStreamForwardPinsTest {
    private static final NodeId A = NodeId.randomNodeId();
    private static final NodeId B = NodeId.randomNodeId();
    private static final String SYSTEM = SystemStreams.CLUSTER_EVENTS.asString();
    private static final String ORDERS = "com.acme:orders:1.0.0";

    private StreamPartitionManager mgrA;
    private StreamPartitionManager mgrB;
    private StreamForwardClient clientA;
    private final List<PublishForward> wire = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        mgrA = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        mgrB = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        for (var m : List.of(mgrA, mgrB)) {
            m.createStream(StreamConfig.streamConfig(SYSTEM));
            m.createStream(StreamConfig.streamConfig(ORDERS));
        }
        var holder = new StreamForwardClient[1];
        var handlerB = StreamForwardHandler.streamForwardHandler(B,
                                                                 mgrB,
                                                                 (_, message) -> holder[0].onPublishForwardResponse((PublishForwardResponse) message));
        clientA = StreamForwardClient.streamForwardClient(A, (_, message) -> {
            if (message instanceof PublishForward pf) {
                wire.add(pf);
                handlerB.onPublishForward(pf);
            }
        });
        holder[0] = clientA;
    }

    @AfterEach
    void tearDown() {
        mgrA.close();
        mgrB.close();
    }

    private static long events(StreamPartitionManager m, String s) {
        return m.partitionInfo(s, 0).unwrap().eventCount();
    }

    private StreamWriteRouter nonSystemRouterToB() {
        return StreamWriteRouter.streamWriteRouter(mgrA, Option.some(clientA), A, (_, _) -> Option.some(B));
    }

    @Test
    void nonSystemRouter_toARemoteOwnersSystemStream_singleAndBatch_areRefused_nothingOnTheWire() {
        var r = nonSystemRouterToB();
        var single = r.publish(SYSTEM, 0, new byte[]{1}, 1L).await();
        var batch = r.publishBatch(SYSTEM, 0, List.of(new byte[]{1}, new byte[]{2}), 1L).await();

        assertThat(single.isFailure()).isTrue();
        single.onFailure(c -> assertThat(c).isInstanceOf(StreamError.SystemStreamWriteRefused.class));
        batch.onSuccess(o -> assertThat(o).noneMatch(x -> x instanceof PublishOutcome.Published));
        assertThat(wire).as("nothing forwarded").isEmpty();
        assertThat(events(mgrB, SYSTEM)).isZero();
        assertThat(events(mgrA, SYSTEM)).isZero();
    }

    @Test
    void control_nonSystemRouter_toARemoteOwner_landsUnmarked() {
        var r = nonSystemRouterToB();
        var res = r.publish(ORDERS, 0, new byte[]{1}, 1L).await();

        assertThat(res.isSuccess()).as("res=%s", res).isTrue();
        assertThat(events(mgrB, ORDERS)).isEqualTo(1L);
        assertThat(wire).hasSize(1);
        assertThat(wire.getFirst().systemWriter()).isFalse();
    }

    @Test
    void systemPublisher_toARemoteOwner_landsAndEveryFrameCarriesTheMark() {
        var pub = SystemStreamFactories.<byte[]> systemStreamPublisher(SystemStreams.CLUSTER_EVENTS,
                                                                       mgrA,
                                                                       ser(),
                                                                       StreamConfig.streamConfig(SYSTEM),
                                                                       Option.some(clientA),
                                                                       A,
                                                                       Option.some(_ -> Option.some(B))).unwrap();
        var s = pub.publish(new byte[]{7}).await();

        assertThat(s.isSuccess()).as("s=%s", s).isTrue();
        assertThat(events(mgrB, SYSTEM)).isEqualTo(1L);
        assertThat(wire).isNotEmpty().allMatch(PublishForward::systemWriter);
    }

    @Test
    void rawClientWithoutTheMark_toASystemStream_isRefusedAtTheOwner() {
        var res = clientA.publishRemote(B, SYSTEM, 0, new byte[]{1}, 1L).await();

        assertThat(res.isFailure()).as("res=%s", res).isTrue();
        assertThat(events(mgrB, SYSTEM)).isZero();
        assertThat(wire).hasSize(1);
        assertThat(wire.getFirst().systemWriter()).isFalse();
    }

    @Test
    void honestyPin_aPeerThatSetsTheMark_landsASystemWrite_theClaimIsForgeable() {
        var res = clientA.publishRemote(B, SYSTEM, 0, new byte[]{1}, 1L, true).await();

        assertThat(res.isSuccess()).as("res=%s", res).isTrue();
        assertThat(events(mgrB, SYSTEM)).isEqualTo(1L);
    }

    @Test
    void unmarkedForward_toAnOwnerWithNoRing_isRefused_andMintsNoRing() {
        var bare = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        try {
            var res = bare.publishForwarded(SYSTEM, 0, new byte[]{1}, 1L, 0);

            assertThat(res.isFailure()).as("res=%s", res).isTrue();
            assertThat(bare.streamInfo(SYSTEM).isPresent()).as("no ring minted by an unmarked forward").isFalse();
        } finally {
            bare.close();
        }
    }


    @Test
    void bareUserStreamNamedClusterEvents_isIndependentOfTheSystemRing_bothDirections() {
        var m = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        try {
            m.createStream(StreamConfig.streamConfig("cluster-events"));
            m.createStream(StreamConfig.streamConfig(SYSTEM));
            m.createStream(StreamConfig.streamConfig("com.acme:cluster-events:1.0.0"));
            var ctx = ProvisioningContext.provisioningContext()
                                         .withExtension(StreamPartitionManager.class, m)
                                         .withExtension(Serializer.class, ser())
                                         .withExtension(Deserializer.class, deser())
                                         .withExtension(String.class, "org.acme:consumer:1.0.0");
            @SuppressWarnings("unchecked")
            var access = (StreamAccess<byte[]>) new StreamAccessFactory().provision(StreamConfig.streamConfig("cluster-events"), ctx).await().unwrap();
            var res = access.publish(new byte[]{1}).await();

            assertThat(res.isSuccess()).as("bare user stream publish res=%s", res).isTrue();
            assertThat(events(m, "cluster-events")).isEqualTo(1L);
            assertThat(events(m, SYSTEM)).as("system ring untouched by the bare user stream").isZero();
            assertThat(events(m, "com.acme:cluster-events:1.0.0")).isZero();

            var sys = SystemStreamFactories.<byte[]> systemStreamPublisher(SystemStreams.CLUSTER_EVENTS, m, ser(), StreamConfig.streamConfig(SYSTEM)).unwrap();
            sys.publish(new byte[]{2}).await();
            assertThat(events(m, SYSTEM)).isEqualTo(1L);
            assertThat(events(m, "cluster-events")).as("bare ring untouched by the system publisher").isEqualTo(1L);
        } finally {
            m.close();
        }
    }

    private static Deserializer deser() {
        return new Deserializer() {
            @SuppressWarnings("unchecked") @Override public <T> T decode(byte[] b) {return (T) b;}

            @SuppressWarnings("unchecked") @Override public <T> T read(ByteBuf b) {
                var x = new byte[b.readableBytes()];
                b.readBytes(x);
                return (T) x;
            }
        };
    }


    @Test
    void markedForward_toAnOwnerWithACommittedConfigButNoRing_materializesAndLands_unmarkedIsRefused() {
        var marked = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        var unmarked = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        try {
            marked.committedConfigSource(name -> Option.some(StreamConfig.streamConfig(name)));
            unmarked.committedConfigSource(name -> Option.some(StreamConfig.streamConfig(name)));
            var ok = marked.publishForwarded(SYSTEM, 0, new byte[]{1}, 1L, 0, true);
            var refused = unmarked.publishForwarded(SYSTEM, 0, new byte[]{1}, 1L, 0, false);
            assertThat(ok.isSuccess()).as("marked forward after lazy materialize ok=%s", ok).isTrue();
            assertThat(events(marked, SYSTEM)).isEqualTo(1L);
            assertThat(refused.isFailure()).isTrue();
            refused.onFailure(c -> assertThat(c).isInstanceOf(StreamError.SystemStreamWriteRefused.class));
            assertThat(unmarked.streamInfo(SYSTEM).isPresent() ? events(unmarked, SYSTEM) : 0L).isZero();
        } finally {
            marked.close();
            unmarked.close();
        }
    }

    @Test
    void publicOwnerAppendOverloads_publishLocalFourArgAndWithEpoch_refuseWithoutTheMark() {
        var epoch = org.pragmatica.aether.slice.generation.Epoch.ZERO;
        var four = mgrB.publishLocal(SYSTEM, 0, new byte[]{1}, 1L);
        var withEpoch = mgrB.publishLocal(SYSTEM, 0, new byte[]{1}, 1L, epoch);

        for (var r : List.of(four, withEpoch)) {
            assertThat(r.isFailure()).as("r=%s", r).isTrue();
            r.onFailure(c -> assertThat(c).isInstanceOf(StreamError.SystemStreamWriteRefused.class));
        }
        assertThat(events(mgrB, SYSTEM)).isZero();
    }


    @Test
    void systemPublisher_publishBatchToALocalOwner_lands() {
        var m = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        try {
            var pub = SystemStreamFactories.<byte[]> systemStreamPublisher(SystemStreams.CLUSTER_EVENTS, m, ser(), StreamConfig.streamConfig(SYSTEM)).unwrap();
            var res = pub.publishBatch(List.of(new byte[]{1}, new byte[]{2})).await();

            assertThat(res.isSuccess()).as("res=%s", res).isTrue();
            res.onSuccess(o -> assertThat(o).hasSize(2).allMatch(x -> x instanceof PublishOutcome.Published));
            var total = m.allPartitionInfo(SYSTEM).unwrap().stream().mapToLong(i -> i.eventCount()).sum();

            assertThat(total).isEqualTo(2L);
        } finally {
            m.close();
        }
    }

    @Test
    void slicePublisherFactory_bindingAnAbsentSystemRing_fails_andMintsNothing() {
        var m = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        try {
            var ctx = ProvisioningContext.provisioningContext()
                                         .withExtension(StreamPartitionManager.class, m)
                                         .withExtension(Serializer.class, ser())
                                         .withExtension(Deserializer.class, deser())
                                         .withExtension(StreamAddressResolver.class, (_, _) -> Result.success(SYSTEM))
                                         .withExtension(String.class, "org.acme:consumer:1.0.0");
            var res = new StreamPublisherFactory().provision(StreamConfig.streamConfig("alias"), ctx).await();

            assertThat(res.isFailure()).as("res=%s", res).isTrue();
            assertThat(m.streamInfo(SYSTEM).isPresent()).as("no system ring minted by a slice publisher binding").isFalse();
        } finally {
            m.close();
        }
    }

    private static Serializer ser() {
        return new Serializer() {
            @Override public <T> byte[] encode(T o) {return (byte[]) o;}

            @Override public <T> void write(ByteBuf b, T o) {b.writeBytes((byte[]) o);}
        };
    }
}
