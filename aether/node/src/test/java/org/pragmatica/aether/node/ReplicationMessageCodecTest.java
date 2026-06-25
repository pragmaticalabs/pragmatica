// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationMessage.BatchSync;
import org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupRequest;
import org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse;
import org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck;
import org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Round-trip coverage for the sealed [ReplicationMessage] hierarchy through the FULL production node
/// codec registry ([NodeCodecs]), exactly as the cluster network builds it. Guards the 7a fix:
/// before `aether-stream` ran the `@Codec` processor and these codecs were registered in
/// `NodeCodecs`, every `ReplicateEvents` send threw `IllegalArgumentException: No codec registered for
/// ReplicationMessage$ReplicateEvents` over the wire — which, replayed during a replacement node's
/// consensus resync, killed the apply thread and wedged the cluster. The `List<byte[]>` payload path
/// has no other precedent in the codebase, so it is exercised explicitly.
///
/// Note: `ReplicateEvents`/`CatchupResponse` are records with `List<byte[]>` components and no custom
/// `equals`, so the default record equality compares payload arrays by identity. These cases assert
/// field-by-field with element-wise byte[] comparison; the array-free variants assert by value.
class ReplicationMessageCodecTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final NodeId GOVERNOR = new NodeId("governor-1");
    private static final NodeId REPLICA = new NodeId("replica-2");

    @Test
    void replicateEvents_roundTrips_withNonEmptyPayloads() {
        var original = new ReplicateEvents(GOVERNOR, "system:cluster-events:1.0.0", 3, 42L,
                                           List.of(new byte[]{1, 2, 3}, new byte[]{}, new byte[]{9}),
                                           List.of(100L, 200L, 300L), Epoch.ZERO);

        ReplicationMessage decoded = CODEC.decode(CODEC.encode(original));

        assertThat(decoded).isInstanceOf(ReplicateEvents.class);
        var event = (ReplicateEvents) decoded;
        assertThat(event.governorId()).isEqualTo(GOVERNOR);
        assertThat(event.streamName()).isEqualTo("system:cluster-events:1.0.0");
        assertThat(event.partition()).isEqualTo(3);
        assertThat(event.fromOffset()).isEqualTo(42L);
        assertThat(event.ownerEpoch()).isEqualTo(Epoch.ZERO);
        assertThat(event.timestamps()).containsExactly(100L, 200L, 300L);
        assertThat(event.payloads()).usingElementComparator(Arrays::compare)
                                    .containsExactly(new byte[]{1, 2, 3}, new byte[]{}, new byte[]{9});
    }

    @Test
    void replicateAck_roundTrips() {
        var original = new ReplicateAck(REPLICA, "app:orders:2.1.0", 0, 7L);

        ReplicationMessage decoded = CODEC.decode(CODEC.encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void batchSync_roundTrips() {
        var original = new BatchSync(GOVERNOR, "app:orders:2.1.0", 1, 0L, 5L, new byte[]{4, 5, 6});

        ReplicationMessage decoded = CODEC.decode(CODEC.encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void catchupRequest_roundTrips() {
        var original = new CatchupRequest(REPLICA, "app:orders:2.1.0", 2, 10L);

        ReplicationMessage decoded = CODEC.decode(CODEC.encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void catchupResponse_roundTrips_withPayloads() {
        var original = new CatchupResponse(GOVERNOR, "app:orders:2.1.0", 1, 0L, 2L,
                                           List.of(new byte[]{7}, new byte[]{8, 8}),
                                           List.of(11L, 22L));

        ReplicationMessage decoded = CODEC.decode(CODEC.encode(original));

        assertThat(decoded).isInstanceOf(CatchupResponse.class);
        var response = (CatchupResponse) decoded;
        assertThat(response.governorId()).isEqualTo(GOVERNOR);
        assertThat(response.toOffset()).isEqualTo(2L);
        assertThat(response.timestamps()).containsExactly(11L, 22L);
        assertThat(response.payloads()).usingElementComparator(Arrays::compare)
                                       .containsExactly(new byte[]{7}, new byte[]{8, 8});
    }
}
