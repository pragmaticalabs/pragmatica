// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.ForwardingReadRouter.LocalReader;
import org.pragmatica.aether.stream.ForwardingReadRouter.OwnerResolver;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardClient.ReadForwardResult;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Per-arm recovery of a `PARTITION_NOT_LOCAL` local-read FAILURE into an owner forward
/// (metadata-only-node read recovery). Drives {@link ForwardingReadRouter} directly over fake local-read /
/// decode / owner-resolve / forward-client closures so each routing arm's failure handling is exercised in
/// isolation: `GOVERNOR` and the two `NEAREST` local arms forward on `PARTITION_NOT_LOCAL`, propagate any
/// OTHER failure, and — when the owner is unknown/self or no forward client is wired — surface the original
/// `PARTITION_NOT_LOCAL` rather than looping.
class ForwardingReadRouterTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OWNER = new NodeId("owner-node");
    private static final String STREAM = "read-stream";
    private static final int PARTITION = 0;

    @Test
    void route_forwardsToOwner_whenGovernorLocalReadFailsPartitionNotLocal() {
        var router = router(ReadPreference.GOVERNOR,
                            Option.none(),
                            partitionNotLocalReader(),
                            Option.some(forwardClientServing("r0", "r1")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("r0", "r1"));
    }

    @Test
    void route_propagatesPartitionNotLocal_whenGovernorOwnerUnknown() {
        var router = router(ReadPreference.GOVERNOR,
                            Option.none(),
                            partitionNotLocalReader(),
                            Option.some(forwardClientServing("r0")),
                            noOwner());

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onSuccess(events -> Assertions.fail("expected PARTITION_NOT_LOCAL to propagate, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.PARTITION_NOT_LOCAL));
    }

    @Test
    void route_propagatesPartitionNotLocal_whenGovernorOwnerIsSelf() {
        var router = router(ReadPreference.GOVERNOR,
                            Option.none(),
                            partitionNotLocalReader(),
                            Option.some(forwardClientServing("r0")),
                            owner(SELF));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onSuccess(events -> Assertions.fail("expected PARTITION_NOT_LOCAL to propagate, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.PARTITION_NOT_LOCAL));
    }

    @Test
    void route_doesNotRecover_whenGovernorLocalReadFailsOtherCause() {
        var router = router(ReadPreference.GOVERNOR,
                            Option.none(),
                            failingReader(new StreamError.StreamNotFound(STREAM)),
                            Option.some(forwardClientServing("r0")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onSuccess(events -> Assertions.fail("expected StreamNotFound to propagate un-forwarded, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StreamNotFound.class));
    }

    @Test
    void route_readsLocal_whenGovernorLocalReadSucceeds() {
        var router = router(ReadPreference.GOVERNOR,
                            Option.none(),
                            successReader("local"),
                            Option.some(forwardClientServing("owner-served")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("local"));
    }

    @Test
    void route_forwardsToOwner_whenNearestNoRegistryLocalReadFailsPartitionNotLocal() {
        var router = router(ReadPreference.NEAREST,
                            Option.none(),
                            partitionNotLocalReader(),
                            Option.some(forwardClientServing("r0")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("r0"));
    }

    @Test
    void route_forwardsToOwner_whenNearestSelfNotCoveringLocalReadFailsPartitionNotLocal() {
        var router = router(ReadPreference.NEAREST,
                            Option.some(ReplicaRegistry.replicaRegistry()),
                            partitionNotLocalReader(),
                            Option.some(forwardClientServing("r0", "r1")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("r0", "r1"));
    }

    @Test
    void route_doesNotRecover_whenNearestSelfNotCoveringLocalReadFailsOtherCause() {
        var router = router(ReadPreference.NEAREST,
                            Option.some(ReplicaRegistry.replicaRegistry()),
                            failingReader(new StreamError.StreamNotFound(STREAM)),
                            Option.some(forwardClientServing("r0")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onSuccess(events -> Assertions.fail("expected StreamNotFound to propagate un-forwarded, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StreamNotFound.class));
    }

    @Test
    void route_readsLocal_whenNearestSelfCoversPartition() {
        var registry = ReplicaRegistry.replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.updateWatermark(STREAM, PARTITION, SELF, 0L, ReplicationState.CAUGHT_UP);

        var router = router(ReadPreference.NEAREST,
                            Option.some(registry),
                            successReader("local"),
                            Option.some(forwardClientServing("owner-served")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("local"));
    }

    /// PR-2f S2 (know `e10cf838c9`): a copy behind the owner forwards a GOVERNOR read without reading local.
    @Test
    void route_forwardsToOwner_whenGovernorCopyIsBehindTheOwner() {
        var router = staleRouter(true, owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("owner-served"));
    }

    @Test
    void route_readsLocal_whenGovernorCopyIsNotBehindTheOwner() {
        var router = staleRouter(false, owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("local"));
    }

    @Test
    void route_readsLocal_whenGovernorCopyIsBehindButTheOwnerIsSelf() {
        var router = staleRouter(true, owner(SELF));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("local"));
    }

    /// After a restart the committed owner can differ from the HRW owner; the stale copy's read goes to the committed one.
    @Test
    void route_forwardsToTheCommittedOwner_whenGovernorCopyIsBehindAndItDiffersFromTheHrwOwner() {
        var committed = new NodeId("committed-owner");
        var router = ForwardingReadRouter.forwardingReadRouter(Option.none(),
                                                               SELF,
                                                               Option.some(forwardClientServingOnly(committed, "committed-served")),
                                                               ReadPreference.GOVERNOR,
                                                               owner(OWNER),
                                                               successReader("local"),
                                                               ForwardingReadRouterTest::decode,
                                                               StreamReadForwardMetrics.NOOP,
                                                               Option.some((_, _) -> Option.some(new CommittedStreamOwnerSource.CommittedOwner(committed,
                                                                                                                                              Epoch.ZERO))),
                                                               Option.none(),
                                                               Option.none(),
                                                               (_, _) -> true);

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("committed-served"));
    }

    private static ForwardingReadRouter<String> staleRouter(boolean behind, OwnerResolver ownerResolver) {
        return ForwardingReadRouter.forwardingReadRouter(Option.none(),
                                                         SELF,
                                                         Option.some(forwardClientServing("owner-served")),
                                                         ReadPreference.GOVERNOR,
                                                         ownerResolver,
                                                         successReader("local"),
                                                         ForwardingReadRouterTest::decode,
                                                         StreamReadForwardMetrics.NOOP,
                                                         Option.none(),
                                                         Option.none(),
                                                         Option.none(),
                                                         (_, _) -> behind);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static ForwardingReadRouter<String> router(ReadPreference preference,
                                                       Option<ReplicaRegistry> registry,
                                                       LocalReader<String> localReader,
                                                       Option<StreamForwardClient> forwardClient,
                                                       OwnerResolver ownerResolver) {
        return ForwardingReadRouter.forwardingReadRouter(registry,
                                                         SELF,
                                                         forwardClient,
                                                         preference,
                                                         ownerResolver,
                                                         localReader,
                                                         ForwardingReadRouterTest::decode,
                                                         StreamReadForwardMetrics.NOOP);
    }

    private static LocalReader<String> partitionNotLocalReader() {
        return failingReader(StreamError.General.PARTITION_NOT_LOCAL);
    }

    private static LocalReader<String> failingReader(Cause cause) {
        return (_, _, _, _) -> cause.promise();
    }

    private static LocalReader<String> successReader(String... values) {
        var events = List.of(values);

        return (_, _, _, _) -> Promise.success(events);
    }

    private static OwnerResolver owner(NodeId owner) {
        return (_, _) -> Option.some(owner);
    }

    private static OwnerResolver noOwner() {
        return (_, _) -> Option.none();
    }

    private static List<String> decode(List<RawEventDto> events, int partition) {
        return events.stream().map(ForwardingReadRouterTest::asString).toList();
    }

    private static String asString(RawEventDto dto) {
        return new String(dto.data());
    }

    private static StreamForwardClient forwardClientServing(String... data) {
        var dtos = Arrays.stream(data).map(ForwardingReadRouterTest::toDto).toList();

        return new StreamForwardClient() {
            @Override
            public Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp) {
                return Promise.success(-1L);
            }

            @Override
            public Promise<ReadForwardResult> readRemote(NodeId replicaId, String streamName, int partition, long fromOffset, int maxEvents) {
                return Promise.success(new ReadForwardResult(dtos, false));
            }

            @Override
            public void onPublishForwardResponse(PublishForwardResponse response) {}

            @Override
            public void onReadForwardResponse(ReadForwardResponse response) {}
        };
    }

    /// Serves `data` to a read forwarded to `target`; any other node answers `PARTITION_NOT_LOCAL` (the router then degrades
    /// to its local read).
    private static StreamForwardClient forwardClientServingOnly(NodeId target, String... data) {
        var dtos = Arrays.stream(data).map(ForwardingReadRouterTest::toDto).toList();

        return new StreamForwardClient() {
            @Override
            public Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp) {
                return Promise.success(-1L);
            }

            @Override
            public Promise<ReadForwardResult> readRemote(NodeId replicaId, String streamName, int partition, long fromOffset, int maxEvents) {
                return replicaId.equals(target)
                       ? Promise.success(new ReadForwardResult(dtos, false))
                       : StreamError.General.PARTITION_NOT_LOCAL.promise();
            }

            @Override
            public void onPublishForwardResponse(PublishForwardResponse response) {}

            @Override
            public void onReadForwardResponse(ReadForwardResponse response) {}
        };
    }

    private static RawEventDto toDto(String data) {
        return new RawEventDto(0L, 1L, data.getBytes());
    }

    private static void failUnexpected(Cause cause) {
        Assertions.fail("unexpected failure: " + cause.message());
    }
}
