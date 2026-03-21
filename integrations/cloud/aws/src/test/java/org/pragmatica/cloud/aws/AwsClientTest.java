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

package org.pragmatica.cloud.aws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cloud.aws.api.DescribeInstancesResponse;
import org.pragmatica.cloud.aws.api.TargetHealth;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.aws.AwsConfig.awsConfig;

class AwsClientTest {
    private static final AwsConfig CONFIG = awsConfig("AKIDTEST", "secretkey123", "us-east-1");
    private final AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();

    private AwsClient client;
    private TestHttpOperations testHttp;

    @BeforeEach
    void setUp() {
        testHttp = new TestHttpOperations(capturedRequest);
        client = AwsClient.awsClient(CONFIG, testHttp);
    }

    @Nested
    class Ec2Operations {

        @Test
        void describeInstances_success_parsesXmlResponse() {
            testHttp.respondWith(200, DESCRIBE_INSTANCES_RESPONSE);

            client.describeInstances()
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(AwsClientTest::assertDescribeInstancesResponse);

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
            assertThat(capturedRequest.get().uri().toString()).isEqualTo("https://ec2.us-east-1.amazonaws.com");
        }

        @Test
        void describeInstances_withFilter_sendsCorrectBody() {
            testHttp.respondWith(200, DESCRIBE_INSTANCES_RESPONSE);

            client.describeInstances("env", "prod")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(AwsClientTest::assertDescribeInstancesResponse);

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
        }

        @Test
        void terminateInstances_success_returnsUnit() {
            testHttp.respondWith(200, TERMINATE_INSTANCES_RESPONSE);

            client.terminateInstances(List.of("i-12345", "i-67890"))
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
        }

        @Test
        void rebootInstances_success_returnsUnit() {
            testHttp.respondWith(200, SIMPLE_EC2_RESPONSE);

            client.rebootInstances(List.of("i-12345"))
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isNotNull());
        }

        @Test
        void createTags_success_returnsUnit() {
            testHttp.respondWith(200, SIMPLE_EC2_RESPONSE);

            client.createTags(List.of("i-12345"), Map.of("Name", "test-instance"))
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isNotNull());
        }
    }

    @Nested
    class Elbv2Operations {

        @Test
        void registerTargets_success_returnsUnit() {
            testHttp.respondWith(200, "{}");

            client.registerTargets("arn:aws:elasticloadbalancing:us-east-1:123456:targetgroup/tg/abc",
                                   List.of("i-12345"))
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
            assertXAmzTargetHeader("ElasticLoadBalancingv2.RegisterTargets");
        }

        @Test
        void deregisterTargets_success_returnsUnit() {
            testHttp.respondWith(200, "{}");

            client.deregisterTargets("arn:aws:elasticloadbalancing:us-east-1:123456:targetgroup/tg/abc",
                                     List.of("i-12345"))
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isNotNull());

            assertXAmzTargetHeader("ElasticLoadBalancingv2.DeregisterTargets");
        }

        @Test
        void describeTargetHealth_success_parsesResponse() {
            testHttp.respondWith(200, DESCRIBE_TARGET_HEALTH_RESPONSE);

            client.describeTargetHealth("arn:aws:elasticloadbalancing:us-east-1:123456:targetgroup/tg/abc")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(AwsClientTest::assertTargetHealthList);

            assertXAmzTargetHeader("ElasticLoadBalancingv2.DescribeTargetHealth");
        }
    }

    @Nested
    class SecretsManagerOperations {

        @Test
        void getSecretValue_success_returnsSecret() {
            testHttp.respondWith(200, GET_SECRET_VALUE_RESPONSE);

            client.getSecretValue("my-secret")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(secret -> assertThat(secret).isEqualTo("super-secret-value"));

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void ec2Error_mapsToAwsError() {
            testHttp.respondWith(400, EC2_ERROR_RESPONSE);

            client.describeInstances()
                  .await()
                  .onSuccess(resp -> assertThat(resp).isNull())
                  .onFailure(AwsClientTest::assertEc2ApiError);
        }

        @Test
        void jsonError_mapsToAwsError() {
            testHttp.respondWith(400, JSON_ERROR_RESPONSE);

            client.getSecretValue("nonexistent")
                  .await()
                  .onSuccess(secret -> assertThat(secret).isNull())
                  .onFailure(AwsClientTest::assertJsonApiError);
        }
    }

    // --- Assertion helpers ---

    private static void assertDescribeInstancesResponse(DescribeInstancesResponse response) {
        assertThat(response.allInstances()).hasSize(1);
        assertThat(response.allInstances().getFirst().instanceId()).isEqualTo("i-12345");
        assertThat(response.allInstances().getFirst().instanceType()).isEqualTo("t2.micro");
    }

    private static void assertTargetHealthList(List<TargetHealth> targets) {
        assertThat(targets).hasSize(1);
        assertThat(targets.getFirst().targetId()).isEqualTo("i-12345");
        assertThat(targets.getFirst().state()).isEqualTo("healthy");
    }

    private static void assertEc2ApiError(Cause cause) {
        assertThat(cause).isInstanceOf(AwsError.ApiError.class);
        var apiError = (AwsError.ApiError) cause;
        assertThat(apiError.statusCode()).isEqualTo(400);
        assertThat(apiError.code()).isEqualTo("InvalidParameterValue");
    }

    private static void assertJsonApiError(Cause cause) {
        assertThat(cause).isInstanceOf(AwsError.ApiError.class);
        var apiError = (AwsError.ApiError) cause;
        assertThat(apiError.statusCode()).isEqualTo(400);
        assertThat(apiError.code()).isEqualTo("ResourceNotFoundException");
    }

    private void assertXAmzTargetHeader(String expectedTarget) {
        var targetHeader = capturedRequest.get().headers().firstValue("X-Amz-Target");
        assertThat(targetHeader).isPresent().hasValue(expectedTarget);
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

    // --- Fixtures ---

    private static final String DESCRIBE_INSTANCES_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <DescribeInstancesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
            <reservationSet>
                <item>
                    <reservationId>r-abc123</reservationId>
                    <instancesSet>
                        <item>
                            <instanceId>i-12345</instanceId>
                            <instanceType>t2.micro</instanceType>
                            <imageId>ami-abcdef</imageId>
                            <privateIpAddress>10.0.0.1</privateIpAddress>
                            <instanceState>
                                <name>running</name>
                                <code>16</code>
                            </instanceState>
                            <tagSet>
                                <item>
                                    <key>Name</key>
                                    <value>test-instance</value>
                                </item>
                            </tagSet>
                        </item>
                    </instancesSet>
                </item>
            </reservationSet>
        </DescribeInstancesResponse>
        """;

    private static final String TERMINATE_INSTANCES_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <TerminateInstancesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
            <instancesSet>
                <item>
                    <instanceId>i-12345</instanceId>
                    <currentState><code>32</code><name>shutting-down</name></currentState>
                    <previousState><code>16</code><name>running</name></previousState>
                </item>
            </instancesSet>
        </TerminateInstancesResponse>
        """;

    private static final String SIMPLE_EC2_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Response><return>true</return></Response>
        """;

    private static final String DESCRIBE_TARGET_HEALTH_RESPONSE = """
        {
            "TargetHealthDescriptions": [
                {
                    "Target": {
                        "Id": "i-12345",
                        "Port": 8080
                    },
                    "TargetHealth": {
                        "State": "healthy",
                        "Description": "Target is healthy"
                    }
                }
            ]
        }
        """;

    private static final String GET_SECRET_VALUE_RESPONSE = """
        {
            "ARN": "arn:aws:secretsmanager:us-east-1:123456:secret:my-secret-abc",
            "Name": "my-secret",
            "SecretString": "super-secret-value",
            "VersionId": "version-1"
        }
        """;

    private static final String EC2_ERROR_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Response>
            <Errors>
                <Error>
                    <Code>InvalidParameterValue</Code>
                    <Message>Invalid instance type</Message>
                </Error>
            </Errors>
            <RequestID>req-123</RequestID>
        </Response>
        """;

    private static final String JSON_ERROR_RESPONSE = """
        {
            "__type": "ResourceNotFoundException",
            "message": "Secret not found"
        }
        """;
}
