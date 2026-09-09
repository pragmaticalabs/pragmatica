// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class SliceStateTest {

    @Test
    void transitional_states_have_timeouts() {
        assertThat(SliceState.LOADING.hasTimeout()).isTrue();
        assertThat(SliceState.LOADING.timeout()).isEqualTo(some(timeSpan(2).minutes()));

        assertThat(SliceState.ACTIVATING.hasTimeout()).isTrue();
        assertThat(SliceState.ACTIVATING.timeout()).isEqualTo(some(timeSpan(90).seconds()));

        assertThat(SliceState.ROUTING.hasTimeout()).isTrue();
        assertThat(SliceState.ROUTING.timeout()).isEqualTo(some(timeSpan(30).seconds()));

        assertThat(SliceState.DEACTIVATING.hasTimeout()).isTrue();
        assertThat(SliceState.DEACTIVATING.timeout()).isEqualTo(some(timeSpan(30).seconds()));

        assertThat(SliceState.UNLOADING.hasTimeout()).isTrue();
        assertThat(SliceState.UNLOADING.timeout()).isEqualTo(some(timeSpan(2).minutes()));
    }

    @Test
    void stable_states_have_no_timeouts() {
        assertThat(SliceState.LOAD.hasTimeout()).isFalse();
        assertThat(SliceState.LOADED.hasTimeout()).isFalse();
        assertThat(SliceState.ACTIVATE.hasTimeout()).isFalse();
        assertThat(SliceState.ACTIVE.hasTimeout()).isFalse();
        assertThat(SliceState.DEACTIVATE.hasTimeout()).isFalse();
        assertThat(SliceState.FAILED.hasTimeout()).isFalse();
        assertThat(SliceState.UNLOAD.hasTimeout()).isFalse();
    }

    @Test
    void transitional_states_are_identified_correctly() {
        assertThat(SliceState.LOADING.isTransitional()).isTrue();
        assertThat(SliceState.ACTIVATING.isTransitional()).isTrue();
        assertThat(SliceState.ROUTING.isTransitional()).isTrue();
        assertThat(SliceState.DEACTIVATING.isTransitional()).isTrue();
        assertThat(SliceState.UNLOADING.isTransitional()).isTrue();

        assertThat(SliceState.LOAD.isTransitional()).isFalse();
        assertThat(SliceState.LOADED.isTransitional()).isFalse();
        assertThat(SliceState.ACTIVATE.isTransitional()).isFalse();
        assertThat(SliceState.ACTIVE.isTransitional()).isFalse();
        assertThat(SliceState.DEACTIVATE.isTransitional()).isFalse();
        assertThat(SliceState.FAILED.isTransitional()).isFalse();
        assertThat(SliceState.UNLOAD.isTransitional()).isFalse();
    }

    @Test
    void valid_transitions_follow_lifecycle() {
        assertThat(SliceState.LOAD.canTransitionTo(SliceState.LOADING)).isTrue();
        assertThat(SliceState.LOADING.canTransitionTo(SliceState.LOADED)).isTrue();
        assertThat(SliceState.LOADED.canTransitionTo(SliceState.ACTIVATE)).isTrue();
        assertThat(SliceState.ACTIVATE.canTransitionTo(SliceState.ACTIVATING)).isTrue();
        assertThat(SliceState.ACTIVATING.canTransitionTo(SliceState.ROUTING)).isTrue();
        assertThat(SliceState.ACTIVATING.canTransitionTo(SliceState.ACTIVE)).isTrue();
        assertThat(SliceState.ROUTING.canTransitionTo(SliceState.ACTIVE)).isTrue();
        assertThat(SliceState.ACTIVE.canTransitionTo(SliceState.DEACTIVATE)).isTrue();
        assertThat(SliceState.DEACTIVATE.canTransitionTo(SliceState.DEACTIVATING)).isTrue();
        assertThat(SliceState.DEACTIVATING.canTransitionTo(SliceState.LOADED)).isTrue();
        assertThat(SliceState.FAILED.canTransitionTo(SliceState.UNLOAD)).isTrue();
        assertThat(SliceState.UNLOAD.canTransitionTo(SliceState.UNLOADING)).isTrue();
    }

    @Test
    void invalid_transitions_are_rejected() {
        assertThat(SliceState.LOAD.canTransitionTo(SliceState.ACTIVE)).isFalse();
        assertThat(SliceState.LOADING.canTransitionTo(SliceState.ACTIVATING)).isFalse();
        assertThat(SliceState.ACTIVE.canTransitionTo(SliceState.LOADING)).isFalse();
        assertThat(SliceState.UNLOADING.canTransitionTo(SliceState.ACTIVE)).isFalse();
    }

    @Test
    void next_state_progression_works_correctly() {
        assertThat(SliceState.LOAD.nextState().unwrap()).isEqualTo(SliceState.LOADING);
        assertThat(SliceState.LOADING.nextState().unwrap()).isEqualTo(SliceState.LOADED);
        assertThat(SliceState.LOADED.nextState().unwrap()).isEqualTo(SliceState.ACTIVATE);
        assertThat(SliceState.ACTIVATE.nextState().unwrap()).isEqualTo(SliceState.ACTIVATING);
        assertThat(SliceState.ACTIVATING.nextState().unwrap()).isEqualTo(SliceState.ROUTING);
        assertThat(SliceState.ROUTING.nextState().unwrap()).isEqualTo(SliceState.ACTIVE);
        assertThat(SliceState.ACTIVE.nextState().unwrap()).isEqualTo(SliceState.DEACTIVATE);
        assertThat(SliceState.DEACTIVATE.nextState().unwrap()).isEqualTo(SliceState.DEACTIVATING);
        assertThat(SliceState.DEACTIVATING.nextState().unwrap()).isEqualTo(SliceState.LOADED);
        assertThat(SliceState.FAILED.nextState().unwrap()).isEqualTo(SliceState.UNLOAD);
        assertThat(SliceState.UNLOAD.nextState().unwrap()).isEqualTo(SliceState.UNLOADING);
    }

    @Test
    void unloading_is_terminal_state() {
        assertThat(SliceState.UNLOADING.validTransitions()).isEmpty();

        SliceState.UNLOADING.nextState()
                            .onSuccessRun(Assertions::fail)
                            .onFailure(cause -> assertThat(cause.message()).contains(
                                    "Cannot transition from UNLOADING terminal state"));
    }

    @Test
    void slice_state_parsing_is_case_insensitive() {
        assertThat(SliceState.sliceState("ACTIVE").unwrap()).isEqualTo(SliceState.ACTIVE);
        assertThat(SliceState.sliceState("active").unwrap()).isEqualTo(SliceState.ACTIVE);
        assertThat(SliceState.sliceState("Active").unwrap()).isEqualTo(SliceState.ACTIVE);
        assertThat(SliceState.sliceState("AcTiVe").unwrap()).isEqualTo(SliceState.ACTIVE);
    }

    @Test
    void slice_state_parsing_rejects_invalid_values() {
        SliceState.sliceState("INVALID")
                  .onSuccessRun(Assertions::fail)
                  .onFailure(cause -> assertThat(cause.message()).contains("Unknown slice state"));

        SliceState.sliceState("")
                  .onSuccessRun(Assertions::fail)
                  .onFailure(cause -> assertThat(cause.message()).contains("Unknown slice state"));
    }

    /// #964: `UNKNOWN` is the wire sentinel, and every property asserted here is a way of saying it is
    /// INERT. It is deliberately not merely "another state": each of these would otherwise let a value
    /// this node could not decode drive real behaviour.
    @org.junit.jupiter.api.Nested
    class UnknownSentinelIsInert {

        /// No timeout means not transitional, which is what keeps `StuckTransitionalRemediator` from
        /// force-unloading a slice whose state was authored by a node running a newer SliceState.
        @Test
        void unknown_hasNoTimeoutAndIsNotTransitional() {
            assertThat(SliceState.UNKNOWN.hasTimeout()).isFalse();
            assertThat(SliceState.UNKNOWN.isTransitional()).isFalse();
        }

        @Test
        void unknown_isNotInProgress() {
            assertThat(SliceState.UNKNOWN.isInProgress()).isFalse();
        }

        /// Nothing can be driven out of a state this node cannot interpret.
        @Test
        void unknown_hasNoValidTransitions() {
            assertThat(SliceState.UNKNOWN.validTransitions()).isEmpty();

            for (var target : SliceState.values()) {
                assertThat(SliceState.UNKNOWN.canTransitionTo(target)).isFalse();
            }
        }

        @Test
        void unknown_hasNoNextState() {
            assertThat(SliceState.UNKNOWN.nextState().isFailure()).isTrue();
        }

        /// The sentinel is a DECODE artifact, not an addressable state. Keeping it out of the string
        /// map is what stops an operator or a config file from asking for it by name — otherwise
        /// "UNKNOWN" would become a writable slice state through every text-parsing path.
        @Test
        void unknown_isNotReachableByName() {
            assertThat(SliceState.sliceState("UNKNOWN").isFailure()).isTrue();
        }

        /// The control for the test above: a real state IS reachable by name, so the failure is about
        /// UNKNOWN specifically rather than a broken parser.
        @Test
        void aRealState_isStillReachableByName() {
            assertThat(SliceState.sliceState("ACTIVE").isSuccess()).isTrue();
        }
    }
}
