package org.pragmatica.aether.lb;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.lb.Backend.backend;
import static org.pragmatica.aether.lb.BackendPool.backendPool;

class BackendPoolTest {
    private static final Backend BACKEND_1 = backend("host1", 8080, 5150);
    private static final Backend BACKEND_2 = backend("host2", 8080, 5150);
    private static final Backend BACKEND_3 = backend("host3", 8080, 5150);

    @Nested
    class HealthTrackingTests {
        @Test
        void healthy_initialState_allUnhealthy() {
            var pool = backendPool(List.of(BACKEND_1, BACKEND_2, BACKEND_3));
            assertThat(pool.healthy()).isEmpty();
            assertThat(pool.healthyCount()).isZero();
        }

        @Test
        void markHealthy_marksBackendAsHealthy() {
            var pool = backendPool(List.of(BACKEND_1, BACKEND_2));
            pool.markHealthy(BACKEND_1);
            assertThat(pool.isHealthy(BACKEND_1)).isTrue();
            assertThat(pool.isHealthy(BACKEND_2)).isFalse();
            assertThat(pool.healthy()).containsExactly(BACKEND_1);
        }

        @Test
        void markUnhealthy_marksBackendAsUnhealthy() {
            var pool = backendPool(List.of(BACKEND_1, BACKEND_2));
            pool.markHealthy(BACKEND_1);
            pool.markHealthy(BACKEND_2);
            assertThat(pool.healthyCount()).isEqualTo(2);

            pool.markUnhealthy(BACKEND_1);
            assertThat(pool.isHealthy(BACKEND_1)).isFalse();
            assertThat(pool.isHealthy(BACKEND_2)).isTrue();
            assertThat(pool.healthyCount()).isEqualTo(1);
        }

        @Test
        void healthyCount_reflectsCurrentState() {
            var pool = backendPool(List.of(BACKEND_1, BACKEND_2, BACKEND_3));
            assertThat(pool.healthyCount()).isZero();
            assertThat(pool.size()).isEqualTo(3);

            pool.markHealthy(BACKEND_1);
            assertThat(pool.healthyCount()).isEqualTo(1);

            pool.markHealthy(BACKEND_2);
            pool.markHealthy(BACKEND_3);
            assertThat(pool.healthyCount()).isEqualTo(3);

            pool.markUnhealthy(BACKEND_2);
            assertThat(pool.healthyCount()).isEqualTo(2);
        }
    }
}
