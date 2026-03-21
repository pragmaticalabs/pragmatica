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

import org.pragmatica.cloud.aws.api.DescribeInstancesResponse;
import org.pragmatica.cloud.aws.api.DescribeTargetHealthResponse;
import org.pragmatica.cloud.aws.api.RunInstancesRequest;
import org.pragmatica.cloud.aws.api.RunInstancesResponse;
import org.pragmatica.cloud.aws.api.TargetHealth;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.xml.XmlMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/// AWS Cloud API client with Promise-based async operations.
/// Uses SigV4 signing, XML for EC2, JSON for ELBv2/SecretsManager.
public interface AwsClient {
    // --- EC2 operations ---

    /// Launches new EC2 instances.
    Promise<RunInstancesResponse> runInstances(RunInstancesRequest request);

    /// Terminates EC2 instances by ID.
    Promise<Unit> terminateInstances(List<String> instanceIds);

    /// Describes all EC2 instances.
    Promise<DescribeInstancesResponse> describeInstances();

    /// Describes EC2 instances matching a tag filter.
    Promise<DescribeInstancesResponse> describeInstances(String tagKey, String tagValue);

    /// Reboots EC2 instances by ID.
    Promise<Unit> rebootInstances(List<String> instanceIds);

    /// Creates tags on EC2 resources.
    Promise<Unit> createTags(List<String> resourceIds, Map<String, String> tags);

    // --- ELBv2 operations ---

    /// Registers instances with a target group.
    Promise<Unit> registerTargets(String targetGroupArn, List<String> instanceIds);

    /// Deregisters instances from a target group.
    Promise<Unit> deregisterTargets(String targetGroupArn, List<String> instanceIds);

    /// Describes target health for a target group.
    Promise<List<TargetHealth>> describeTargetHealth(String targetGroupArn);

    // --- Secrets Manager operations ---

    /// Gets a secret value by secret ID.
    Promise<String> getSecretValue(String secretId);

    /// Creates an AwsClient with default HTTP operations.
    static AwsClient awsClient(AwsConfig config) {
        return awsClient(config, JdkHttpOperations.jdkHttpOperations());
    }

    /// Creates an AwsClient with custom HTTP operations (for testing).
    static AwsClient awsClient(AwsConfig config, HttpOperations http) {
        return new AwsClientRecord(config, http, JsonMapper.defaultJsonMapper(), XmlMapper.defaultXmlMapper());
    }
}

/// Implementation of AwsClient using HttpOperations, JsonMapper, and XmlMapper.
record AwsClientRecord(AwsConfig config,
                        HttpOperations http,
                        JsonMapper jsonMapper,
                        XmlMapper xmlMapper) implements AwsClient {
    private static final String EC2_API_VERSION = "2016-11-15";
    private static final String EC2_SERVICE = "ec2";
    private static final String ELB_SERVICE = "elasticloadbalancing";
    private static final String SECRETS_SERVICE = "secretsmanager";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String JSON_CONTENT_TYPE = "application/x-amz-json-1.1";

    @Override
    public Promise<RunInstancesResponse> runInstances(RunInstancesRequest request) {
        var formBody = buildRunInstancesForm(request);
        return postEc2(formBody, RunInstancesResponse.class);
    }

    @Override
    public Promise<Unit> terminateInstances(List<String> instanceIds) {
        var formBody = buildInstanceIdsForm("TerminateInstances", instanceIds);
        return postEc2Discarding(formBody);
    }

    @Override
    public Promise<DescribeInstancesResponse> describeInstances() {
        var formBody = "Action=DescribeInstances&Version=" + EC2_API_VERSION;
        return postEc2(formBody, DescribeInstancesResponse.class);
    }

    @Override
    public Promise<DescribeInstancesResponse> describeInstances(String tagKey, String tagValue) {
        var formBody = "Action=DescribeInstances&Version=" + EC2_API_VERSION
                       + "&Filter.1.Name=tag:" + AwsSigV4Signer.urlEncode(tagKey)
                       + "&Filter.1.Value.1=" + AwsSigV4Signer.urlEncode(tagValue);
        return postEc2(formBody, DescribeInstancesResponse.class);
    }

    @Override
    public Promise<Unit> rebootInstances(List<String> instanceIds) {
        var formBody = buildInstanceIdsForm("RebootInstances", instanceIds);
        return postEc2Discarding(formBody);
    }

    @Override
    public Promise<Unit> createTags(List<String> resourceIds, Map<String, String> tags) {
        var formBody = buildCreateTagsForm(resourceIds, tags);
        return postEc2Discarding(formBody);
    }

    @Override
    public Promise<Unit> registerTargets(String targetGroupArn, List<String> instanceIds) {
        return postElbv2Action("ElasticLoadBalancingv2.RegisterTargets",
                               buildTargetGroupJson(targetGroupArn, instanceIds));
    }

    @Override
    public Promise<Unit> deregisterTargets(String targetGroupArn, List<String> instanceIds) {
        return postElbv2Action("ElasticLoadBalancingv2.DeregisterTargets",
                               buildTargetGroupJson(targetGroupArn, instanceIds));
    }

    @Override
    public Promise<List<TargetHealth>> describeTargetHealth(String targetGroupArn) {
        var jsonBody = "{\"TargetGroupArn\":\"" + targetGroupArn + "\"}";
        return postElbv2("ElasticLoadBalancingv2.DescribeTargetHealth",
                         jsonBody, DescribeTargetHealthResponse.class)
            .map(DescribeTargetHealthResponse::toTargetHealthList);
    }

    @Override
    public Promise<String> getSecretValue(String secretId) {
        var jsonBody = "{\"SecretId\":\"" + secretId + "\"}";
        return postSecretsManager(jsonBody)
            .flatMap(this::extractSecretString);
    }

    // --- EC2 helpers ---

    private <T> Promise<T> postEc2(String formBody, Class<T> responseType) {
        var bodyBytes = formBody.getBytes(StandardCharsets.UTF_8);
        return AwsSigV4Signer.sign(config, EC2_SERVICE, "POST", config.ec2Url(),
                                   Map.of("content-type", FORM_CONTENT_TYPE), bodyBytes)
                             .async()
                             .flatMap(signedHeaders -> sendEc2Request(formBody, signedHeaders))
                             .flatMap(result -> parseXmlResponse(result, responseType));
    }

    private Promise<Unit> postEc2Discarding(String formBody) {
        var bodyBytes = formBody.getBytes(StandardCharsets.UTF_8);
        return AwsSigV4Signer.sign(config, EC2_SERVICE, "POST", config.ec2Url(),
                                   Map.of("content-type", FORM_CONTENT_TYPE), bodyBytes)
                             .async()
                             .flatMap(signedHeaders -> sendEc2Request(formBody, signedHeaders))
                             .flatMap(this::checkSuccess);
    }

    private Promise<HttpResult<String>> sendEc2Request(String formBody, Map<String, String> signedHeaders) {
        var builder = HttpRequest.newBuilder()
                                .uri(URI.create(config.ec2Url()))
                                .POST(BodyPublishers.ofString(formBody))
                                .header("Content-Type", FORM_CONTENT_TYPE);
        signedHeaders.forEach(builder::header);
        return http.sendString(builder.build());
    }

    // --- ELBv2 helpers ---

    private Promise<Unit> postElbv2Action(String target, String jsonBody) {
        var bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        var headers = Map.of("content-type", JSON_CONTENT_TYPE, "x-amz-target", target);
        return AwsSigV4Signer.sign(config, ELB_SERVICE, "POST", config.elbv2Url(), headers, bodyBytes)
                             .async()
                             .flatMap(signedHeaders -> sendElbv2Request(target, jsonBody, signedHeaders))
                             .flatMap(this::checkSuccess);
    }

    private <T> Promise<T> postElbv2(String target, String jsonBody, Class<T> responseType) {
        var bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        var headers = Map.of("content-type", JSON_CONTENT_TYPE, "x-amz-target", target);
        return AwsSigV4Signer.sign(config, ELB_SERVICE, "POST", config.elbv2Url(), headers, bodyBytes)
                             .async()
                             .flatMap(signedHeaders -> sendElbv2Request(target, jsonBody, signedHeaders))
                             .flatMap(result -> parseJsonResponse(result, responseType));
    }

    private Promise<HttpResult<String>> sendElbv2Request(String target,
                                                          String jsonBody,
                                                          Map<String, String> signedHeaders) {
        var builder = HttpRequest.newBuilder()
                                .uri(URI.create(config.elbv2Url()))
                                .POST(BodyPublishers.ofString(jsonBody))
                                .header("Content-Type", JSON_CONTENT_TYPE)
                                .header("X-Amz-Target", target);
        signedHeaders.forEach(builder::header);
        return http.sendString(builder.build());
    }

    // --- Secrets Manager helpers ---

    private Promise<HttpResult<String>> postSecretsManager(String jsonBody) {
        var bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        var target = "secretsmanager.GetSecretValue";
        var headers = Map.of("content-type", JSON_CONTENT_TYPE, "x-amz-target", target);
        return AwsSigV4Signer.sign(config, SECRETS_SERVICE, "POST", config.secretsManagerUrl(),
                                   headers, bodyBytes)
                             .async()
                             .flatMap(signedHeaders -> sendSecretsRequest(target, jsonBody, signedHeaders));
    }

    private Promise<HttpResult<String>> sendSecretsRequest(String target,
                                                            String jsonBody,
                                                            Map<String, String> signedHeaders) {
        var builder = HttpRequest.newBuilder()
                                .uri(URI.create(config.secretsManagerUrl()))
                                .POST(BodyPublishers.ofString(jsonBody))
                                .header("Content-Type", JSON_CONTENT_TYPE)
                                .header("X-Amz-Target", target);
        signedHeaders.forEach(builder::header);
        return http.sendString(builder.build());
    }

    private Promise<String> extractSecretString(HttpResult<String> result) {
        if (!result.isSuccess()) {
            return AwsError.fromResponse(result.statusCode(), result.body()).promise();
        }
        return AwsError.extractSecretStringField(result.body()).async();
    }

    // --- Response handling ---

    private <T> Promise<T> parseXmlResponse(HttpResult<String> result, Class<T> responseType) {
        if (result.isSuccess()) {
            return xmlMapper.readString(result.body(), responseType).async();
        }
        return AwsError.fromResponse(result.statusCode(), result.body()).promise();
    }

    private <T> Promise<T> parseJsonResponse(HttpResult<String> result, Class<T> responseType) {
        if (result.isSuccess()) {
            return jsonMapper.readString(result.body(), responseType).async();
        }
        return AwsError.fromResponse(result.statusCode(), result.body()).promise();
    }

    private Promise<Unit> checkSuccess(HttpResult<String> result) {
        if (result.isSuccess()) {
            return Promise.success(Unit.unit());
        }
        return AwsError.fromResponse(result.statusCode(), result.body()).promise();
    }

    // --- Form body builders ---

    private static String buildInstanceIdsForm(String action, List<String> instanceIds) {
        var sb = new StringBuilder("Action=").append(action).append("&Version=").append(EC2_API_VERSION);
        IntStream.range(0, instanceIds.size())
                 .forEach(i -> sb.append("&InstanceId.").append(i + 1).append("=")
                                 .append(AwsSigV4Signer.urlEncode(instanceIds.get(i))));
        return sb.toString();
    }

    private static String buildRunInstancesForm(RunInstancesRequest request) {
        var sb = new StringBuilder("Action=RunInstances&Version=").append(EC2_API_VERSION)
                     .append("&ImageId=").append(AwsSigV4Signer.urlEncode(request.imageId()))
                     .append("&InstanceType=").append(AwsSigV4Signer.urlEncode(request.instanceType()))
                     .append("&MinCount=").append(request.minCount())
                     .append("&MaxCount=").append(request.maxCount());
        request.keyName().onPresent(k -> sb.append("&KeyName=").append(AwsSigV4Signer.urlEncode(k)));
        IntStream.range(0, request.securityGroupIds().size())
                 .forEach(i -> sb.append("&SecurityGroupId.").append(i + 1).append("=")
                                 .append(AwsSigV4Signer.urlEncode(request.securityGroupIds().get(i))));
        request.subnetId().onPresent(s -> sb.append("&SubnetId=").append(AwsSigV4Signer.urlEncode(s)));
        request.userData().onPresent(u -> sb.append("&UserData=").append(AwsSigV4Signer.urlEncode(u)));
        return sb.toString();
    }

    private static String buildCreateTagsForm(List<String> resourceIds, Map<String, String> tags) {
        var sb = new StringBuilder("Action=CreateTags&Version=").append(EC2_API_VERSION);
        IntStream.range(0, resourceIds.size())
                 .forEach(i -> sb.append("&ResourceId.").append(i + 1).append("=")
                                 .append(AwsSigV4Signer.urlEncode(resourceIds.get(i))));
        var tagEntries = List.copyOf(tags.entrySet());
        IntStream.range(0, tagEntries.size())
                 .forEach(i -> appendTagParam(sb, i + 1, tagEntries.get(i)));
        return sb.toString();
    }

    private static void appendTagParam(StringBuilder sb, int index, Map.Entry<String, String> entry) {
        sb.append("&Tag.").append(index).append(".Key=").append(AwsSigV4Signer.urlEncode(entry.getKey()));
        sb.append("&Tag.").append(index).append(".Value=").append(AwsSigV4Signer.urlEncode(entry.getValue()));
    }

    private static String buildTargetGroupJson(String targetGroupArn, List<String> instanceIds) {
        var targets = instanceIds.stream()
                                 .map(id -> "{\"Id\":\"" + id + "\"}")
                                 .toList();
        return "{\"TargetGroupArn\":\"" + targetGroupArn + "\","
               + "\"Targets\":[" + String.join(",", targets) + "]}";
    }
}
