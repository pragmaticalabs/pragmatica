// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.entity;

import java.time.Duration;
import java.util.UUID;

import org.pragmatica.aether.resource.Mutator;
import org.pragmatica.aether.resource.entity.DurableEntity;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Verify;


/// Durable-entity fixture (#345 increment I0) — the first slice in the repository that DECLARES a
/// [DurableEntity] resource.
///
/// Until this module existed, no `resources.toml` anywhere named a durable entity, no example used
/// one, and no test slice bound one. The entire `resource/durable-entity` module — the interface,
/// the SPI factory, and all three implementations — was therefore unreachable from any running
/// node, and every build stayed green regardless of whether it worked. That is precisely the
/// silently-inert shape this project has shipped before, and it makes increments I1–I6
/// unfalsifiable: there is nothing to break.
///
/// ## What this slice is for
///
/// It is a REPORTER, not an assertion. Every operation returns an [EntityResponse] naming what the
/// runtime actually did — including failures, which are reported as data
/// ([EntityResponse#failureType]) rather than thrown or swallowed. A test can then assert on the
/// entity's real behavior instead of on the absence of an exception.
///
/// [#scheduleTimer] exists for exactly this reason. Durable timers are real on the fenced-log backing a
/// node provisions (#345 I4): the call answers with a token, the pending timer is a record in the entity's
/// own log, and the fire applies [OrderCommand.Expire] to the state through the same path an external
/// update takes. `DurableEntityFactory` provisions only that backing, so `TimerNotSupported` — the answer
/// of the HA-only in-memory cut (`InMemoryDurableEntity`, `FencedDurableEntity`), which unit tests
/// construct directly — cannot arrive here. The fixture reports whatever answer it receives as data, so a
/// test that ever saw one would name it rather than hang waiting for a fire.
///
/// ## Timer effects are COUNTED, not merely stamped
///
/// [OrderState#expiries] counts applications of [OrderCommand.Expire]. A status flip alone would be
/// idempotent — "expired" set twice reads exactly like "expired" set once — so it could not tell a timer
/// that fired once from one that fired twice, which is the whole question a re-sent schedule asks. The
/// counter is what makes exactly-once an assertion instead of a hope.
///
/// ## Instance identity is load-bearing
///
/// [EntityResponse#instance] is a per-slice-instance id minted at construction. It is what makes
/// the cross-node assertions non-vacuous: an "absent" answer from a second node proves the entity
/// holds no shared state ONLY if that answer demonstrably came from a DIFFERENT slice instance.
/// Without it, a routing quirk that sent both requests to the same node would read identically.
///
/// ## What the fixture makes measurable
///
/// The provisioned entity is `DurableEntityFactory`'s product: the fenced-log
/// `PartitionFencedDurableEntity`. The factory REFUSES to provision without every fence collaborator, so
/// a slice that loads got a fully wired fenced entity and there is no unfenced shape to degrade into.
/// `DurableEntityConfig.replicationFactor` reaches the log substrate's `ensureLog`, so the blueprint's
/// declared `replication_factor` buys that many copies of each partition. What a cross-node test can
/// therefore observe through this fixture: one create for a key is admitted cluster-wide while every
/// other node relays and surfaces the owner's typed refusal, a read is served by more nodes than the
/// owner alone, and state outlives the node that owned it.
@Slice
public interface EntitySlice {
    record CreateRequest(String orderId, String status, int amount) {
        OrderState initialState() {
            return new OrderState(status, amount, 0);
        }
    }

    record KeyRequest(String orderId) {}

    record UpdateRequest(String orderId, int amount) {}

    /// The schedule-timer request, with BOTH knobs a durability gate needs and neither of them mandatory.
    ///
    /// `delayMillis` is caller-controlled because a hardcoded delay serves exactly one of the two audiences
    /// this fixture has. The five-minute default is long enough that an ordinary API-surface test cannot
    /// have its timer fire underneath it; a gate that must watch a timer survive a handover or a restart
    /// needs a delay tuned to the disruption it is testing, and no single constant is both. Absent or
    /// non-positive falls back to [#DEFAULT_DELAY], so a caller that says nothing gets exactly the old
    /// behaviour.
    ///
    /// `token` is caller-controlled because it is the ONLY way to present the same schedule twice. Left
    /// empty, the entity mints one per call — which is what an ordinary caller wants, and what makes each
    /// node's schedule its own. Supplied, the same token re-sent is the same schedule: the owner recognises
    /// it as already pending, appends nothing, and answers with it, so the effect lands once however many
    /// times the request is repeated. That is the property a lost acknowledgement makes load-bearing, and
    /// it cannot be exercised through an entry that mints internally.
    ///
    /// Both are declared as REFERENCE types, and `delayMillis` deliberately so. Jackson refuses an ABSENT
    /// creator property of primitive type outright — `Type mismatch: expected long, got unknown`, a 500
    /// rather than a default — so a `long` here would have made the field mandatory in fact while reading
    /// as optional. A boxed `Long` arrives null when the field is omitted, which is what an omitted field
    /// means, and [Option#option] mints the optionality at the boundary where the nullable actually is.
    record TimerRequest(String orderId, Long delayMillis, String token) {
        /// The pre-I4 hardcoded value, kept as the default so every caller that supplies no delay is
        /// unchanged by the field's arrival.
        private static final Duration DEFAULT_DELAY = Duration.ofMinutes(5);

        Duration delay() {
            return Option.option(delayMillis)
                         .filter(Verify.Is::positive)
                         .map(Duration::ofMillis)
                         .or(DEFAULT_DELAY);
        }

        /// An absent JSON field arrives as null here — the HTTP boundary is exactly where [Option] is minted
        /// from a nullable, so nothing downstream has to ask.
        Option<DurableEntity.TimerToken> callerToken() {
            return Option.option(token)
                         .filter(Verify.Is::present)
                         .map(DurableEntity.TimerToken::timerToken);
        }
    }

    /// The keyspace's command hierarchy — the transitions this slice can apply to an [OrderState].
    ///
    /// SEALED with record variants on purpose. A lambda has no name, so it cannot be persisted for a
    /// durable timer nor forwarded to a partition owner; a record's components ARE its arguments, and
    /// each variant is its own registered codec type, so the tag is already the discriminator. Being
    /// sealed is also what stops a lambda being passed here at all.
    ///
    /// Declared as the `C` type argument of [DurableEntity] so the slice processor collects it: the
    /// generator walks the type arguments of a resource-qualified parameter, and a command type that
    /// is not a type argument is invisible to codec generation.
    sealed interface OrderCommand extends Mutator<OrderState> {
        record SetAmount(int amount) implements OrderCommand {
            @Override
            public OrderState apply(OrderState state) {
                return state.withAmount(amount);
            }
        }

        /// What a durable timer fires (#351). Real rather than an identity stub, so it keeps meaning
        /// something once timers persist their action.
        record Expire() implements OrderCommand {
            @Override
            public OrderState apply(OrderState state) {
                return state.expired();
            }
        }
    }

    /// The entity state. Deliberately never crosses the HTTP boundary — [EntityResponse] carries its
    /// fields flattened — so this fixture measures the ENTITY, never the slice codec. A codec defect
    /// would otherwise be indistinguishable from an entity defect.
    record OrderState(String status, int amount, int expiries) {
        OrderState withAmount(int newAmount) {
            return new OrderState(status, newAmount, expiries);
        }

        /// The pure mutator a durable timer applies on fire. It COUNTS: flipping the status alone would be
        /// idempotent, so two fires would be indistinguishable from one and no test could pin exactly-once.
        /// `amount` is deliberately untouched, so a fire is also distinguishable from an [OrderCommand.SetAmount].
        OrderState expired() {
            return new OrderState("expired", amount, expiries + 1);
        }
    }

    /// A single flat shape for every outcome, so a test asserts on `outcome`/`failureType` rather
    /// than on HTTP status codes or error prose.
    ///
    /// `failureType` is the [Cause]'s simple class name — `EntityAlreadyExists`, `EntityNotFound`,
    /// `NotCurrentOwner`. Asserting on the TYPE rather than on `failure` (the rendered message) keeps
    /// the proof anchored to the sealed cause hierarchy instead of to wording that may be reworded.
    ///
    /// `token` carries a scheduled timer's handle in its own component rather than borrowing `status`: a
    /// gate that re-sends a schedule asserts the SAME token comes back, and reading that out of a field
    /// whose other meaning is the order's lifecycle would make the assertion unreadable.
    record EntityResponse(String instance,
                          String outcome,
                          String failureType,
                          String failure,
                          String status,
                          int amount,
                          int expiries,
                          String token) {
        static EntityResponse created(String instance, OrderState state) {
            return succeeded(instance, "created", state);
        }

        static EntityResponse found(String instance, OrderState state) {
            return succeeded(instance, "found", state);
        }

        static EntityResponse updated(String instance, OrderState state) {
            return succeeded(instance, "updated", state);
        }

        static EntityResponse absent(String instance) {
            return new EntityResponse(instance, "absent", "", "", "", 0, 0, "");
        }

        static EntityResponse deleted(String instance) {
            return new EntityResponse(instance, "deleted", "", "", "", 0, 0, "");
        }

        static EntityResponse scheduled(String instance, DurableEntity.TimerToken token) {
            return new EntityResponse(instance, "scheduled", "", "", "", 0, 0, token.value());
        }

        static EntityResponse failed(String instance, Cause cause) {
            return new EntityResponse(instance,
                                      "failed",
                                      cause.getClass().getSimpleName(),
                                      cause.message(),
                                      "",
                                      0,
                                      0,
                                      "");
        }

        private static EntityResponse succeeded(String instance, String outcome, OrderState state) {
            return new EntityResponse(instance, outcome, "", "", state.status(), state.amount(), state.expiries(), "");
        }
    }

    Promise<EntityResponse> create(CreateRequest request);
    Promise<EntityResponse> get(KeyRequest request);
    Promise<EntityResponse> update(UpdateRequest request);
    Promise<EntityResponse> delete(KeyRequest request);
    /// Schedules a durable timer that applies [OrderCommand.Expire] when it fires, and reports the token as
    /// data. Both the delay and the token come from the caller (see [TimerRequest]); the effect of a fire is
    /// visible through the ordinary [#get] path as an incremented [OrderState#expiries], so a test asserts on
    /// entity state rather than on logs.
    Promise<EntityResponse> scheduleTimer(TimerRequest request);

    static EntitySlice entitySlice(@OrderEntity DurableEntity<String, OrderState, OrderCommand> orders) {
        return new entitySlice(orders,
                               UUID.randomUUID().toString());
    }

    record entitySlice(DurableEntity<String, OrderState, OrderCommand> orders, String instance) implements EntitySlice {
        /// The command every timer in this fixture fires. A component-less record, hence immutable and
        /// shareable — there is nothing per-call to build.
        private static final OrderCommand EXPIRE = new OrderCommand.Expire();

        @Override
        public Promise<EntityResponse> create(CreateRequest request) {
            return orders.create(request.orderId(),
                                 request.initialState())
                         .map(state -> EntityResponse.created(instance, state))
                         .recover(this::failure);
        }

        @Override
        public Promise<EntityResponse> get(KeyRequest request) {
            return orders.get(request.orderId())
                         .map(this::lookup)
                         .recover(this::failure);
        }

        @Override
        public Promise<EntityResponse> update(UpdateRequest request) {
            return orders.update(request.orderId(),
                                 new OrderCommand.SetAmount(request.amount()))
                         .map(state -> EntityResponse.updated(instance, state))
                         .recover(this::failure);
        }

        @Override
        public Promise<EntityResponse> delete(KeyRequest request) {
            return orders.delete(request.orderId())
                         .map(_ -> EntityResponse.deleted(instance))
                         .recover(this::failure);
        }

        @Override
        public Promise<EntityResponse> scheduleTimer(TimerRequest request) {
            return scheduled(request).map(token -> EntityResponse.scheduled(instance, token))
                            .recover(this::failure);
        }

        /// Routes to the entry the caller's request selects: a supplied token re-presents a schedule that
        /// may already be pending (the owner answers it and appends nothing), an absent one asks the entity
        /// to mint. Two entries rather than one because only the token-carrying entry can be retried.
        private Promise<DurableEntity.TimerToken> scheduled(TimerRequest request) {
            return request.callerToken()
                          .fold(() -> orders.scheduleTimer(request.orderId(),
                                                           request.delay(),
                                                           EXPIRE),
                                token -> orders.scheduleTimer(request.orderId(),
                                                              request.delay(),
                                                              EXPIRE,
                                                              token));
        }

        private EntityResponse lookup(Option<OrderState> state) {
            return state.fold(() -> EntityResponse.absent(instance), found -> EntityResponse.found(instance, found));
        }

        /// Where every `recover(this::failure)` in this record lands, and the reason none of them is a
        /// swallowed failure: the cause is not absorbed, it is TRANSPOSED into the response payload. Its
        /// type and message both survive into [EntityResponse#failureType] and [EntityResponse#failure], so
        /// a caller learns strictly more than a thrown exception would have told it. Nothing is dropped and
        /// nothing is retried — this is a REPORTER, and reporting the refusal is the product.
        private EntityResponse failure(Cause cause) {
            return EntityResponse.failed(instance, cause);
        }
    }
}
