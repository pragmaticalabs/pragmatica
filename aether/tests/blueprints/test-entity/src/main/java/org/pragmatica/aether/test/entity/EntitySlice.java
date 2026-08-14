// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.entity;

import java.time.Duration;
import java.util.UUID;

import org.pragmatica.aether.resource.entity.DurableEntity;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


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
/// [#scheduleTimer] exists for exactly this reason. Durable timers are unimplemented in every
/// current implementation (spec §4.5, plan Phase 2c), so the honest fixture calls the method and
/// reports the `TimerNotSupported` refusal, rather than omitting the call and leaving the reader to
/// assume timers work.
///
/// ## Instance identity is load-bearing
///
/// [EntityResponse#instance] is a per-slice-instance id minted at construction. It is what makes
/// the cross-node assertions non-vacuous: an "absent" answer from a second node proves the entity
/// holds no shared state ONLY if that answer demonstrably came from a DIFFERENT slice instance.
/// Without it, a routing quirk that sent both requests to the same node would read identically.
///
/// ## Baseline this pins down
///
/// The provisioned entity is today's `DurableEntityFactory` product: a bare in-process map, no
/// ownership fence, no replication, `DurableEntityConfig.replicationFactor` accepted and ignored.
/// The fixture makes that measurable — same key creatable on every node at once, state invisible
/// across nodes — so I1 (fenced entity) and I3 (restart-durable state) have a baseline to flip.
@Slice
public interface EntitySlice {
    record CreateRequest(String orderId, String status, int amount) {
        OrderState initialState() {
            return new OrderState(status, amount);
        }
    }

    record KeyRequest(String orderId) {}

    record UpdateRequest(String orderId, int amount) {}

    /// The entity state. Deliberately never crosses the HTTP boundary — [EntityResponse] carries its
    /// fields flattened — so this fixture measures the ENTITY, never the slice codec. A codec defect
    /// would otherwise be indistinguishable from an entity defect.
    record OrderState(String status, int amount) {
        OrderState withAmount(int newAmount) {
            return new OrderState(status, newAmount);
        }

        /// The pure mutator a durable timer would apply on fire. Unreachable until #351 lands; it
        /// exists so [EntitySlice#scheduleTimer] presents a real `S -> S` rather than an identity
        /// stub that would keep passing after timers become real.
        OrderState expired() {
            return new OrderState("expired", amount);
        }
    }

    /// A single flat shape for every outcome, so a test asserts on `outcome`/`failureType` rather
    /// than on HTTP status codes or error prose.
    ///
    /// `failureType` is the [Cause]'s simple class name — `TimerNotSupported`, `EntityAlreadyExists`,
    /// `EntityNotFound`. Asserting on the TYPE rather than on `failure` (the rendered message) keeps
    /// the proof anchored to the sealed cause hierarchy instead of to wording that may be reworded.
    record EntityResponse(String instance,
                          String outcome,
                          String failureType,
                          String failure,
                          String status,
                          int amount) {
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
            return new EntityResponse(instance, "absent", "", "", "", 0);
        }

        static EntityResponse deleted(String instance) {
            return new EntityResponse(instance, "deleted", "", "", "", 0);
        }

        static EntityResponse scheduled(String instance, DurableEntity.TimerToken token) {
            return new EntityResponse(instance, "scheduled", "", "", token.value(), 0);
        }

        static EntityResponse failed(String instance, Cause cause) {
            return new EntityResponse(instance,
                                      "failed",
                                      cause.getClass().getSimpleName(),
                                      cause.message(),
                                      "",
                                      0);
        }

        private static EntityResponse succeeded(String instance, String outcome, OrderState state) {
            return new EntityResponse(instance, outcome, "", "", state.status(), state.amount());
        }
    }

    Promise<EntityResponse> create(CreateRequest request);

    Promise<EntityResponse> get(KeyRequest request);

    Promise<EntityResponse> update(UpdateRequest request);

    Promise<EntityResponse> delete(KeyRequest request);

    /// Calls [DurableEntity#scheduleTimer] for real and reports the refusal. See the type comment:
    /// omitting this call would let a reader assume timers work.
    Promise<EntityResponse> scheduleTimer(KeyRequest request);

    static EntitySlice entitySlice(@OrderEntity DurableEntity<String, OrderState> orders) {
        return new entitySlice(orders, UUID.randomUUID().toString());
    }

    record entitySlice(DurableEntity<String, OrderState> orders, String instance) implements EntitySlice {
        /// Long enough that the timer cannot plausibly fire during a test, short enough to stay
        /// meaningful once #351 makes it real.
        private static final Duration TIMER_DELAY = Duration.ofMinutes(5);

        @Override
        public Promise<EntityResponse> create(CreateRequest request) {
            return orders.create(request.orderId(), request.initialState())
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
            return orders.update(request.orderId(), state -> state.withAmount(request.amount()))
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
        public Promise<EntityResponse> scheduleTimer(KeyRequest request) {
            return orders.scheduleTimer(request.orderId(), TIMER_DELAY, OrderState::expired)
                         .map(token -> EntityResponse.scheduled(instance, token))
                         .recover(this::failure);
        }

        private EntityResponse lookup(Option<OrderState> state) {
            return state.fold(() -> EntityResponse.absent(instance), found -> EntityResponse.found(instance, found));
        }

        private EntityResponse failure(Cause cause) {
            return EntityResponse.failed(instance, cause);
        }
    }
}
