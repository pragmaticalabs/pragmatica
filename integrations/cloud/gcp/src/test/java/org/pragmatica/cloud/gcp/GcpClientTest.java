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

package org.pragmatica.cloud.gcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cloud.gcp.api.Instance;
import org.pragmatica.cloud.gcp.api.NetworkEndpoint;
import org.pragmatica.cloud.gcp.api.Operation;
import org.pragmatica.cloud.gcp.api.SetLabelsRequest;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.gcp.GcpConfig.gcpConfig;

class GcpClientTest {
    private static final String BASE_URL = "https://test.compute.googleapis.com/compute/v1";
    private static final String PROJECT_ID = "test-project";
    private static final String ZONE = "us-central1-a";
    private static final GcpConfig CONFIG = gcpConfig(PROJECT_ID, ZONE, "test@test.iam.gserviceaccount.com", "unused", BASE_URL);
    private static final String ZONE_PREFIX = BASE_URL + "/projects/" + PROJECT_ID + "/zones/" + ZONE;

    private final AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
    private final JsonMapper mapper = JsonMapper.defaultJsonMapper();

    private GcpClient client;
    private TestHttpOperations testHttp;

    @BeforeEach
    void setUp() {
        testHttp = new TestHttpOperations(capturedRequest);
        var tokenManager = new PresetTokenManager();
        client = new GcpClientRecord(CONFIG, testHttp, mapper, tokenManager);
    }

    @Nested
    class InstanceOperations {

        @Test
        void getInstance_success_parsesResponse() {
            testHttp.respondWith(200, GET_INSTANCE_RESPONSE);

            client.getInstance("my-instance")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(GcpClientTest::assertInstanceBasicFields);

            assertThat(capturedRequest.get().method()).isEqualTo("GET");
            assertThat(capturedRequest.get().uri().toString()).isEqualTo(ZONE_PREFIX + "/instances/my-instance");
            assertAuthorizationHeader();
        }

        @Test
        void listInstances_success_parsesList() {
            testHttp.respondWith(200, LIST_INSTANCES_RESPONSE);

            client.listInstances()
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(GcpClientTest::assertSingleInstanceList);

            assertThat(capturedRequest.get().method()).isEqualTo("GET");
            assertThat(capturedRequest.get().uri().toString()).isEqualTo(ZONE_PREFIX + "/instances");
        }

        @Test
        void listInstances_withFilter_encodesFilter() {
            testHttp.respondWith(200, LIST_INSTANCES_RESPONSE);

            client.listInstances("labels.env=prod")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(GcpClientTest::assertSingleInstanceList);

            assertThat(capturedRequest.get().method()).isEqualTo("GET");
            assertThat(capturedRequest.get().uri().toString())
                .isEqualTo(ZONE_PREFIX + "/instances?filter=labels.env%3Dprod");
        }

        @Test
        void listInstances_emptyResponse_returnsEmptyList() {
            testHttp.respondWith(200, EMPTY_LIST_RESPONSE);

            client.listInstances()
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(instances -> assertThat(instances).isEmpty());
        }

        @Test
        void deleteInstance_success_returnsUnit() {
            testHttp.respondWith(200, DELETE_OPERATION_RESPONSE);

            client.deleteInstance("my-instance")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(capturedRequest.get().method()).isEqualTo("DELETE");
            assertThat(capturedRequest.get().uri().toString()).isEqualTo(ZONE_PREFIX + "/instances/my-instance");
        }

        @Test
        void resetInstance_success_returnsOperation() {
            testHttp.respondWith(200, RESET_OPERATION_RESPONSE);

            client.resetInstance("my-instance")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(GcpClientTest::assertResetOperation);

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
            assertThat(capturedRequest.get().uri().toString()).isEqualTo(ZONE_PREFIX + "/instances/my-instance/reset");
        }

        @Test
        void setLabels_success_returnsOperation() {
            testHttp.respondWith(200, SET_LABELS_OPERATION_RESPONSE);

            client.setLabels("my-instance", new SetLabelsRequest(Map.of("env", "prod"), "abc123"))
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(op -> assertThat(op.operationType()).isEqualTo("setLabels"));

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
            assertThat(capturedRequest.get().uri().toString())
                .isEqualTo(ZONE_PREFIX + "/instances/my-instance/setLabels");
        }
    }

    @Nested
    class NetworkEndpointOperations {

        @Test
        void attachNetworkEndpoint_success_returnsOperation() {
            testHttp.respondWith(200, ATTACH_ENDPOINT_RESPONSE);

            client.attachNetworkEndpoint("my-neg", new NetworkEndpoint("10.0.0.1", 8080, "my-instance"))
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(op -> assertThat(op.operationType()).isEqualTo("attachNetworkEndpoints"));

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
            assertThat(capturedRequest.get().uri().toString())
                .isEqualTo(ZONE_PREFIX + "/networkEndpointGroups/my-neg/attachNetworkEndpoints");
        }

        @Test
        void detachNetworkEndpoint_success_returnsOperation() {
            testHttp.respondWith(200, DETACH_ENDPOINT_RESPONSE);

            client.detachNetworkEndpoint("my-neg", new NetworkEndpoint("10.0.0.1", 8080, "my-instance"))
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(op -> assertThat(op.operationType()).isEqualTo("detachNetworkEndpoints"));

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
        }

        @Test
        void listNetworkEndpoints_success_parsesList() {
            testHttp.respondWith(200, LIST_ENDPOINTS_RESPONSE);

            client.listNetworkEndpoints("my-neg")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(GcpClientTest::assertSingleEndpointList);

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
            assertThat(capturedRequest.get().uri().toString())
                .isEqualTo(ZONE_PREFIX + "/networkEndpointGroups/my-neg/listNetworkEndpoints");
        }
    }

    @Nested
    class SecretManagerOperations {

        @Test
        void accessSecretVersion_success_decodesPayload() {
            testHttp.respondWith(200, SECRET_RESPONSE);

            client.accessSecretVersion("my-secret")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(secret -> assertThat(secret).isEqualTo("super-secret-value"));

            assertThat(capturedRequest.get().method()).isEqualTo("GET");
            assertThat(capturedRequest.get().uri().toString())
                .contains("/projects/" + PROJECT_ID + "/secrets/my-secret/versions/latest:access");
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void apiError_mapsToGcpError() {
            testHttp.respondWith(404, ERROR_NOT_FOUND_RESPONSE);

            client.getInstance("nonexistent")
                  .await()
                  .onSuccess(instance -> assertThat(instance).isNull())
                  .onFailure(GcpClientTest::assertNotFoundError);
        }

        @Test
        void serverError_mapsToApiError() {
            testHttp.respondWith(500, ERROR_SERVER_RESPONSE);

            client.listInstances()
                  .await()
                  .onSuccess(instances -> assertThat(instances).isNull())
                  .onFailure(GcpClientTest::assertServerErrorResponse);
        }
    }

    // --- Assertion helpers ---

    private static void assertInstanceBasicFields(Instance instance) {
        assertThat(instance.name()).isEqualTo("my-instance");
        assertThat(instance.status()).isEqualTo("RUNNING");
        assertThat(instance.zone()).isEqualTo("projects/test-project/zones/us-central1-a");
    }

    private static void assertSingleInstanceList(List<Instance> instances) {
        assertThat(instances).hasSize(1);
        assertThat(instances.getFirst().name()).isEqualTo("my-instance");
    }

    private static void assertResetOperation(Operation op) {
        assertThat(op.name()).isEqualTo("operation-123");
        assertThat(op.status()).isEqualTo("RUNNING");
        assertThat(op.operationType()).isEqualTo("reset");
    }

    private static void assertSingleEndpointList(List<NetworkEndpoint> endpoints) {
        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.getFirst().ipAddress()).isEqualTo("10.0.0.1");
        assertThat(endpoints.getFirst().port()).isEqualTo(8080);
    }

    private static void assertNotFoundError(Cause cause) {
        assertThat(cause).isInstanceOf(GcpError.ApiError.class);
        var apiError = (GcpError.ApiError) cause;
        assertThat(apiError.statusCode()).isEqualTo(404);
        assertThat(apiError.code()).isEqualTo("NOT_FOUND");
    }

    private static void assertServerErrorResponse(Cause cause) {
        assertThat(cause).isInstanceOf(GcpError.ApiError.class);
        var apiError = (GcpError.ApiError) cause;
        assertThat(apiError.statusCode()).isEqualTo(500);
    }

    private void assertAuthorizationHeader() {
        var authHeader = capturedRequest.get().headers().firstValue("Authorization");
        assertThat(authHeader).isPresent().hasValue("Bearer test-token");
    }

    /// Token manager that returns a preset token without JWT signing.
    static final class PresetTokenManager extends GcpTokenManager {
        PresetTokenManager() {
            super();
        }

        @Override
        Promise<String> getAccessToken(HttpOperations http, JsonMapper mapper) {
            return Promise.success("test-token");
        }
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

    private static final String GET_INSTANCE_RESPONSE = """
        {
          "name": "my-instance",
          "status": "RUNNING",
          "zone": "projects/test-project/zones/us-central1-a",
          "networkInterfaces": [
            {"networkIP": "10.128.0.2", "network": "projects/test-project/global/networks/default"}
          ],
          "labels": {"env": "test"}
        }
        """;

    private static final String LIST_INSTANCES_RESPONSE = """
        {
          "items": [
            {
              "name": "my-instance",
              "status": "RUNNING",
              "zone": "projects/test-project/zones/us-central1-a",
              "networkInterfaces": [
                {"networkIP": "10.128.0.2", "network": "projects/test-project/global/networks/default"}
              ],
              "labels": {"env": "test"}
            }
          ]
        }
        """;

    private static final String EMPTY_LIST_RESPONSE = """
        {}
        """;

    private static final String DELETE_OPERATION_RESPONSE = """
        {
          "name": "operation-456",
          "status": "RUNNING",
          "targetLink": "projects/test-project/zones/us-central1-a/instances/my-instance",
          "operationType": "delete"
        }
        """;

    private static final String RESET_OPERATION_RESPONSE = """
        {
          "name": "operation-123",
          "status": "RUNNING",
          "targetLink": "projects/test-project/zones/us-central1-a/instances/my-instance",
          "operationType": "reset"
        }
        """;

    private static final String SET_LABELS_OPERATION_RESPONSE = """
        {
          "name": "operation-789",
          "status": "RUNNING",
          "targetLink": "projects/test-project/zones/us-central1-a/instances/my-instance",
          "operationType": "setLabels"
        }
        """;

    private static final String ATTACH_ENDPOINT_RESPONSE = """
        {
          "name": "operation-attach",
          "status": "RUNNING",
          "targetLink": "projects/test-project/zones/us-central1-a/networkEndpointGroups/my-neg",
          "operationType": "attachNetworkEndpoints"
        }
        """;

    private static final String DETACH_ENDPOINT_RESPONSE = """
        {
          "name": "operation-detach",
          "status": "RUNNING",
          "targetLink": "projects/test-project/zones/us-central1-a/networkEndpointGroups/my-neg",
          "operationType": "detachNetworkEndpoints"
        }
        """;

    private static final String LIST_ENDPOINTS_RESPONSE = """
        {
          "items": [
            {
              "networkEndpoint": {
                "ipAddress": "10.0.0.1",
                "port": 8080,
                "instance": "my-instance"
              }
            }
          ]
        }
        """;

    private static final String SECRET_RESPONSE = """
        {
          "payload": {
            "data": "c3VwZXItc2VjcmV0LXZhbHVl"
          }
        }
        """;

    private static final String ERROR_NOT_FOUND_RESPONSE = """
        {
          "error": {
            "code": 404,
            "message": "The resource 'projects/test-project/zones/us-central1-a/instances/nonexistent' was not found",
            "status": "NOT_FOUND"
          }
        }
        """;

    private static final String ERROR_SERVER_RESPONSE = """
        {
          "error": {
            "code": 500,
            "message": "Internal server error",
            "status": "INTERNAL"
          }
        }
        """;
}
