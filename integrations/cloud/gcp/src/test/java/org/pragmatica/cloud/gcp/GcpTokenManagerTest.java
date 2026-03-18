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
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.gcp.GcpConfig.gcpConfig;

class GcpTokenManagerTest {
    private static final String PROJECT_ID = "test-project";
    private static final String ZONE = "us-central1-a";
    private static final String SERVICE_ACCOUNT = "test@test-project.iam.gserviceaccount.com";
    private static String TEST_PRIVATE_KEY_PEM;

    static {
        TEST_PRIVATE_KEY_PEM = generateTestPrivateKey();
    }

    private final JsonMapper mapper = JsonMapper.defaultJsonMapper();

    @Nested
    class JwtCreation {

        @Test
        void createSignedJwt_validKey_producesThreePartJwt() {
            var config = gcpConfig(PROJECT_ID, ZONE, SERVICE_ACCOUNT, TEST_PRIVATE_KEY_PEM);
            var tokenManager = GcpTokenManager.gcpTokenManager(config);

            tokenManager.createSignedJwt()
                        .onFailure(cause -> assertThat(cause).isNull())
                        .onSuccess(GcpTokenManagerTest::assertValidJwtStructure);
        }

        @Test
        void createSignedJwt_validKey_containsCorrectHeader() {
            var config = gcpConfig(PROJECT_ID, ZONE, SERVICE_ACCOUNT, TEST_PRIVATE_KEY_PEM);
            var tokenManager = GcpTokenManager.gcpTokenManager(config);

            tokenManager.createSignedJwt()
                        .onFailure(cause -> assertThat(cause).isNull())
                        .onSuccess(GcpTokenManagerTest::assertJwtHeaderContainsRs256);
        }

        @Test
        void createSignedJwt_validKey_containsCorrectClaims() {
            var config = gcpConfig(PROJECT_ID, ZONE, SERVICE_ACCOUNT, TEST_PRIVATE_KEY_PEM);
            var tokenManager = GcpTokenManager.gcpTokenManager(config);

            tokenManager.createSignedJwt()
                        .onFailure(cause -> assertThat(cause).isNull())
                        .onSuccess(GcpTokenManagerTest::assertJwtClaimsContainServiceAccount);
        }

        @Test
        void createSignedJwt_invalidKey_returnsAuthError() {
            var config = gcpConfig(PROJECT_ID, ZONE, SERVICE_ACCOUNT, "invalid-key");
            var tokenManager = GcpTokenManager.gcpTokenManager(config);

            tokenManager.createSignedJwt()
                        .onSuccess(jwt -> assertThat(jwt).isNull())
                        .onFailure(cause -> assertThat(cause).isInstanceOf(GcpError.AuthError.class));
        }
    }

    @Nested
    class TokenCaching {
        private TestHttpOperations testHttp;

        @BeforeEach
        void setUp() {
            testHttp = new TestHttpOperations();
        }

        @Test
        void getAccessToken_firstCall_requestsNewToken() {
            var config = gcpConfig(PROJECT_ID, ZONE, SERVICE_ACCOUNT, TEST_PRIVATE_KEY_PEM);
            var tokenManager = GcpTokenManager.gcpTokenManager(config);
            testHttp.respondWith(200, TOKEN_RESPONSE);

            tokenManager.getAccessToken(testHttp, mapper)
                        .await()
                        .onFailure(cause -> assertThat(cause).isNull())
                        .onSuccess(token -> assertThat(token).isEqualTo("ya29.test-access-token"));

            assertThat(testHttp.callCount()).isEqualTo(1);
        }

        @Test
        void getAccessToken_secondCall_returnsCachedToken() {
            var config = gcpConfig(PROJECT_ID, ZONE, SERVICE_ACCOUNT, TEST_PRIVATE_KEY_PEM);
            var tokenManager = GcpTokenManager.gcpTokenManager(config);
            testHttp.respondWith(200, TOKEN_RESPONSE);

            tokenManager.getAccessToken(testHttp, mapper).await();
            tokenManager.getAccessToken(testHttp, mapper)
                        .await()
                        .onFailure(cause -> assertThat(cause).isNull())
                        .onSuccess(token -> assertThat(token).isEqualTo("ya29.test-access-token"));

            assertThat(testHttp.callCount()).isEqualTo(1);
        }

        @Test
        void getAccessToken_tokenEndpointError_returnsAuthError() {
            var config = gcpConfig(PROJECT_ID, ZONE, SERVICE_ACCOUNT, TEST_PRIVATE_KEY_PEM);
            var tokenManager = GcpTokenManager.gcpTokenManager(config);
            testHttp.respondWith(401, ERROR_RESPONSE);

            tokenManager.getAccessToken(testHttp, mapper)
                        .await()
                        .onSuccess(token -> assertThat(token).isNull())
                        .onFailure(cause -> assertThat(cause).isInstanceOf(GcpError.AuthError.class));
        }
    }

    // --- Assertion helpers ---

    private static void assertValidJwtStructure(String jwt) {
        var parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isNotEmpty();
        assertThat(parts[1]).isNotEmpty();
        assertThat(parts[2]).isNotEmpty();
    }

    private static void assertJwtHeaderContainsRs256(String jwt) {
        var header = decodeJwtPart(jwt.split("\\.")[0]);
        assertThat(header).contains("\"alg\":\"RS256\"");
        assertThat(header).contains("\"typ\":\"JWT\"");
    }

    private static void assertJwtClaimsContainServiceAccount(String jwt) {
        var claims = decodeJwtPart(jwt.split("\\.")[1]);
        assertThat(claims).contains("\"iss\":\"" + SERVICE_ACCOUNT + "\"");
        assertThat(claims).contains("\"scope\":\"https://www.googleapis.com/auth/cloud-platform\"");
        assertThat(claims).contains("\"aud\":\"https://oauth2.googleapis.com/token\"");
    }

    private static String decodeJwtPart(String part) {
        return new String(Base64.getUrlDecoder().decode(part));
    }

    private static String generateTestPrivateKey() {
        try {
            var keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            var keyPair = keyGen.generateKeyPair();
            var encoded = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
            return "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----";
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate test key", e);
        }
    }

    /// Test HTTP operations that captures requests and returns canned responses.
    static final class TestHttpOperations implements HttpOperations {
        private final AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
        private final AtomicInteger callCounter = new AtomicInteger(0);
        private int responseStatus;
        private String responseBody;

        void respondWith(int status, String body) {
            this.responseStatus = status;
            this.responseBody = body;
        }

        int callCount() {
            return callCounter.get();
        }

        @Override
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            capturedRequest.set(request);
            callCounter.incrementAndGet();
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
          "access_token": "ya29.test-access-token",
          "expires_in": 3600,
          "token_type": "Bearer"
        }
        """;

    private static final String ERROR_RESPONSE = """
        {
          "error": "invalid_grant",
          "error_description": "Invalid JWT Signature."
        }
        """;
}
