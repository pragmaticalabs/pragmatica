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

package org.pragmatica.cloud.azure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cloud.azure.api.AzureLoadBalancer;
import org.pragmatica.cloud.azure.api.AzureLoadBalancer.BackendPool;
import org.pragmatica.cloud.azure.api.AzureLoadBalancer.BackendAddress;
import org.pragmatica.cloud.azure.api.AzureLoadBalancer.BackendAddressProperties;
import org.pragmatica.cloud.azure.api.AzureLoadBalancer.PoolProperties;
import org.pragmatica.cloud.azure.api.CreateVmRequest;
import org.pragmatica.cloud.azure.api.ResourceRow;
import org.pragmatica.cloud.azure.api.VirtualMachine;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.azure.AzureConfig.azureConfig;
import static org.pragmatica.cloud.azure.AzureTokenManager.azureTokenManager;

class AzureClientTest {

    private static final String BASE_URL = "https://test.management.azure.com";
    private static final AzureConfig CONFIG = azureConfig(
        "tenant-123", "client-456", "secret-789", "sub-abc", "rg-test", "eastus", BASE_URL);
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    private final AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
    private SequentialHttpOperations testHttp;
    private AzureClient client;

    @BeforeEach
    void setUp() {
        testHttp = new SequentialHttpOperations(capturedRequest);
        var tokenManager = azureTokenManager(CONFIG);
        client = AzureClient.azureClient(CONFIG, testHttp, MAPPER, tokenManager);
    }

    @Nested
    class VmOperations {

        @Test
        void getVm_success_parsesResponse() {
            testHttp.enqueue(200, TOKEN_RESPONSE);
            testHttp.enqueue(200, GET_VM_RESPONSE);

            client.getVm("my-vm")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(AzureClientTest::assertVmBasicFields);

            assertThat(capturedRequest.get().method()).isEqualTo("GET");
            assertThat(capturedRequest.get().uri().toString())
                .contains("/Microsoft.Compute/virtualMachines/my-vm");
            assertAuthorizationHeader();
        }

        @Test
        void listVms_success_parsesList() {
            testHttp.enqueue(200, TOKEN_RESPONSE);
            testHttp.enqueue(200, LIST_VMS_RESPONSE);

            client.listVms()
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(AzureClientTest::assertSingleVmList);
        }

        @Test
        void deleteVm_success_returnsUnit() {
            testHttp.enqueue(200, TOKEN_RESPONSE);
            testHttp.enqueue(200, "");

            client.deleteVm("my-vm")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(capturedRequest.get().method()).isEqualTo("DELETE");
        }

        @Test
        void restartVm_success_returnsUnit() {
            testHttp.enqueue(200, TOKEN_RESPONSE);
            testHttp.enqueue(202, "");

            client.restartVm("my-vm")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
            assertThat(capturedRequest.get().uri().toString()).contains("/restart");
        }

        @Test
        void updateTags_success_parsesResponse() {
            testHttp.enqueue(200, TOKEN_RESPONSE);
            testHttp.enqueue(200, UPDATE_TAGS_RESPONSE);

            client.updateTags("my-vm", Map.of("env", "prod"))
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(vm -> assertThat(vm.tags()).containsEntry("env", "prod"));

            assertThat(capturedRequest.get().method()).isEqualTo("PATCH");
        }

        @Test
        void createVm_success_parsesResponse() {
            testHttp.enqueue(200, TOKEN_RESPONSE);
            testHttp.enqueue(201, GET_VM_RESPONSE);

            var request = CreateVmRequest.createVmRequest("my-vm", "eastus", Map.of(), null);
            client.createVm(request)
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(AzureClientTest::assertVmBasicFields);

            assertThat(capturedRequest.get().method()).isEqualTo("PUT");
        }
    }

    @Nested
    class LoadBalancerOperations {

        @Test
        void getLb_success_parsesResponse() {
            testHttp.enqueue(200, TOKEN_RESPONSE);
            testHttp.enqueue(200, GET_LB_RESPONSE);

            client.getLb("my-lb")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(AzureClientTest::assertLbBasicFields);

            assertThat(capturedRequest.get().uri().toString())
                .contains("/Microsoft.Network/loadBalancers/my-lb");
        }

        @Test
        void updateLbBackendPool_success_parsesResponse() {
            testHttp.enqueue(200, TOKEN_RESPONSE);
            testHttp.enqueue(200, GET_LB_RESPONSE);

            var pool = new BackendPool("pool-id", "my-pool",
                new PoolProperties(List.of(
                    new BackendAddress("addr1", new BackendAddressProperties("10.0.0.1")))));

            client.updateLbBackendPool("my-lb", "my-pool", pool)
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(lb -> assertThat(lb.name()).isEqualTo("my-lb"));

            assertThat(capturedRequest.get().method()).isEqualTo("PUT");
            assertThat(capturedRequest.get().uri().toString()).contains("/backendAddressPools/my-pool");
        }
    }

    @Nested
    class ResourceGraphOperations {

        @Test
        void queryResources_success_parsesResponse() {
            testHttp.enqueue(200, TOKEN_RESPONSE);
            testHttp.enqueue(200, RESOURCE_GRAPH_RESPONSE);

            client.queryResources("Resources | where type =~ 'Microsoft.Compute/virtualMachines'")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(AzureClientTest::assertResourceGraphResults);

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
            assertThat(capturedRequest.get().uri().toString()).contains("Microsoft.ResourceGraph/resources");
        }
    }

    @Nested
    class KeyVaultOperations {

        @Test
        void getSecret_success_returnsValue() {
            testHttp.enqueue(200, TOKEN_RESPONSE);
            testHttp.enqueue(200, SECRET_RESPONSE);

            client.getSecret("my-vault", "my-secret")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(value -> assertThat(value).isEqualTo("super-secret-value"));

            assertThat(capturedRequest.get().uri().toString())
                .isEqualTo("https://my-vault.vault.azure.net/secrets/my-secret?api-version=7.4");
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void apiError_mapsToAzureError() {
            testHttp.enqueue(200, TOKEN_RESPONSE);
            testHttp.enqueue(404, ERROR_NOT_FOUND_RESPONSE);

            client.getVm("nonexistent")
                  .await()
                  .onSuccess(vm -> assertThat(vm).isNull())
                  .onFailure(AzureClientTest::assertNotFoundError);
        }

        @Test
        void serverError_mapsToApiError() {
            testHttp.enqueue(200, TOKEN_RESPONSE);
            testHttp.enqueue(500, ERROR_SERVER_RESPONSE);

            client.listVms()
                  .await()
                  .onSuccess(vms -> assertThat(vms).isNull())
                  .onFailure(AzureClientTest::assertServerError);
        }
    }

    // --- Assertion helpers ---

    private static void assertVmBasicFields(VirtualMachine vm) {
        assertThat(vm.name()).isEqualTo("my-vm");
        assertThat(vm.location()).isEqualTo("eastus");
    }

    private static void assertSingleVmList(List<VirtualMachine> vms) {
        assertThat(vms).hasSize(1);
        assertThat(vms.getFirst().name()).isEqualTo("my-vm");
    }

    private static void assertLbBasicFields(AzureLoadBalancer lb) {
        assertThat(lb.name()).isEqualTo("my-lb");
        assertThat(lb.properties().backendAddressPools()).hasSize(1);
    }

    private static void assertResourceGraphResults(List<ResourceRow> rows) {
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().name()).isEqualTo("my-vm");
        assertThat(rows.getFirst().type()).isEqualTo("microsoft.compute/virtualmachines");
    }

    private static void assertNotFoundError(Cause cause) {
        assertThat(cause).isInstanceOf(AzureError.ApiError.class);
        var apiError = (AzureError.ApiError) cause;
        assertThat(apiError.statusCode()).isEqualTo(404);
        assertThat(apiError.code()).isEqualTo("ResourceNotFound");
    }

    private static void assertServerError(Cause cause) {
        assertThat(cause).isInstanceOf(AzureError.ApiError.class);
        var apiError = (AzureError.ApiError) cause;
        assertThat(apiError.statusCode()).isEqualTo(500);
    }

    private void assertAuthorizationHeader() {
        var authHeader = capturedRequest.get().headers().firstValue("Authorization");
        assertThat(authHeader).isPresent().hasValue("Bearer mgmt-access-token-123");
    }

    /// Test HTTP operations that returns sequential responses for multi-step operations.
    static final class SequentialHttpOperations implements HttpOperations {
        private final AtomicReference<HttpRequest> capturedRequest;
        private final List<QueuedResponse> responses = new ArrayList<>();
        private int responseIndex;

        SequentialHttpOperations(AtomicReference<HttpRequest> capturedRequest) {
            this.capturedRequest = capturedRequest;
        }

        void enqueue(int status, String body) {
            responses.add(new QueuedResponse(status, body));
        }

        record QueuedResponse(int status, String body) {}

        @Override
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            capturedRequest.set(request);
            var response = responses.get(responseIndex++);
            @SuppressWarnings("unchecked")
            var result = new HttpResult<>(response.status(),
                                          HttpHeaders.of(Map.of(), (a, b) -> true),
                                          (T) response.body());
            return Promise.success(result);
        }
    }

    // --- JSON fixtures ---

    private static final String TOKEN_RESPONSE = """
        {
          "access_token": "mgmt-access-token-123",
          "expires_in": 3600,
          "token_type": "Bearer"
        }
        """;

    private static final String GET_VM_RESPONSE = """
        {
          "id": "/subscriptions/sub-abc/resourceGroups/rg-test/providers/Microsoft.Compute/virtualMachines/my-vm",
          "name": "my-vm",
          "location": "eastus",
          "tags": {"env": "test"},
          "properties": {
            "vmId": "vm-id-123",
            "provisioningState": "Succeeded"
          }
        }
        """;

    private static final String LIST_VMS_RESPONSE = """
        {
          "value": [
            {
              "id": "/subscriptions/sub-abc/resourceGroups/rg-test/providers/Microsoft.Compute/virtualMachines/my-vm",
              "name": "my-vm",
              "location": "eastus",
              "tags": {},
              "properties": {
                "vmId": "vm-id-123",
                "provisioningState": "Succeeded"
              }
            }
          ]
        }
        """;

    private static final String UPDATE_TAGS_RESPONSE = """
        {
          "id": "/subscriptions/sub-abc/resourceGroups/rg-test/providers/Microsoft.Compute/virtualMachines/my-vm",
          "name": "my-vm",
          "location": "eastus",
          "tags": {"env": "prod"},
          "properties": {
            "vmId": "vm-id-123",
            "provisioningState": "Succeeded"
          }
        }
        """;

    private static final String GET_LB_RESPONSE = """
        {
          "id": "/subscriptions/sub-abc/resourceGroups/rg-test/providers/Microsoft.Network/loadBalancers/my-lb",
          "name": "my-lb",
          "properties": {
            "backendAddressPools": [
              {
                "id": "pool-1",
                "name": "default-pool",
                "properties": {
                  "loadBalancerBackendAddresses": [
                    {
                      "name": "addr1",
                      "properties": {"ipAddress": "10.0.0.1"}
                    }
                  ]
                }
              }
            ]
          }
        }
        """;

    private static final String RESOURCE_GRAPH_RESPONSE = """
        {
          "data": [
            {
              "id": "/subscriptions/sub-abc/resourceGroups/rg-test/providers/Microsoft.Compute/virtualMachines/my-vm",
              "name": "my-vm",
              "type": "microsoft.compute/virtualmachines",
              "location": "eastus",
              "tags": {"env": "test"},
              "properties": {}
            }
          ]
        }
        """;

    private static final String SECRET_RESPONSE = """
        {
          "value": "super-secret-value",
          "id": "https://my-vault.vault.azure.net/secrets/my-secret/version-1"
        }
        """;

    private static final String ERROR_NOT_FOUND_RESPONSE = """
        {
          "error": {"code": "ResourceNotFound", "message": "The Resource 'nonexistent' was not found."}
        }
        """;

    private static final String ERROR_SERVER_RESPONSE = """
        {
          "error": {"code": "InternalServerError", "message": "An internal server error occurred."}
        }
        """;
}
