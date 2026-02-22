package org.pragmatica.aether.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.controller.RollbackManager.RollbackDecision;
import org.pragmatica.aether.controller.RollbackManager.RollbackError;
import org.pragmatica.aether.controller.RollbackManager.RollbackState;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class RollbackManagerTest {

    private static final ArtifactBase TEST_BASE = Artifact.artifact("org.test:my-slice:1.0.0").unwrap().base();
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final Version V2 = Version.version("2.0.0").unwrap();
    private static final Version V3 = Version.version("3.0.0").unwrap();

    private RollbackConfig config;

    @BeforeEach
    void setUp() {
        config = RollbackConfig.rollbackConfig(true, true, 300, 2).unwrap();
    }

    @Nested
    class RollbackStateCreation {
        @Test
        void initial_createsStateWithNoPreviousVersion() {
            var state = RollbackState.initial(TEST_BASE, V1);

            assertThat(state.artifactBase()).isEqualTo(TEST_BASE);
            assertThat(state.currentVersion()).isEqualTo(V1);
            assertThat(state.previousVersion().isEmpty()).isTrue();
            assertThat(state.rollbackCount()).isEqualTo(0);
        }

        @Test
        void fromKVStore_createsStateWithPreviousVersion() {
            var state = RollbackState.fromKVStore(TEST_BASE, V1, V2);

            assertThat(state.previousVersion().isPresent()).isTrue();
            assertThat(state.previousVersion().unwrap()).isEqualTo(V1);
            assertThat(state.currentVersion()).isEqualTo(V2);
        }
    }

    @Nested
    class StateTransitions {
        @Test
        void withVersionChange_tracksPreviousVersion() {
            var state = RollbackState.initial(TEST_BASE, V1);

            var updated = state.withVersionChange(V2);

            assertThat(updated.currentVersion()).isEqualTo(V2);
            assertThat(updated.previousVersion().isPresent()).isTrue();
            assertThat(updated.previousVersion().unwrap()).isEqualTo(V1);
        }

        @Test
        void withRollbackCompleted_incrementsCountAndRecordsDetails() {
            var state = RollbackState.fromKVStore(TEST_BASE, V1, V2);

            var updated = state.withRollbackCompleted(V2, V1, 1000L);

            assertThat(updated.rollbackCount()).isEqualTo(1);
            assertThat(updated.lastRollbackTimestamp()).isEqualTo(1000L);
            assertThat(updated.lastRolledBackFrom().isPresent()).isTrue();
            assertThat(updated.lastRolledBackFrom().unwrap()).isEqualTo(V2);
            assertThat(updated.lastRolledBackTo().isPresent()).isTrue();
            assertThat(updated.lastRolledBackTo().unwrap()).isEqualTo(V1);
            assertThat(updated.currentVersion()).isEqualTo(V1);
            // previousVersion is cleared after rollback
            assertThat(updated.previousVersion().isEmpty()).isTrue();
        }

        @Test
        void withReset_clearsCounters() {
            var state = RollbackState.fromKVStore(TEST_BASE, V1, V2)
                                     .withRollbackCompleted(V2, V1, 1000L);

            var reset = state.withReset();

            assertThat(reset.rollbackCount()).isEqualTo(0);
            assertThat(reset.lastRollbackTimestamp()).isEqualTo(0);
            assertThat(reset.lastRolledBackFrom().isEmpty()).isTrue();
            assertThat(reset.lastRolledBackTo().isEmpty()).isTrue();
        }
    }

    @Nested
    class CanRollbackDecision {
        @Test
        void canRollback_withPreviousVersionAndNoCooldown_returnsSuccess() {
            var state = RollbackState.fromKVStore(TEST_BASE, V1, V2);

            var result = state.canRollback(config, System.currentTimeMillis());

            result.onFailure(c -> org.junit.jupiter.api.Assertions.fail(c.message()))
                  .onSuccess(decision -> assertThat(decision.targetVersion()).isEqualTo(V1))
                  .onSuccess(decision -> assertThat(decision.failedVersion()).isEqualTo(V2))
                  .onSuccess(decision -> assertThat(decision.rollbackNumber()).isEqualTo(1));
        }

        @Test
        void canRollback_noPreviousVersion_returnsNoPreviousVersionError() {
            var state = RollbackState.initial(TEST_BASE, V1);

            var result = state.canRollback(config, System.currentTimeMillis());

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            result.onFailure(cause -> assertThat(cause).isEqualTo(RollbackError.General.NO_PREVIOUS_VERSION));
        }

        @Test
        void canRollback_cooldownActive_returnsCooldownError() {
            // State with previous version AND recent rollback timestamp
            var stateForTest = new RollbackState(TEST_BASE,
                                                  Option.some(V1),
                                                  V2,
                                                  0,
                                                  System.currentTimeMillis(),
                                                  Option.some(V2),
                                                  Option.some(V1));

            var result = stateForTest.canRollback(config, System.currentTimeMillis());

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            result.onFailure(cause -> assertThat(cause).isEqualTo(RollbackError.General.COOLDOWN_ACTIVE));
        }

        @Test
        void canRollback_maxRollbacksExceeded_returnsMaxExceededError() {
            var state = new RollbackState(TEST_BASE,
                                           Option.some(V1),
                                           V2,
                                           2,
                                           0,
                                           Option.some(V3),
                                           Option.some(V1));

            var result = state.canRollback(config, System.currentTimeMillis());

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            result.onFailure(cause -> assertThat(cause).isEqualTo(RollbackError.General.MAX_ROLLBACKS_EXCEEDED));
        }
    }

    @Nested
    class StatsConversion {
        @Test
        void toStats_producesCorrectRollbackStats() {
            var state = RollbackState.fromKVStore(TEST_BASE, V1, V2)
                                     .withRollbackCompleted(V2, V1, 5000L);

            var stats = state.toStats();

            assertThat(stats.artifactBase()).isEqualTo(TEST_BASE);
            assertThat(stats.rollbackCount()).isEqualTo(1);
            assertThat(stats.lastRollbackTimestamp()).isEqualTo(5000L);
            assertThat(stats.lastRolledBackFrom().isPresent()).isTrue();
            assertThat(stats.lastRolledBackFrom().unwrap()).isEqualTo(V2);
            assertThat(stats.lastRolledBackTo().isPresent()).isTrue();
            assertThat(stats.lastRolledBackTo().unwrap()).isEqualTo(V1);
        }
    }

    @Nested
    class RollbackDecisionFactory {
        @Test
        void rollbackDecision_createsCorrectValues() {
            var decision = RollbackDecision.rollbackDecision(V1, V2, 3);

            assertThat(decision.targetVersion()).isEqualTo(V1);
            assertThat(decision.failedVersion()).isEqualTo(V2);
            assertThat(decision.rollbackNumber()).isEqualTo(3);
        }
    }

    @Nested
    class DisabledManager {
        @Test
        void disabled_getStats_returnsNone() {
            var manager = RollbackManager.disabled();

            var stats = manager.getStats(TEST_BASE);

            assertThat(stats.isEmpty()).isTrue();
        }

        @Test
        void disabled_resetRollbackCount_doesNotThrow() {
            var manager = RollbackManager.disabled();

            // Should complete without exception
            manager.resetRollbackCount(TEST_BASE);
        }
    }
}
