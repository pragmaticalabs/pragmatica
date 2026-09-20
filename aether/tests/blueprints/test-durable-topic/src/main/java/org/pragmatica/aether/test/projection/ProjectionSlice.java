// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.projection;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.projection.InMemoryProjectionClaims;
import org.pragmatica.aether.resource.projection.InMemoryProjectionStore;
import org.pragmatica.aether.resource.projection.Projection;
import org.pragmatica.aether.resource.projection.ProjectionRuntime;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.aether.slice.topic.MessageContext;
import org.pragmatica.aether.slice.topic.Topic;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;


/// Durable projection fixture (#1333): a [Projection] over the durable topic `projection-events`,
/// ATTACHED to the node through the provisioned [ProjectionRuntime], so the runtime's commit hook,
/// rewind and rebuild route reach it. Everything the forge test asserts is observable over this slice's
/// HTTP surface plus the management API's `GET …/topics/…/groups`.
///
/// The fold is order-sensitive — `state * 10 + seq` — so the model after seq 1..6 reads `123456`, and
/// after a rebuild that dead-letters seq 3 it reads `12456`: one digit per event applied, in order,
/// exactly once. The subscriber records every attempt per seq, which is the dead-letter DETECTOR the
/// forge test uses: the `.dlq` stream is not readable through the catalog routes, but a dead-lettered
/// event is exactly one that reached the durable retry budget (5 attempts), so "exactly one dead
/// letter" is "exactly one seq with 5 attempts".
///
/// The store and claims are in-process ([InMemoryProjectionStore]): coherent here because the topic has
/// ONE partition and so one assignee, which is the scope those types state.
@Slice
public interface ProjectionSlice {
    Topic<ProjectionEvent> PROJECTION_EVENTS = Topic.of("projection-events", ProjectionEvent.class);
    String MODEL_KEY = "model";

    record PublishRequest(int seq) {
        public static Result<PublishRequest> publishRequest(int seq) {
            return success(new PublishRequest(seq));
        }
    }

    /// `seq < 0` disarms.
    record ArmPoisonRequest(int seq) {
        public static Result<ArmPoisonRequest> armPoisonRequest(int seq) {
            return success(new ArmPoisonRequest(seq));
        }
    }

    record StatusRequest() {}

    record PublishResponse(String status) {
        public static PublishResponse published() {
            return new PublishResponse("published");
        }

        public static PublishResponse armed(int seq) {
            return new PublishResponse("poison=" + seq);
        }
    }

    record AttemptCount(int seq, int attempts) {}

    /// `model` is the folded read model on THIS node (`-1` when absent — a node that hosts the slice but
    /// not the group's consumer never folds anything); `generation`/`live` are the store's replay state.
    record ProjectionStatus(long model, long generation, boolean live, int poisonSeq, List<AttemptCount> attempts) {}

    Promise<PublishResponse> publish(PublishRequest request);
    Promise<PublishResponse> armPoison(ArmPoisonRequest request);
    Promise<ProjectionStatus> status(StatusRequest request);

    /// The durable subscriber the projection rides — deliberately unroutable, so only the runtime's
    /// dispatch reaches it. The context-carrying shape: the projection's §8 guard needs the message id
    /// and the delivery position.
    @ProjectionEventSubscriber
    Promise<Unit> onProjectionEvent(ProjectionEvent event, MessageContext context);

    static ProjectionSlice projectionSlice(@ProjectionEventPublisher Publisher<ProjectionEvent> publisher,
                                           @ProjectionEventRuntime ProjectionRuntime runtime) {
        var store = InMemoryProjectionStore.<Long> inMemoryProjectionStore();
        var projection = runtime.attach(Projection.of(PROJECTION_EVENTS)
                                                  .into(store, _ -> MODEL_KEY)
                                                  .apply(ProjectionSlice::fold)
                                                  .withClaims(InMemoryProjectionClaims.inMemoryProjectionClaims(),
                                                              TimeSpan.timeSpan(30).seconds()))
                               .unwrap();

        return new projectionSlice(publisher, projection, store, new AtomicInteger(-1), new ConcurrentHashMap<>());
    }

    static Long fold(Option<Long> current, ProjectionEvent event) {
        return current.or(0L) * 10 + event.seq();
    }

    record projectionSlice(Publisher<ProjectionEvent> publisher,
                           Projection<Long, ProjectionEvent> projection,
                           InMemoryProjectionStore<Long> store,
                           AtomicInteger poisonSeq,
                           Map<Integer, AtomicInteger> attempts) implements ProjectionSlice {
        @Override
        public Promise<PublishResponse> publish(PublishRequest request) {
            return publisher.publish(new ProjectionEvent(request.seq()))
                            .map(_ -> PublishResponse.published());
        }

        @Override
        public Promise<PublishResponse> armPoison(ArmPoisonRequest request) {
            poisonSeq.set(request.seq());

            return Promise.success(PublishResponse.armed(request.seq()));
        }

        @Override
        public Promise<ProjectionStatus> status(StatusRequest request) {
            return store.read(MODEL_KEY)
                        .flatMap(model -> store.replayStatus()
                                               .map(replay -> new ProjectionStatus(model.or(-1L),
                                                                                   replay.generation(),
                                                                                   replay.isLive(),
                                                                                   poisonSeq.get(),
                                                                                   attemptCounts())));
        }

        private List<AttemptCount> attemptCounts() {
            return attempts.entrySet()
                           .stream()
                           .sorted(Map.Entry.comparingByKey())
                           .map(entry -> new AttemptCount(entry.getKey(),
                                                          entry.getValue().get()))
                           .toList();
        }

        @Override
        public Promise<Unit> onProjectionEvent(ProjectionEvent event, MessageContext context) {
            attempts.computeIfAbsent(event.seq(), _ -> new AtomicInteger()).incrementAndGet();

            return event.seq() == poisonSeq.get()
                   ? new PoisonRefused(event.seq()).promise()
                   : projection.onEvent(event, context);
        }
    }

    /// The armed refusal: named so the dead-letter envelope's `lastFailureCause` is attributable.
    record PoisonRefused(int seq) implements Cause {
        @Override
        public String message() {
            return "test-durable-topic projection fixture refuses seq " + seq + " (poison armed)";
        }
    }
}
