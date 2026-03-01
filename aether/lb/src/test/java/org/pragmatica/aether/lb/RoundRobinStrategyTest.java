package org.pragmatica.aether.lb;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.server.RequestContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.lb.Backend.backend;
import static org.pragmatica.aether.lb.RoundRobinStrategy.roundRobinStrategy;

class RoundRobinStrategyTest {
    private static final RequestContext DUMMY_REQUEST = dummyRequest();

    @Nested
    class SelectTests {
        @Test
        void select_withMultipleBackends_distributesEvenly() {
            var strategy = roundRobinStrategy();
            var backends = List.of(
                backend("host1", 8080),
                backend("host2", 8080),
                backend("host3", 8080)
            );
            var counts = new int[3];
            for (int i = 0; i < 9; i++) {
                strategy.select(backends, DUMMY_REQUEST)
                        .onPresent(b -> counts[backends.indexOf(b)]++);
            }
            assertThat(counts[0]).isEqualTo(3);
            assertThat(counts[1]).isEqualTo(3);
            assertThat(counts[2]).isEqualTo(3);
        }

        @Test
        void select_withEmptyList_returnsEmpty() {
            var strategy = roundRobinStrategy();
            var result = strategy.select(List.of(), DUMMY_REQUEST);
            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void select_withSingleBackend_alwaysReturnsSame() {
            var strategy = roundRobinStrategy();
            var backends = List.of(backend("host1", 8080));
            for (int i = 0; i < 5; i++) {
                var result = strategy.select(backends, DUMMY_REQUEST);
                assertThat(result.isPresent()).isTrue();
                result.onPresent(b -> assertThat(b.host()).isEqualTo("host1"));
            }
        }
    }

    private static RequestContext dummyRequest() {
        return new RequestContext() {
            @Override
            public String requestId() {
                return "req_test";
            }

            @Override
            public HttpMethod method() {
                return HttpMethod.GET;
            }

            @Override
            public String path() {
                return "/";
            }

            @Override
            public Headers headers() {
                return Headers.empty();
            }

            @Override
            public QueryParams queryParams() {
                return QueryParams.empty();
            }

            @Override
            public byte[] body() {
                return new byte[0];
            }
        };
    }
}
