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
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.azure.AzureConfig.azureConfig;
import static org.pragmatica.cloud.azure.AzureTokenManager.azureTokenManager;

class AzureTokenManagerTest {

    private static final AzureConfig CONFIG = azureConfig(
        "tenant-123", "client-456", "secret-789", "sub-abc", "rg-test", "eastus");
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    private TestHttpOperations testHttp;
    private AzureTokenManager tokenManager;

    @BeforeEach
    void setUp() {
        testHttp = new TestHttpOperations();
        tokenManager = azureTokenManager(CONFIG);
    }

    @Nested
    class ManagementToken {

        @Test
        void getManagementToken_success_returnsAccessToken() {
            testHttp.respondWith(200, TOKEN_RESPONSE);

            tokenManager.getManagementToken(testHttp, MAPPER)
                        .await()
                        .onFailure(cause -> assertThat(cause).isNull())
                        .onSuccess(token -> assertThat(token).isEqualTo("mgmt-access-token-123"));

            assertThat(testHttp.capturedRequest().method()).isEqualTo("POST");
            assertThat(testHttp.capturedRequest().uri().toString())
                .contains("login.microsoftonline.com/tenant-123/oauth2/v2.0/token");
        }

        @Test
        void getManagementToken_cached_doesNotCallApiAgain() {
            testHttp.respondWith(200, TOKEN_RESPONSE);

            tokenManager.getManagementToken(testHttp, MAPPER).await();
            var firstCallCount = testHttp.callCount();

            tokenManager.getManagementToken(testHttp, MAPPER)
                        .await()
                        .onFailure(cause -> assertThat(cause).isNull())
                        .onSuccess(token -> assertThat(token).isEqualTo("mgmt-access-token-123"));

            assertThat(testHttp.callCount()).isEqualTo(firstCallCount);
        }

        @Test
        void getManagementToken_authFailure_returnsAuthError() {
            testHttp.respondWith(401, AUTH_ERROR_RESPONSE);

            tokenManager.getManagementToken(testHttp, MAPPER)
                        .await()
                        .onSuccess(token -> assertThat(token).isNull())
                        .onFailure(AzureTokenManagerTest::assertAuthError);
        }
    }

    @Nested
    class VaultToken {

        @Test
        void getVaultToken_success_returnsAccessToken() {
            testHttp.respondWith(200, VAULT_TOKEN_RESPONSE);

            tokenManager.getVaultToken(testHttp, MAPPER)
                        .await()
                        .onFailure(cause -> assertThat(cause).isNull())
                        .onSuccess(token -> assertThat(token).isEqualTo("vault-access-token-456"));
        }
    }

    @Nested
    class DualTokens {

        @Test
        void managementAndVault_separateTokens_cachedIndependently() {
            testHttp.respondWith(200, TOKEN_RESPONSE);
            tokenManager.getManagementToken(testHttp, MAPPER).await();
            var afterMgmt = testHttp.callCount();

            testHttp.respondWith(200, VAULT_TOKEN_RESPONSE);
            tokenManager.getVaultToken(testHttp, MAPPER).await();

            assertThat(testHttp.callCount()).isEqualTo(afterMgmt + 1);
        }
    }

    // --- Assertion helpers ---

    private static void assertAuthError(Cause cause) {
        assertThat(cause).isInstanceOf(AzureError.AuthError.class);
        var authError = (AzureError.AuthError) cause;
        assertThat(authError.message()).contains("Token request failed");
    }

    /// Test HTTP operations that captures requests and returns canned responses.
    static final class TestHttpOperations implements HttpOperations {
        private final AtomicReference<HttpRequest> captured = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();
        private int responseStatus;
        private String responseBody;

        void respondWith(int status, String body) {
            this.responseStatus = status;
            this.responseBody = body;
        }

        HttpRequest capturedRequest() {
            return captured.get();
        }

        int callCount() {
            return calls.get();
        }

        @Override
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            captured.set(request);
            calls.incrementAndGet();
            @SuppressWarnings("unchecked")
            var result = new HttpResult<>(responseStatus,
                                          HttpHeaders.of(Map.of(), (a, b) -> true),
                                          (T) responseBody);
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

    private static final String VAULT_TOKEN_RESPONSE = """
        {
          "access_token": "vault-access-token-456",
          "expires_in": 3600,
          "token_type": "Bearer"
        }
        """;

    private static final String AUTH_ERROR_RESPONSE = """
        {
          "error": "invalid_client",
          "error_description": "Invalid client credentials"
        }
        """;
}
