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
import org.pragmatica.cloud.aws.api.SecurityGroup;
import org.pragmatica.cloud.aws.api.TargetHealth;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.aws.AwsConfig.awsConfig;

class AwsClientTest {
    private static final AwsConfig CONFIG = awsConfig("AKIDTEST", "secretkey123", "us-east-1");
    private static final String GROUP_ID = "sg-0123456789abcdef0";
    private final AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();

    private AwsClient client;
    private TestHttpOperations testHttp;

    @BeforeEach
    void setUp() {
        testHttp = new TestHttpOperations(capturedRequest, capturedBody);
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
        void describeInstancesById_success_parsesXmlResponse() {
            testHttp.respondWith(200, DESCRIBE_INSTANCES_RESPONSE);

            client.describeInstancesById("i-12345")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(AwsClientTest::assertDescribeInstancesResponse);

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
            assertThat(capturedRequest.get().uri().toString()).isEqualTo("https://ec2.us-east-1.amazonaws.com");
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
        void registerTargets_success_speaksQueryProtocol() {
            testHttp.respondWith(200, REGISTER_TARGETS_RESPONSE);

            client.registerTargets("arn:aws:elasticloadbalancing:us-east-1:123456:targetgroup/tg/abc",
                                   List.of("i-12345"))
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(capturedRequest.get().method()).isEqualTo("POST");
            assertQueryProtocol();
        }

        @Test
        void deregisterTargets_success_speaksQueryProtocol() {
            testHttp.respondWith(200, DEREGISTER_TARGETS_RESPONSE);

            client.deregisterTargets("arn:aws:elasticloadbalancing:us-east-1:123456:targetgroup/tg/abc",
                                     List.of("i-12345"))
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isNotNull());

            assertQueryProtocol();
        }

        @Test
        void describeTargetHealth_success_parsesXmlResponse() {
            testHttp.respondWith(200, DESCRIBE_TARGET_HEALTH_RESPONSE);

            client.describeTargetHealth("arn:aws:elasticloadbalancing:us-east-1:123456:targetgroup/tg/abc")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(AwsClientTest::assertTargetHealthList);

            assertQueryProtocol();
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
    class SecurityGroupOperations {

        @Test
        void createSecurityGroup_success_returnsGroupIdAndSendsVpcId() {
            testHttp.respondWith(200, CREATE_SECURITY_GROUP_RESPONSE);

            client.createSecurityGroup("aether-prod-node", "Aether node firewall", Option.some("vpc-0abc"))
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(groupId -> assertThat(groupId).isEqualTo(GROUP_ID));

            assertThat(capturedBody.get())
                .isEqualTo("Action=CreateSecurityGroup&Version=2016-11-15"
                         + "&GroupName=aether-prod-node"
                         + "&GroupDescription=Aether%20node%20firewall"
                         + "&VpcId=vpc-0abc");
            assertQueryProtocol();
        }

        @Test
        void createSecurityGroup_withoutVpc_omitsVpcIdParam() {
            testHttp.respondWith(200, CREATE_SECURITY_GROUP_RESPONSE);

            client.createSecurityGroup("aether-prod-node", "Aether node firewall", Option.none())
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(groupId -> assertThat(groupId).isEqualTo(GROUP_ID));

            assertThat(capturedBody.get())
                .isEqualTo("Action=CreateSecurityGroup&Version=2016-11-15"
                         + "&GroupName=aether-prod-node"
                         + "&GroupDescription=Aether%20node%20firewall");
        }

        @Test
        void createSecurityGroup_fails_whenResponseCarriesNoGroupId() {
            testHttp.respondWith(200, CREATE_SECURITY_GROUP_WITHOUT_ID_RESPONSE);

            client.createSecurityGroup("aether-prod-node", "Aether node firewall", Option.none())
                  .await()
                  .onSuccess(groupId -> assertThat(groupId).isNull())
                  .onFailure(cause -> assertThat(cause).isInstanceOf(AwsError.ParseError.class));
        }

        @Test
        void describeSecurityGroups_success_numbersEveryTagFilterFromOne() {
            testHttp.respondWith(200, DESCRIBE_SECURITY_GROUPS_RESPONSE);

            client.describeSecurityGroups(tagFilters())
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(AwsClientTest::assertDescribedSecurityGroup);

            assertThat(capturedBody.get())
                .isEqualTo("Action=DescribeSecurityGroups&Version=2016-11-15"
                         + "&Filter.1.Name=tag:aether-cluster&Filter.1.Value.1=prod"
                         + "&Filter.2.Name=tag:aether-source&Filter.2.Value.1=provisioner");
            assertQueryProtocol();
        }

        @Test
        void describeSecurityGroups_success_returnsEmptyListForEmptyGroupSet() {
            testHttp.respondWith(200, DESCRIBE_SECURITY_GROUPS_EMPTY_RESPONSE);

            client.describeSecurityGroups(tagFilters())
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(groups -> assertThat(groups).isEmpty());
        }

        @Test
        void authorizeSecurityGroupIngress_success_sendsIpPermissionsForm() {
            testHttp.respondWith(200, AUTHORIZE_INGRESS_RESPONSE);

            client.authorizeSecurityGroupIngress(GROUP_ID, "tcp", 8081, "10.0.0.0/8", "aether mgmt api")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isEqualTo(Unit.unit()));

            assertThat(capturedBody.get()).isEqualTo(EXPECTED_AUTHORIZE_FORM);
            assertQueryProtocol();
        }

        @Test
        void revokeSecurityGroupIngress_success_sendsStoredDescriptionSoTheRuleMatches() {
            testHttp.respondWith(200, REVOKE_INGRESS_RESPONSE);

            client.revokeSecurityGroupIngress(GROUP_ID, "tcp", 8081, "10.0.0.0/8", "aether ingress")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isEqualTo(Unit.unit()));

            assertThat(capturedBody.get())
                .isEqualTo("Action=RevokeSecurityGroupIngress&Version=2016-11-15"
                         + "&GroupId=" + GROUP_ID
                         + "&IpPermissions.1.IpProtocol=tcp"
                         + "&IpPermissions.1.FromPort=8081"
                         + "&IpPermissions.1.ToPort=8081"
                         + "&IpPermissions.1.IpRanges.1.CidrIp=10.0.0.0%2F8"
                         + "&IpPermissions.1.IpRanges.1.Description=aether%20ingress");
        }

        /// A rule stored without a description must be revoked without one — emitting an empty
        /// Description is itself a mismatch.
        @Test
        void revokeSecurityGroupIngress_blankDescription_omitsTheParameter() {
            testHttp.respondWith(200, REVOKE_INGRESS_RESPONSE);

            client.revokeSecurityGroupIngress(GROUP_ID, "tcp", 8081, "10.0.0.0/8", "")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isEqualTo(Unit.unit()));

            assertThat(capturedBody.get()).doesNotContain("Description");
        }

        @Test
        void deleteSecurityGroup_success_sendsGroupIdForm() {
            testHttp.respondWith(200, DELETE_SECURITY_GROUP_RESPONSE);

            client.deleteSecurityGroup(GROUP_ID)
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isEqualTo(Unit.unit()));

            assertThat(capturedBody.get())
                .isEqualTo("Action=DeleteSecurityGroup&Version=2016-11-15&GroupId=" + GROUP_ID);
            assertQueryProtocol();
        }
    }

    /// Pins the codes that mean "the requested end-state already holds" to success, and pins the
    /// tolerance as narrow: any other AWS error code still fails the Promise.
    @Nested
    class SecurityGroupIdempotency {

        @Test
        void authorizeSecurityGroupIngress_succeeds_whenRuleAlreadyExists() {
            testHttp.respondWith(400, ec2Error("InvalidPermission.Duplicate", "the specified rule already exists"));

            client.authorizeSecurityGroupIngress(GROUP_ID, "tcp", 8081, "10.0.0.0/8", "aether mgmt api")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isEqualTo(Unit.unit()));
        }

        @Test
        /// The caller establishes absence by READING the group, so a `NotFound` here means the revoke
        /// did not match a rule that was observed to exist — a mismatch, not an idempotent no-op.
        /// Tolerating it is what let `closeIngress` report success while the rule survived.
        void revokeSecurityGroupIngress_fails_whenRuleDoesNotMatch() {
            testHttp.respondWith(400, ec2Error("InvalidPermission.NotFound", "the specified rule does not exist"));

            var outcome = client.revokeSecurityGroupIngress(GROUP_ID, "tcp", 8081, "10.0.0.0/8", "aether ingress")
                                .await();

            assertThat(outcome.isFailure()).isTrue();
        }

        @Test
        void revokeSecurityGroupIngress_succeeds_whenGroupAlreadyDeleted() {
            testHttp.respondWith(400, ec2Error("InvalidGroup.NotFound", "The security group does not exist"));

            client.revokeSecurityGroupIngress(GROUP_ID, "tcp", 8081, "10.0.0.0/8", "aether ingress")
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isEqualTo(Unit.unit()));
        }

        @Test
        void deleteSecurityGroup_succeeds_whenGroupAbsent() {
            testHttp.respondWith(400, ec2Error("InvalidGroup.NotFound", "The security group does not exist"));

            client.deleteSecurityGroup(GROUP_ID)
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(unit -> assertThat(unit).isEqualTo(Unit.unit()));
        }

        @Test
        void describeSecurityGroups_succeeds_whenGroupAbsent() {
            testHttp.respondWith(400, ec2Error("InvalidGroup.NotFound", "The security group does not exist"));

            client.describeSecurityGroups(tagFilters())
                  .await()
                  .onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(groups -> assertThat(groups).isEmpty());
        }

        @Test
        void authorizeSecurityGroupIngress_fails_forUnrelatedErrorCode() {
            testHttp.respondWith(400, ec2Error("RulesPerSecurityGroupLimitExceeded", "quota exceeded"));

            client.authorizeSecurityGroupIngress(GROUP_ID, "tcp", 8081, "10.0.0.0/8", "aether mgmt api")
                  .await()
                  .onSuccess(unit -> assertThat(unit).isNull())
                  .onFailure(cause -> assertApiErrorCode(cause, "RulesPerSecurityGroupLimitExceeded"));
        }

        @Test
        void deleteSecurityGroup_fails_forUnrelatedErrorCode() {
            testHttp.respondWith(400, ec2Error("DependencyViolation", "resource is in use"));

            client.deleteSecurityGroup(GROUP_ID)
                  .await()
                  .onSuccess(unit -> assertThat(unit).isNull())
                  .onFailure(cause -> assertApiErrorCode(cause, "DependencyViolation"));
        }

        @Test
        void describeSecurityGroups_fails_forUnrelatedErrorCode() {
            testHttp.respondWith(400, ec2Error("InvalidParameterValue", "bad filter"));

            client.describeSecurityGroups(tagFilters())
                  .await()
                  .onSuccess(groups -> assertThat(groups).isNull())
                  .onFailure(cause -> assertApiErrorCode(cause, "InvalidParameterValue"));
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

    private static void assertDescribedSecurityGroup(List<SecurityGroup> groups) {
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().groupId()).isEqualTo(GROUP_ID);
        assertThat(groups.getFirst().groupName()).isEqualTo("aether-prod-node");
        assertThat(groups.getFirst().tags()).containsExactlyInAnyOrderEntriesOf(Map.of("aether-cluster",
                                                                                       "prod",
                                                                                       "aether-source",
                                                                                       "provisioner"));
    }

    private static void assertApiErrorCode(Cause cause, String expectedCode) {
        assertThat(cause).isInstanceOf(AwsError.ApiError.class);
        assertThat(((AwsError.ApiError) cause).code()).isEqualTo(expectedCode);
    }

    /// Ordered so the emitted `Filter.N` numbering is reproducible - EC2 ANDs the filters and does
    /// not care about their order.
    private static Map<String, String> tagFilters() {
        var filters = new LinkedHashMap<String, String>();

        filters.put("aether-cluster", "prod");
        filters.put("aether-source", "provisioner");

        return filters;
    }

    /// EC2 Query-protocol error envelope - `AwsError.fromResponse` reads the code out of `<Error>`.
    private static String ec2Error(String code, String message) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <Response>
                <Errors>
                    <Error>
                        <Code>%s</Code>
                        <Message>%s</Message>
                    </Error>
                </Errors>
                <RequestID>req-sg-err</RequestID>
            </Response>
            """.formatted(code, message);
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

    private void assertQueryProtocol() {
        var headers = capturedRequest.get().headers();

        assertThat(headers.firstValue("X-Amz-Target")).isEmpty();
        assertThat(headers.firstValue("Content-Type")).hasValue("application/x-www-form-urlencoded");
    }

    /// Test HTTP operations that captures requests and returns canned responses.
    static final class TestHttpOperations implements HttpOperations {
        private final AtomicReference<HttpRequest> capturedRequest;
        private final AtomicReference<String> capturedBody;
        private int responseStatus;
        private String responseBody;

        TestHttpOperations(AtomicReference<HttpRequest> capturedRequest, AtomicReference<String> capturedBody) {
            this.capturedRequest = capturedRequest;
            this.capturedBody = capturedBody;
        }

        void respondWith(int status, String body) {
            this.responseStatus = status;
            this.responseBody = body;
        }

        @Override
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            capturedRequest.set(request);
            capturedBody.set(readBody(request));
            @SuppressWarnings("unchecked")
            var result = new HttpResult<>(responseStatus,
                                          HttpHeaders.of(Map.of(), (a, b) -> true),
                                          (T) responseBody);
            return Promise.success(result);
        }

        /// Drains the request's body publisher into a string. `BodyPublishers.ofString` is backed by
        /// a pull publisher that delivers synchronously inside `Subscription.request(...)`, so the
        /// buffer is complete once `subscribe` returns. Were that ever to change, the captured body
        /// would come back short and every form assertion would go red — the failure mode is loud,
        /// not a silent pass.
        private static String readBody(HttpRequest request) {
            var collected = new StringBuilder();

            request.bodyPublisher()
                   .ifPresent(publisher -> publisher.subscribe(new BodyCollector(collected)));

            return collected.toString();
        }
    }

    /// Flow subscriber appending every published chunk to a buffer. Every member is dictated by the
    /// JDK [Flow.Subscriber] contract, hence the type-level [Contract].
    @Contract
    record BodyCollector(StringBuilder buffer) implements Flow.Subscriber<ByteBuffer> {
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            buffer.append(StandardCharsets.UTF_8.decode(item));
        }

        @Override
        public void onError(Throwable throwable) {
            // Leaves the buffer short, which surfaces as a failed body assertion.
        }

        @Override
        public void onComplete() {
            // Nothing to finalize - the buffer is the result.
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
        <?xml version="1.0" encoding="UTF-8"?>
        <DescribeTargetHealthResponse xmlns="http://elasticloadbalancing.amazonaws.com/doc/2015-12-01/">
            <DescribeTargetHealthResult>
                <TargetHealthDescriptions>
                    <member>
                        <Target>
                            <Id>i-12345</Id>
                            <Port>8080</Port>
                        </Target>
                        <TargetHealth>
                            <State>healthy</State>
                            <Description>Target is healthy</Description>
                        </TargetHealth>
                    </member>
                </TargetHealthDescriptions>
            </DescribeTargetHealthResult>
            <ResponseMetadata>
                <RequestId>req-health-1</RequestId>
            </ResponseMetadata>
        </DescribeTargetHealthResponse>
        """;

    private static final String REGISTER_TARGETS_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <RegisterTargetsResponse xmlns="http://elasticloadbalancing.amazonaws.com/doc/2015-12-01/">
            <RegisterTargetsResult/>
            <ResponseMetadata><RequestId>req-reg-1</RequestId></ResponseMetadata>
        </RegisterTargetsResponse>
        """;

    private static final String DEREGISTER_TARGETS_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <DeregisterTargetsResponse xmlns="http://elasticloadbalancing.amazonaws.com/doc/2015-12-01/">
            <DeregisterTargetsResult/>
            <ResponseMetadata><RequestId>req-dereg-1</RequestId></ResponseMetadata>
        </DeregisterTargetsResponse>
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

    /// The exact EC2 Query form for a single inbound rule, asserted verbatim.
    private static final String EXPECTED_AUTHORIZE_FORM =
        "Action=AuthorizeSecurityGroupIngress&Version=2016-11-15"
      + "&GroupId=sg-0123456789abcdef0"
      + "&IpPermissions.1.IpProtocol=tcp"
      + "&IpPermissions.1.FromPort=8081"
      + "&IpPermissions.1.ToPort=8081"
      + "&IpPermissions.1.IpRanges.1.CidrIp=10.0.0.0%2F8"
      + "&IpPermissions.1.IpRanges.1.Description=aether%20mgmt%20api";

    private static final String CREATE_SECURITY_GROUP_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <CreateSecurityGroupResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
            <requestId>req-sg-create</requestId>
            <return>true</return>
            <groupId>sg-0123456789abcdef0</groupId>
        </CreateSecurityGroupResponse>
        """;

    private static final String CREATE_SECURITY_GROUP_WITHOUT_ID_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <CreateSecurityGroupResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
            <requestId>req-sg-create</requestId>
            <return>true</return>
        </CreateSecurityGroupResponse>
        """;

    private static final String DESCRIBE_SECURITY_GROUPS_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <DescribeSecurityGroupsResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
            <requestId>req-sg-describe</requestId>
            <securityGroupInfo>
                <item>
                    <ownerId>123456789012</ownerId>
                    <groupId>sg-0123456789abcdef0</groupId>
                    <groupName>aether-prod-node</groupName>
                    <groupDescription>Aether node firewall</groupDescription>
                    <vpcId>vpc-0abc</vpcId>
                    <tagSet>
                        <item>
                            <key>aether-cluster</key>
                            <value>prod</value>
                        </item>
                        <item>
                            <key>aether-source</key>
                            <value>provisioner</value>
                        </item>
                    </tagSet>
                </item>
            </securityGroupInfo>
        </DescribeSecurityGroupsResponse>
        """;

    private static final String DESCRIBE_SECURITY_GROUPS_EMPTY_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <DescribeSecurityGroupsResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
            <requestId>req-sg-describe-empty</requestId>
            <securityGroupInfo/>
        </DescribeSecurityGroupsResponse>
        """;

    private static final String AUTHORIZE_INGRESS_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <AuthorizeSecurityGroupIngressResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
            <requestId>req-sg-auth</requestId>
            <return>true</return>
        </AuthorizeSecurityGroupIngressResponse>
        """;

    private static final String REVOKE_INGRESS_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <RevokeSecurityGroupIngressResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
            <requestId>req-sg-revoke</requestId>
            <return>true</return>
        </RevokeSecurityGroupIngressResponse>
        """;

    private static final String DELETE_SECURITY_GROUP_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <DeleteSecurityGroupResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
            <requestId>req-sg-delete</requestId>
            <return>true</return>
        </DeleteSecurityGroupResponse>
        """;
}
