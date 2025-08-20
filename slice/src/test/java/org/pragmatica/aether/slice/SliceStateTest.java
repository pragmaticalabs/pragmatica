package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class SliceStateTest {

    @Test
    void transitional_states_have_timeouts() {
        assertThat(SliceState.LOADING.hasTimeout()).isTrue();
        assertThat(SliceState.LOADING.timeout()).isEqualTo(timeSpan(2).minutes());

        assertThat(SliceState.ACTIVATING.hasTimeout()).isTrue();
        assertThat(SliceState.ACTIVATING.timeout()).isEqualTo(timeSpan(1).minutes());

        assertThat(SliceState.DEACTIVATING.hasTimeout()).isTrue();
        assertThat(SliceState.DEACTIVATING.timeout()).isEqualTo(timeSpan(30).seconds());

        assertThat(SliceState.UNLOADING.hasTimeout()).isTrue();
        assertThat(SliceState.UNLOADING.timeout()).isEqualTo(timeSpan(2).minutes());
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
        assertThat(SliceState.ACTIVATING.canTransitionTo(SliceState.ACTIVE)).isTrue();
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
        assertThat(SliceState.LOAD.nextState()).isEqualTo(SliceState.LOADING);
        assertThat(SliceState.LOADING.nextState()).isEqualTo(SliceState.LOADED);
        assertThat(SliceState.LOADED.nextState()).isEqualTo(SliceState.ACTIVATE);
        assertThat(SliceState.ACTIVATE.nextState()).isEqualTo(SliceState.ACTIVATING);
        assertThat(SliceState.ACTIVATING.nextState()).isEqualTo(SliceState.ACTIVE);
        assertThat(SliceState.ACTIVE.nextState()).isEqualTo(SliceState.DEACTIVATE);
        assertThat(SliceState.DEACTIVATE.nextState()).isEqualTo(SliceState.DEACTIVATING);
        assertThat(SliceState.DEACTIVATING.nextState()).isEqualTo(SliceState.LOADED);
        assertThat(SliceState.FAILED.nextState()).isEqualTo(SliceState.UNLOAD);
        assertThat(SliceState.UNLOAD.nextState()).isEqualTo(SliceState.UNLOADING);
    }

    @Test
    void unloading_is_terminal_state() {
        assertThat(SliceState.UNLOADING.validTransitions()).isEmpty();

        assertThatThrownBy(() -> SliceState.UNLOADING.nextState())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("UNLOADING is terminal state");
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
                  .onSuccessRun(() -> org.junit.jupiter.api.Assertions.fail("Should have failed"))
                  .onFailure(cause -> assertThat(cause.message()).contains("Unknown slice state"));

        SliceState.sliceState("")
                  .onSuccessRun(() -> org.junit.jupiter.api.Assertions.fail("Should have failed"))
                  .onFailure(cause -> assertThat(cause.message()).contains("Unknown slice state"));
    }
}