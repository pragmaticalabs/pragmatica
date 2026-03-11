package org.pragmatica.aether.dht;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.dht.ReplicationCooldown.replicationCooldown;

class ReplicationCooldownTest {
    private ReplicationCooldown cooldown;

    @AfterEach
    void tearDown() {
        if (cooldown != null) {
            cooldown.stop();
        }
    }

    @Nested
    class StartOperation {
        @Test
        void start_completesAfterDelay_resolvesPromise() {
            cooldown = replicationCooldown(50, 10_000);

            var result = cooldown.start().await();

            result.onFailure(_ -> fail("Expected success"));
        }

        @Test
        void start_afterCompletion_isCompleteReturnsTrue() {
            cooldown = replicationCooldown(50, 10_000);

            cooldown.start().await();

            assertThat(cooldown.isComplete()).isTrue();
        }
    }

    @Nested
    class IsCompleteState {
        @Test
        void isComplete_beforeStart_returnsFalse() {
            cooldown = replicationCooldown(5_000, 10_000);

            assertThat(cooldown.isComplete()).isFalse();
        }
    }

    @Nested
    class StopOperation {
        @Test
        void stop_beforeCompletion_preventsCompletion() {
            cooldown = replicationCooldown(5_000, 10_000);
            cooldown.start();
            cooldown.stop();

            assertThat(cooldown.isComplete()).isFalse();
        }
    }

    @Nested
    class DefaultFactory {
        @Test
        void replicationCooldown_defaults_createsInstance() {
            cooldown = replicationCooldown();

            assertThat(cooldown.isComplete()).isFalse();
        }
    }
}
