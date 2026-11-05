// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import org.pragmatica.aether.stream.forward.AcceptingStreamForwardTransport;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.control.ControlOutcome.Admitted;
import org.pragmatica.aether.stream.control.ControlOutcome.Refused;
import org.pragmatica.aether.stream.forward.ControlOutcomeDto;
import org.pragmatica.aether.stream.forward.ControlRecordDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ControlAppendForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ControlAppendForwardResponse;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.control.ControlLogFixture.PARTITION;
import static org.pragmatica.aether.stream.control.ControlLogFixture.PEER;
import static org.pragmatica.aether.stream.control.ControlLogFixture.SELF;
import static org.pragmatica.aether.stream.control.ControlLogFixture.STREAM;
import static org.pragmatica.aether.stream.control.ControlLogFixture.ack;
import static org.pragmatica.aether.stream.control.ControlLogFixture.await;
import static org.pragmatica.aether.stream.control.ControlLogFixture.awaitAdmissionReady;
import static org.pragmatica.aether.stream.control.ControlLogFixture.awaitTrue;
import static org.pragmatica.aether.stream.control.ControlLogFixture.owner;
import static org.pragmatica.aether.stream.control.ControlLogFixture.record;
import static org.pragmatica.aether.stream.control.ControlLogFixture.ringHead;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;


/// #2590 P1: the forwarded batch-append. A batch crosses as ONE message, runs on the owner as one queue item with one run
/// append, and comes back with one outcome per record; the owner side runs through the same entry as a forwarded publish,
/// so its barrier uses the confirmation factor read after the stream materialized (#1391).
class ControlLogForwardTest {
    private static final NodeId SENDER = NodeId.nodeId("ctl-sender").unwrap();

    /// Behaviour 13. One admitted, one refused, one admitted: each record's own outcome crosses the wire.
    /// Mutation: map every outcome to Admitted.
    @Test
    void forwardedBatch_mixedOutcomes_perRecord() {
        try (var owner = owner(1, 1)) {
            awaitAdmissionReady(owner);
            var client = clientOf(owner, new AtomicInteger());

            var outcomes = await(client.appendControlRemote(SELF,
                                                            STREAM,
                                                            PARTITION,
                                                            List.of(record("k", 1, 1), record("k", 0, 1), record("j", 1, 1))));

            assertThat(outcomes).hasSize(3);
            assertThat(outcomes.get(0)).isEqualTo(new Admitted(0L));
            assertThat(outcomes.get(1)).isInstanceOfSatisfying(Refused.class,
                                                               refused -> assertThat(refused.cause()).isInstanceOf(ControlLogError.RemoteRefused.class));
            assertThat(outcomes.get(1)
                               .isLocalOnly()).as("the kind's refusal is final, not LocalOnly").isFalse();
            assertThat(outcomes.get(2)).isEqualTo(new Admitted(1L));
            assertThat(ringHead(owner)).as("only the admitted records were appended").isEqualTo(1L);
        }
    }

    /// Behaviour 13 (R3.2(c)). A batch of three is one forwarded message, one append task on the owner's queue and one run
    /// append (so one group-commit fsync). Mutation: append the batch one record at a time.
    @Test
    void forwardedBatch_isOneQueueItemOneRunAppend() {
        try (var owner = owner(1, 1)) {
            awaitAdmissionReady(owner);
            var messages = new AtomicInteger();
            var client = clientOf(owner, messages);
            var items = owner.state()
                             .appendItemCount();
            var runs = owner.state()
                            .runAppendCount();

            var outcomes = await(client.appendControlRemote(SELF,
                                                            STREAM,
                                                            PARTITION,
                                                            List.of(record("a", 1, 1), record("b", 1, 1), record("c", 1, 1))));

            assertThat(outcomes).containsExactly(new Admitted(0L), new Admitted(1L), new Admitted(2L));
            assertThat(messages.get()).as("forwarded messages").isEqualTo(1);
            assertThat(owner.state()
                            .appendItemCount() - items).as("append items on the owner's queue")
                                                       .isEqualTo(1L);
            assertThat(owner.state()
                            .runAppendCount() - runs).as("run appends").isEqualTo(1L);
        }
    }

    /// Behaviour 19 (the #1391 pin on the ControlLog arm). The owner has not materialized the stream, so the confirmation
    /// factor it reports before the append is 0; the forwarded batch materializes it from the committed config (CF 2) and
    /// must then wait for the peer. Mutation: feed the barrier from the read taken before the append.
    @Test
    void controlForward_unmaterializedOwner_awaitsBarrier() throws Exception {
        var replication = replicationManager(SELF, ControlLogFixture.registry(2));
        var manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);

        manager.committedConfigSource(name -> Option.some(ControlLogFixture.streamConfig(2, 2))
                                                    .filter(_ -> name.equals(STREAM)));
        try (var owner = ControlLogFixture.node(manager, replication, MirrorView.EMPTY, 1L, ReplicaSetController.Role.OWNER)) {
            assertThat(manager.confirmationFactorFor(STREAM)).as("control: not materialized here").isZero();
            awaitAdmissionReady(owner);
            var responses = new CopyOnWriteArrayList<ControlAppendForwardResponse>();
            var handler = StreamForwardHandler.streamForwardHandler(SELF,
                                                                    manager,
                                                                    AcceptingStreamForwardTransport.accepting((_, message) -> collect(responses, message)),
                                                                    owner.controlLog());

            handler.onControlAppendForward(ControlAppendForward.controlAppendForward(SENDER,
                                                                                     "c-1",
                                                                                     STREAM,
                                                                                     PARTITION,
                                                                                     List.of(ControlRecordDto.fromRecord(record("k", 1, 1)))));

            awaitTrue("materialized and appended", () -> ringHead(owner) == 0L);
            Thread.sleep(300);
            assertThat(responses).as("not acknowledged before the peer").isEmpty();

            ack(owner, PEER, 0L);

            awaitTrue("answered", () -> !responses.isEmpty());
            assertThat(responses.getFirst()
                                .outcomes()).extracting(ControlOutcomeDto::kind)
                                            .containsExactly(ControlOutcomeDto.Kind.ADMITTED);
        }
    }

    /// A client whose transport delivers a forwarded batch to `owner`'s handler, in process, and the answer back.
    private static StreamForwardClient clientOf(ControlLogFixture.Node owner, AtomicInteger messages) {
        var clientHolder = new CopyOnWriteArrayList<StreamForwardClient>();
        var handler = StreamForwardHandler.streamForwardHandler(SELF,
                                                                owner.manager(),
                                                                AcceptingStreamForwardTransport.accepting((_, message) -> answer(clientHolder, message)),
                                                                owner.controlLog());
        var client = StreamForwardClient.streamForwardClient(SENDER, AcceptingStreamForwardTransport.accepting((_, message) -> deliver(handler, messages, message)));

        clientHolder.add(client);

        return client;
    }

    private static void deliver(StreamForwardHandler handler, AtomicInteger messages, StreamForwardMessage message) {
        if (message instanceof ControlAppendForward batch) {
            messages.incrementAndGet();
            handler.onControlAppendForward(batch);
        }
    }

    private static void answer(List<StreamForwardClient> client, StreamForwardMessage message) {
        if (message instanceof ControlAppendForwardResponse response) {
            client.getFirst()
                  .onControlAppendForwardResponse(response);
        }
    }

    private static void collect(List<ControlAppendForwardResponse> responses, StreamForwardMessage message) {
        if (message instanceof ControlAppendForwardResponse response) {
            responses.add(response);
        }
    }
}
