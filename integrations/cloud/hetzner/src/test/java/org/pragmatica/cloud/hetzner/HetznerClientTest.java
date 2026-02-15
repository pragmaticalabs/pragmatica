/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.pragmatica.cloud.hetzner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cloud.hetzner.api.LoadBalancer;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.lang.Cause;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Promise;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.hetzner.HetznerConfig.hetznerConfig;

class HetznerClientTest {

    private static final String BASE_URL = "https://test.api.hetzner.cloud/v1";
    private static final HetznerConfig CONFIG = hetznerConfig("test-token", BASE_URL);
    private final AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();

    private HetznerClient client;
    private TestHttpOperations testHttp;

    @BeforeEach
    void setUp() {
        testHttp = new TestHttpOperations(capturedRequest);
        client = HetznerClient.hetznerClient(CONFIG, testHttp);
    }

    @Nested
    class ServerOperations {

        @Test
        void createServer_success_parsesResponse() {
            testHttp.respondWith(201, CREATE_SERVER_RESPONSE);

            client.createServer(CreateServerRequest.createServerRequest(
                    "my-server", "cx11", "ubuntu-22.04",
                    List.of(1L), List.of(10L), List.of(5L), "", true))
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(HetznerClientTest::assertCreatedServer);

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
            assertThat(capturedRequest.get().uri().toString()).isEqualTo(BASE_URL + "/servers");
            assertAuthorizationHeader();
        }

        @Test
        void deleteServer_success_returnsUnit() {
            testHttp.respondWith(200, DELETE_ACTION_RESPONSE);

            client.deleteServer(42)
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(capturedRequest.get().method()).isEqualTo("DELETE");
            assertThat(capturedRequest.get().uri().toString()).isEqualTo(BASE_URL + "/servers/42");
        }

        @Test
        void getServer_success_parsesResponse() {
            testHttp.respondWith(200, GET_SERVER_RESPONSE);

            client.getServer(42)
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(HetznerClientTest::assertServerBasicFields);

            assertThat(capturedRequest.get().method()).isEqualTo("GET");
        }

        @Test
        void listServers_success_parsesList() {
            testHttp.respondWith(200, LIST_SERVERS_RESPONSE);

            client.listServers()
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(HetznerClientTest::assertSingleServerList);

            assertThat(capturedRequest.get().method()).isEqualTo("GET");
            assertThat(capturedRequest.get().uri().toString()).isEqualTo(BASE_URL + "/servers");
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void apiError_mapsToHetznerError() {
            testHttp.respondWith(404, ERROR_NOT_FOUND_RESPONSE);

            client.getServer(999)
                  .await()
                  .onSuccess(server -> assertThat(server).isNull())
                  .onFailure(HetznerClientTest::assertNotFoundError);
        }

        @Test
        void rateLimited_mapsToRateLimitedError() {
            testHttp.respondWith(429, RATE_LIMITED_RESPONSE);

            client.listServers()
                  .await()
                  .onSuccess(servers -> assertThat(servers).isNull())
                  .onFailure(HetznerClientTest::assertRateLimitedError);
        }

        @Test
        void serverError_mapsToApiError() {
            testHttp.respondWith(500, ERROR_SERVER_RESPONSE);

            client.listServers()
                  .await()
                  .onSuccess(servers -> assertThat(servers).isNull())
                  .onFailure(HetznerClientTest::assertServerErrorResponse);
        }
    }

    @Nested
    class LoadBalancerOperations {

        @Test
        void listLoadBalancers_success_parsesList() {
            testHttp.respondWith(200, LIST_LOAD_BALANCERS_RESPONSE);

            client.listLoadBalancers()
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(HetznerClientTest::assertSingleLoadBalancerList);
        }

        @Test
        void addTarget_success_returnsUnit() {
            testHttp.respondWith(201, ADD_TARGET_RESPONSE);

            client.addTarget(100, 42)
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
            assertThat(capturedRequest.get().uri().toString())
                .isEqualTo(BASE_URL + "/load_balancers/100/actions/add_target");
        }
    }

    // --- Assertion helpers ---

    private static void assertCreatedServer(Server server) {
        assertThat(server.id()).isEqualTo(42);
        assertThat(server.name()).isEqualTo("my-server");
        assertThat(server.status()).isEqualTo("initializing");
    }

    private static void assertServerBasicFields(Server server) {
        assertThat(server.id()).isEqualTo(42);
        assertThat(server.name()).isEqualTo("my-server");
    }

    private static void assertSingleServerList(List<Server> servers) {
        assertThat(servers).hasSize(1);
        assertThat(servers.getFirst().id()).isEqualTo(42);
    }

    private static void assertNotFoundError(Cause cause) {
        assertThat(cause).isInstanceOf(HetznerError.ApiError.class);
        var apiError = (HetznerError.ApiError) cause;
        assertThat(apiError.statusCode()).isEqualTo(404);
        assertThat(apiError.code()).isEqualTo("not_found");
    }

    private static void assertRateLimitedError(Cause cause) {
        assertThat(cause).isInstanceOf(HetznerError.RateLimited.class);
        var rateLimited = (HetznerError.RateLimited) cause;
        assertThat(rateLimited.retryAfterSeconds()).isEqualTo(30);
    }

    private static void assertServerErrorResponse(Cause cause) {
        assertThat(cause).isInstanceOf(HetznerError.ApiError.class);
        var apiError = (HetznerError.ApiError) cause;
        assertThat(apiError.statusCode()).isEqualTo(500);
    }

    private static void assertSingleLoadBalancerList(List<LoadBalancer> lbs) {
        assertThat(lbs).hasSize(1);
        assertThat(lbs.getFirst().id()).isEqualTo(100);
        assertThat(lbs.getFirst().name()).isEqualTo("my-lb");
    }

    private void assertAuthorizationHeader() {
        var authHeader = capturedRequest.get().headers().firstValue("Authorization");
        assertThat(authHeader).isPresent().hasValue("Bearer test-token");
    }

    /// Test HTTP operations that captures requests and returns canned responses.
    static final class TestHttpOperations implements HttpOperations {
        private final AtomicReference<HttpRequest> capturedRequest;
        private int responseStatus;
        private String responseBody;

        TestHttpOperations(AtomicReference<HttpRequest> capturedRequest) {
            this.capturedRequest = capturedRequest;
        }

        void respondWith(int status, String body) {
            this.responseStatus = status;
            this.responseBody = body;
        }

        @Override
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            capturedRequest.set(request);
            @SuppressWarnings("unchecked")
            var result = new HttpResult<>(responseStatus,
                                          HttpHeaders.of(Map.of(), (a, b) -> true),
                                          (T) responseBody);
            return Promise.success(result);
        }
    }

    // --- JSON fixtures ---

    private static final String CREATE_SERVER_RESPONSE = """
        {
          "server": {
            "id": 42,
            "name": "my-server",
            "status": "initializing",
            "server_type": {"id": 1, "name": "cx11", "description": "CX11", "cores": 1, "memory": 2.0, "disk": 20},
            "image": {"id": 1, "name": "ubuntu-22.04", "description": "Ubuntu 22.04", "os_flavor": "ubuntu"},
            "public_net": {"ipv4": {"ip": "1.2.3.4"}, "ipv6": {"ip": "2001:db8::1"}},
            "private_net": []
          },
          "action": {"id": 1, "command": "create_server", "status": "running", "progress": 0}
        }
        """;

    private static final String GET_SERVER_RESPONSE = """
        {
          "server": {
            "id": 42,
            "name": "my-server",
            "status": "running",
            "server_type": {"id": 1, "name": "cx11", "description": "CX11", "cores": 1, "memory": 2.0, "disk": 20},
            "image": {"id": 1, "name": "ubuntu-22.04", "description": "Ubuntu 22.04", "os_flavor": "ubuntu"},
            "public_net": {"ipv4": {"ip": "1.2.3.4"}, "ipv6": {"ip": "2001:db8::1"}},
            "private_net": []
          }
        }
        """;

    private static final String LIST_SERVERS_RESPONSE = """
        {
          "servers": [
            {
              "id": 42,
              "name": "my-server",
              "status": "running",
              "server_type": {"id": 1, "name": "cx11", "description": "CX11", "cores": 1, "memory": 2.0, "disk": 20},
              "image": {"id": 1, "name": "ubuntu-22.04", "description": "Ubuntu 22.04", "os_flavor": "ubuntu"},
              "public_net": {"ipv4": {"ip": "1.2.3.4"}, "ipv6": {"ip": "2001:db8::1"}},
              "private_net": []
            }
          ]
        }
        """;

    private static final String DELETE_ACTION_RESPONSE = """
        {
          "action": {"id": 1, "command": "delete_server", "status": "running", "progress": 0}
        }
        """;

    private static final String ERROR_NOT_FOUND_RESPONSE = """
        {
          "error": {"code": "not_found", "message": "Server with ID 999 not found"}
        }
        """;

    private static final String RATE_LIMITED_RESPONSE = """
        {
          "error": {"code": "rate_limit_exceeded", "message": "Rate limit exceeded"},
          "retry_after": 30
        }
        """;

    private static final String ERROR_SERVER_RESPONSE = """
        {
          "error": {"code": "server_error", "message": "Internal server error"}
        }
        """;

    private static final String LIST_LOAD_BALANCERS_RESPONSE = """
        {
          "load_balancers": [
            {
              "id": 100,
              "name": "my-lb",
              "load_balancer_type": {"id": 1, "name": "lb11", "description": "LB11"},
              "algorithm": {"type": "round_robin"},
              "targets": []
            }
          ]
        }
        """;

    private static final String ADD_TARGET_RESPONSE = """
        {
          "action": {"id": 2, "command": "add_target", "status": "running", "progress": 0}
        }
        """;
}
