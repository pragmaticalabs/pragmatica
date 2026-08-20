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

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.pragmatica.cloud.aws.api.CreateSecurityGroupResponse;
import org.pragmatica.cloud.aws.api.DescribeInstancesResponse;
import org.pragmatica.cloud.aws.api.DescribeSecurityGroupsResponse;
import org.pragmatica.cloud.aws.api.DescribeSubnetsResponse;
import org.pragmatica.cloud.aws.api.DescribeTargetHealthResponse;
import org.pragmatica.cloud.aws.api.RunInstancesRequest;
import org.pragmatica.cloud.aws.api.RunInstancesResponse;
import org.pragmatica.cloud.aws.api.SecurityGroup;
import org.pragmatica.cloud.aws.api.TargetHealth;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.xml.XmlMapper;


/// AWS Cloud API client with Promise-based async operations.
///
/// Wire protocol per service family: EC2 and ELBv2 are AWS **Query** services (form-encoded
/// `Action`/`Version` requests, XML responses); Secrets Manager is a **JSON** service
/// (`application/x-amz-json-1.1` + `X-Amz-Target`). All requests are SigV4-signed and bounded by
/// a per-request timeout so no operation can leave its [Promise] unresolved.
public interface AwsClient {
    // --- EC2 operations ---
    /// Launches new EC2 instances.
    Promise<RunInstancesResponse> runInstances(RunInstancesRequest request);
    /// Terminates EC2 instances by ID.
    Promise<Unit> terminateInstances(List<String> instanceIds);
    /// Describes all EC2 instances.
    Promise<DescribeInstancesResponse> describeInstances();
    /// Describes EC2 instances matching a tag filter (`tag:{key}` = value).
    Promise<DescribeInstancesResponse> describeInstances(String tagKey, String tagValue);
    /// Describes a single EC2 instance by its native instance id (`InstanceId.1`).
    Promise<DescribeInstancesResponse> describeInstancesById(String instanceId);
    /// Reboots EC2 instances by ID.
    Promise<Unit> rebootInstances(List<String> instanceIds);
    /// Creates tags on EC2 resources.
    Promise<Unit> createTags(List<String> resourceIds, Map<String, String> tags);
    // --- EC2 security group operations ---
    /// Creates a security group and returns its native id. An absent `vpcId` targets the account's
    /// default VPC, matching EC2's own default for the omitted parameter.
    ///
    /// Unlike its inverse [#deleteSecurityGroup] this call is **not** idempotent, and deliberately
    /// so: a duplicate name fails with `InvalidGroup.Duplicate`, whose body carries no group id, so
    /// there is no id to resolve the Promise with. Callers wanting create-or-reuse read
    /// [#describeSecurityGroups] first and create only on an empty result.
    Promise<String> createSecurityGroup(String name, String description, Option<String> vpcId);
    /// Describes security groups matching every supplied `tag:{key}` = value filter (`Filter.N`,
    /// numbered from 1). A group that no longer exists yields an empty list rather than a failure,
    /// so a teardown read after deletion is idempotent.
    Promise<List<SecurityGroup>> describeSecurityGroups(Map<String, String> tagFilters);
    /// Authorizes one inbound CIDR rule on a security group. A rule that already exists
    /// (`InvalidPermission.Duplicate`) resolves as success, so re-bootstrapping over an existing
    /// firewall is idempotent.
    Promise<Unit> authorizeSecurityGroupIngress(String groupId,
                                                String protocol,
                                                int port,
                                                String cidr,
                                                String description);
    /// Revokes one inbound CIDR rule from a security group. An already-absent rule
    /// (`InvalidPermission.NotFound`) or an already-deleted group (`InvalidGroup.NotFound`)
    /// resolves as success, so teardown is idempotent and order-insensitive.
    Promise<Unit> revokeSecurityGroupIngress(String groupId, String protocol, int port, String cidr);
    /// Deletes a security group. An already-deleted group (`InvalidGroup.NotFound`) resolves as
    /// success, so repeated teardown is idempotent.
    Promise<Unit> deleteSecurityGroup(String groupId);
    /// The VPC a subnet belongs to, or empty when the subnet is unknown. Used to place a new security
    /// group in the same VPC as the instances that will carry it — a group in the wrong VPC cannot be
    /// attached, and `RunInstances` rejects the pair.
    Promise<Option<String>> vpcOfSubnet(String subnetId);
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
record AwsClientRecord(AwsConfig config, HttpOperations http, JsonMapper jsonMapper, XmlMapper xmlMapper) implements AwsClient {
    private static final String EC2_API_VERSION = "2016-11-15";
    private static final String ELB_API_VERSION = "2015-12-01";
    private static final String EC2_SERVICE = "ec2";
    private static final String ELB_SERVICE = "elasticloadbalancing";
    private static final String SECRETS_SERVICE = "secretsmanager";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String JSON_CONTENT_TYPE = "application/x-amz-json-1.1";
    /// Bounds every request so a stalled/unanswered service can never leave a Promise unresolved.
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /// EC2 error codes reporting that the call's end-state already holds. Absorbing them is
    /// **design-out**, not error recovery: the postcondition each operation promises (rule present /
    /// rule absent / group absent) is already satisfied by the very error, so there is nothing to
    /// compensate (no BER) and nothing degraded (no FER). The tolerance is per-operation and
    /// code-exact — any other AWS error code still fails the Promise, which
    /// `authorizeSecurityGroupIngress_fails_forUnrelatedErrorCode` pins.
    private static final Set<String> RULE_ALREADY_PRESENT = Set.of("InvalidPermission.Duplicate");
    private static final Set<String> RULE_ALREADY_ABSENT = Set.of("InvalidPermission.NotFound", "InvalidGroup.NotFound");
    private static final Set<String> GROUP_ALREADY_ABSENT = Set.of("InvalidGroup.NotFound");
    private static final AwsError MISSING_GROUP_ID =
        new AwsError.ParseError("Missing groupId in CreateSecurityGroup response", Option.none());

    @Override
    public Promise<RunInstancesResponse> runInstances(RunInstancesRequest request) {
        return postQuery(EC2_SERVICE, config.ec2Url(), buildRunInstancesForm(request), RunInstancesResponse.class);
    }

    @Override
    public Promise<Unit> terminateInstances(List<String> instanceIds) {
        return postQueryDiscarding(EC2_SERVICE, config.ec2Url(), buildInstanceIdsForm("TerminateInstances", instanceIds));
    }

    @Override
    public Promise<DescribeInstancesResponse> describeInstances() {
        return postQuery(EC2_SERVICE,
                         config.ec2Url(),
                         "Action=DescribeInstances&Version=" + EC2_API_VERSION,
                         DescribeInstancesResponse.class);
    }

    @Override
    public Promise<DescribeInstancesResponse> describeInstances(String tagKey, String tagValue) {
        return postQuery(EC2_SERVICE, config.ec2Url(), buildTagFilterForm(tagKey, tagValue), DescribeInstancesResponse.class);
    }

    @Override
    public Promise<DescribeInstancesResponse> describeInstancesById(String instanceId) {
        var formBody = "Action=DescribeInstances&Version=" + EC2_API_VERSION
                     + "&InstanceId.1=" + AwsSigV4Signer.urlEncode(instanceId);

        return postQuery(EC2_SERVICE, config.ec2Url(), formBody, DescribeInstancesResponse.class);
    }

    @Override
    public Promise<Unit> rebootInstances(List<String> instanceIds) {
        return postQueryDiscarding(EC2_SERVICE, config.ec2Url(), buildInstanceIdsForm("RebootInstances", instanceIds));
    }

    @Override
    public Promise<Unit> createTags(List<String> resourceIds, Map<String, String> tags) {
        return postQueryDiscarding(EC2_SERVICE, config.ec2Url(), buildCreateTagsForm(resourceIds, tags));
    }

    @Override
    public Promise<String> createSecurityGroup(String name, String description, Option<String> vpcId) {
        return postQuery(EC2_SERVICE,
                         config.ec2Url(),
                         buildCreateSecurityGroupForm(name, description, vpcId),
                         CreateSecurityGroupResponse.class).flatMap(AwsClientRecord::extractGroupId);
    }

    @Override
    public Promise<List<SecurityGroup>> describeSecurityGroups(Map<String, String> tagFilters) {
        return signAndSendQuery(EC2_SERVICE,
                                config.ec2Url(),
                                buildDescribeSecurityGroupsForm(tagFilters)).flatMap(this::parseSecurityGroups);
    }

    @Override
    public Promise<Unit> authorizeSecurityGroupIngress(String groupId,
                                                       String protocol,
                                                       int port,
                                                       String cidr,
                                                       String description) {
        return postQueryTolerating(EC2_SERVICE,
                                   config.ec2Url(),
                                   buildAuthorizeIngressForm(groupId, protocol, port, cidr, description),
                                   RULE_ALREADY_PRESENT);
    }

    @Override
    public Promise<Unit> revokeSecurityGroupIngress(String groupId, String protocol, int port, String cidr) {
        return postQueryTolerating(EC2_SERVICE,
                                   config.ec2Url(),
                                   buildRevokeIngressForm(groupId, protocol, port, cidr),
                                   RULE_ALREADY_ABSENT);
    }

    @Override
    public Promise<Unit> deleteSecurityGroup(String groupId) {
        return postQueryTolerating(EC2_SERVICE,
                                   config.ec2Url(),
                                   buildDeleteSecurityGroupForm(groupId),
                                   GROUP_ALREADY_ABSENT);
    }

    @Override
    public Promise<Unit> registerTargets(String targetGroupArn, List<String> instanceIds) {
        return postQueryDiscarding(ELB_SERVICE,
                                   config.elbv2Url(),
                                   buildTargetsForm("RegisterTargets", targetGroupArn, instanceIds));
    }

    @Override
    public Promise<Unit> deregisterTargets(String targetGroupArn, List<String> instanceIds) {
        return postQueryDiscarding(ELB_SERVICE,
                                   config.elbv2Url(),
                                   buildTargetsForm("DeregisterTargets", targetGroupArn, instanceIds));
    }

    @Override
    public Promise<List<TargetHealth>> describeTargetHealth(String targetGroupArn) {
        return postQuery(ELB_SERVICE,
                         config.elbv2Url(),
                         buildDescribeTargetHealthForm(targetGroupArn),
                         DescribeTargetHealthResponse.class).map(DescribeTargetHealthResponse::toTargetHealthList);
    }

    @Override
    public Promise<String> getSecretValue(String secretId) {
        var jsonBody = "{\"SecretId\":\"" + secretId + "\"}";

        return postSecretsManager(jsonBody).flatMap(this::extractSecretString);
    }

    // --- Query-protocol (EC2 + ELBv2) helpers: form-encoded request, XML response ---
    private <T> Promise<T> postQuery(String service, String url, String formBody, Class<T> responseType) {
        return signAndSendQuery(service, url, formBody).flatMap(result -> parseXmlResponse(result, responseType));
    }

    private Promise<Unit> postQueryDiscarding(String service, String url, String formBody) {
        return signAndSendQuery(service, url, formBody).flatMap(this::checkSuccess);
    }

    /// Like [#postQueryDiscarding] but resolves the listed AWS error codes as success — see
    /// [#RULE_ALREADY_PRESENT] for why that is design-out rather than error recovery.
    private Promise<Unit> postQueryTolerating(String service, String url, String formBody, Set<String> toleratedCodes) {
        return signAndSendQuery(service,
                                url,
                                formBody).flatMap(result -> checkSuccessTolerating(result, toleratedCodes));
    }

    private Promise<HttpResult<String>> signAndSendQuery(String service, String url, String formBody) {
        var bodyBytes = formBody.getBytes(StandardCharsets.UTF_8);

        return AwsSigV4Signer.sign(config,
                                   service,
                                   "POST",
                                   url,
                                   Map.of("content-type", FORM_CONTENT_TYPE),
                                   bodyBytes)
                             .async()
                             .flatMap(signedHeaders -> sendFormRequest(url, formBody, signedHeaders));
    }

    private Promise<HttpResult<String>> sendFormRequest(String url, String formBody, Map<String, String> signedHeaders) {
        var builder = HttpRequest.newBuilder()
                                 .uri(URI.create(url))
                                 .timeout(REQUEST_TIMEOUT)
                                 .POST(BodyPublishers.ofString(formBody))
                                 .header("Content-Type", FORM_CONTENT_TYPE);

        signedHeaders.forEach(builder::header);

        return http.sendString(builder.build());
    }

    // --- Secrets Manager helpers (JSON protocol) ---
    private Promise<HttpResult<String>> postSecretsManager(String jsonBody) {
        var bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        var target = "secretsmanager.GetSecretValue";
        var headers = Map.of("content-type", JSON_CONTENT_TYPE, "x-amz-target", target);

        return AwsSigV4Signer.sign(config,
                                   SECRETS_SERVICE,
                                   "POST",
                                   config.secretsManagerUrl(),
                                   headers,
                                   bodyBytes)
                             .async()
                             .flatMap(signedHeaders -> sendSecretsRequest(target, jsonBody, signedHeaders));
    }

    private Promise<HttpResult<String>> sendSecretsRequest(String target,
                                                           String jsonBody,
                                                           Map<String, String> signedHeaders) {
        var builder = HttpRequest.newBuilder()
                                 .uri(URI.create(config.secretsManagerUrl()))
                                 .timeout(REQUEST_TIMEOUT)
                                 .POST(BodyPublishers.ofString(jsonBody))
                                 .header("Content-Type", JSON_CONTENT_TYPE)
                                 .header("X-Amz-Target", target);

        signedHeaders.forEach(builder::header);

        return http.sendString(builder.build());
    }

    private Promise<String> extractSecretString(HttpResult<String> result) {
        if (!result.isSuccess()) {
            return AwsError.fromResponse(result.statusCode(),
                                         result.body())
                           .promise();
        }

        return AwsError.extractSecretStringField(result.body()).async();
    }

    // --- Response handling ---
    private <T> Promise<T> parseXmlResponse(HttpResult<String> result, Class<T> responseType) {
        if (result.isSuccess()) {
            return xmlMapper.readString(result.body(),
                                        responseType)
                            .async();
        }

        return AwsError.fromResponse(result.statusCode(),
                                     result.body())
                       .promise();
    }

    private Promise<Unit> checkSuccess(HttpResult<String> result) {
        if (result.isSuccess()) {
            return Promise.success(Unit.unit());
        }

        return AwsError.fromResponse(result.statusCode(),
                                     result.body())
                       .promise();
    }

    private Promise<Unit> checkSuccessTolerating(HttpResult<String> result, Set<String> toleratedCodes) {
        if (result.isSuccess()) {
            return Promise.success(Unit.unit());
        }

        var error = AwsError.fromResponse(result.statusCode(), result.body());

        return isTolerated(error, toleratedCodes)
               ? Promise.success(Unit.unit())
               : error.promise();
    }

    private Promise<List<SecurityGroup>> parseSecurityGroups(HttpResult<String> result) {
        if (result.isSuccess()) {
            return xmlMapper.readString(result.body(),
                                        DescribeSecurityGroupsResponse.class)
                            .map(DescribeSecurityGroupsResponse::securityGroups)
                            .async();
        }

        var error = AwsError.fromResponse(result.statusCode(), result.body());

        return isTolerated(error, GROUP_ALREADY_ABSENT)
               ? Promise.success(List.of())
               : error.promise();
    }

    private static boolean isTolerated(AwsError error, Set<String> toleratedCodes) {
        return error instanceof AwsError.ApiError apiError && toleratedCodes.contains(apiError.code());
    }

    private static Promise<String> extractGroupId(CreateSecurityGroupResponse response) {
        return Option.option(response.groupId())
                     .async(MISSING_GROUP_ID);
    }

    // --- Form body builders ---
    private static String buildTagFilterForm(String tagKey, String tagValue) {
        return "Action=DescribeInstances&Version=" + EC2_API_VERSION
             + "&Filter.1.Name=tag:" + AwsSigV4Signer.urlEncode(tagKey)
             + "&Filter.1.Value.1=" + AwsSigV4Signer.urlEncode(tagValue);
    }

    private static String buildInstanceIdsForm(String action, List<String> instanceIds) {
        var sb = new StringBuilder("Action=").append(action).append("&Version=").append(EC2_API_VERSION);

        IntStream.range(0,
                        instanceIds.size())
                 .forEach(i -> sb.append("&InstanceId.")
                                 .append(i + 1)
                                 .append("=")
                                 .append(AwsSigV4Signer.urlEncode(instanceIds.get(i))));

        return sb.toString();
    }

    private static String buildRunInstancesForm(RunInstancesRequest request) {
        var sb = new StringBuilder("Action=RunInstances&Version=").append(EC2_API_VERSION)
                                                                  .append("&ImageId=")
                                                                  .append(AwsSigV4Signer.urlEncode(request.imageId()))
                                                                  .append("&InstanceType=")
                                                                  .append(AwsSigV4Signer.urlEncode(request.instanceType()))
                                                                  .append("&MinCount=")
                                                                  .append(request.minCount())
                                                                  .append("&MaxCount=")
                                                                  .append(request.maxCount());

        request.keyName().onPresent(k -> sb.append("&KeyName=")
                                           .append(AwsSigV4Signer.urlEncode(k)));
        IntStream.range(0,
                        request.securityGroupIds().size())
                 .forEach(i -> sb.append("&SecurityGroupId.")
                                 .append(i + 1)
                                 .append("=")
                                 .append(AwsSigV4Signer.urlEncode(request.securityGroupIds().get(i))));
        request.subnetId().onPresent(s -> sb.append("&SubnetId=")
                                            .append(AwsSigV4Signer.urlEncode(s)));
        request.userData().onPresent(u -> sb.append("&UserData=")
                                            .append(AwsSigV4Signer.urlEncode(u)));
        request.availabilityZone()
               .onPresent(az -> sb.append("&Placement.AvailabilityZone=")
                                  .append(AwsSigV4Signer.urlEncode(az)));
        request.spotMarketOptions()
               .onPresent(spot -> appendSpotMarketOptions(sb, spot));

        return sb.toString();
    }

    private static void appendSpotMarketOptions(StringBuilder sb, RunInstancesRequest.SpotMarketOptions spot) {
        sb.append("&InstanceMarketOptions.MarketType=spot");
        spot.maxPrice()
            .onPresent(price -> sb.append("&InstanceMarketOptions.SpotOptions.MaxPrice=")
                                  .append(AwsSigV4Signer.urlEncode(price)));
        sb.append("&InstanceMarketOptions.SpotOptions.InstanceInterruptionBehavior=")
          .append(AwsSigV4Signer.urlEncode(spot.interruptionBehavior()));
    }

    private static String buildCreateTagsForm(List<String> resourceIds, Map<String, String> tags) {
        var sb = new StringBuilder("Action=CreateTags&Version=").append(EC2_API_VERSION);

        IntStream.range(0,
                        resourceIds.size())
                 .forEach(i -> sb.append("&ResourceId.")
                                 .append(i + 1)
                                 .append("=")
                                 .append(AwsSigV4Signer.urlEncode(resourceIds.get(i))));
        var tagEntries = List.copyOf(tags.entrySet());

        IntStream.range(0, tagEntries.size()).forEach(i -> appendTagParam(sb, i + 1, tagEntries.get(i)));

        return sb.toString();
    }

    private static void appendTagParam(StringBuilder sb, int index, Map.Entry<String, String> entry) {
        sb.append("&Tag.").append(index).append(".Key=").append(AwsSigV4Signer.urlEncode(entry.getKey()));
        sb.append("&Tag.").append(index).append(".Value=").append(AwsSigV4Signer.urlEncode(entry.getValue()));
    }

    // --- EC2 security group form builders ---
    private static String buildCreateSecurityGroupForm(String name, String description, Option<String> vpcId) {
        var sb = new StringBuilder("Action=CreateSecurityGroup&Version=").append(EC2_API_VERSION)
                                                                        .append("&GroupName=")
                                                                        .append(AwsSigV4Signer.urlEncode(name))
                                                                        .append("&GroupDescription=")
                                                                        .append(AwsSigV4Signer.urlEncode(description));

        vpcId.onPresent(v -> sb.append("&VpcId=")
                               .append(AwsSigV4Signer.urlEncode(v)));

        return sb.toString();
    }

    /// Emits `Filter.N.Name=tag:{key}&Filter.N.Value.1={value}` for each entry, N starting at 1.
    /// Filter order follows the map's own iteration order — EC2 ANDs the filters regardless, so
    /// order carries no meaning on the wire, and the caller picks an ordered map when it wants a
    /// reproducible body.
    private static String buildDescribeSecurityGroupsForm(Map<String, String> tagFilters) {
        var sb = new StringBuilder("Action=DescribeSecurityGroups&Version=").append(EC2_API_VERSION);
        var filterEntries = List.copyOf(tagFilters.entrySet());

        IntStream.range(0, filterEntries.size()).forEach(i -> appendTagFilter(sb, i + 1, filterEntries.get(i)));

        return sb.toString();
    }

    private static void appendTagFilter(StringBuilder sb, int index, Map.Entry<String, String> entry) {
        sb.append("&Filter.").append(index).append(".Name=tag:").append(AwsSigV4Signer.urlEncode(entry.getKey()));
        sb.append("&Filter.").append(index).append(".Value.1=").append(AwsSigV4Signer.urlEncode(entry.getValue()));
    }

    private static String buildAuthorizeIngressForm(String groupId,
                                                    String protocol,
                                                    int port,
                                                    String cidr,
                                                    String description) {
        return buildIngressRuleForm("AuthorizeSecurityGroupIngress", groupId, protocol, port, cidr)
             + "&IpPermissions.1.IpRanges.1.Description=" + AwsSigV4Signer.urlEncode(description);
    }

    /// EC2 ignores a description on revoke — the rule is matched by protocol, port range and CIDR
    /// alone — so [AwsClient#revokeSecurityGroupIngress] takes none and none is emitted.
    private static String buildRevokeIngressForm(String groupId, String protocol, int port, String cidr) {
        return buildIngressRuleForm("RevokeSecurityGroupIngress", groupId, protocol, port, cidr);
    }

    private static String buildIngressRuleForm(String action, String groupId, String protocol, int port, String cidr) {
        return "Action=" + action + "&Version=" + EC2_API_VERSION
             + "&GroupId=" + AwsSigV4Signer.urlEncode(groupId)
             + "&IpPermissions.1.IpProtocol=" + AwsSigV4Signer.urlEncode(protocol)
             + "&IpPermissions.1.FromPort=" + port
             + "&IpPermissions.1.ToPort=" + port
             + "&IpPermissions.1.IpRanges.1.CidrIp=" + AwsSigV4Signer.urlEncode(cidr);
    }

    @Override
    public Promise<Option<String>> vpcOfSubnet(String subnetId) {
        return postQuery(EC2_SERVICE,
                         config.ec2Url(),
                         buildDescribeSubnetsForm(subnetId),
                         DescribeSubnetsResponse.class).map(DescribeSubnetsResponse::vpcId);
    }

    private static String buildDescribeSubnetsForm(String subnetId) {
        return "Action=DescribeSubnets&Version=" + EC2_API_VERSION
             + "&SubnetId.1=" + AwsSigV4Signer.urlEncode(subnetId);
    }

    private static String buildDeleteSecurityGroupForm(String groupId) {
        return "Action=DeleteSecurityGroup&Version=" + EC2_API_VERSION
             + "&GroupId=" + AwsSigV4Signer.urlEncode(groupId);
    }

    // --- ELBv2 Query-protocol form builders ---
    private static String buildTargetsForm(String action, String targetGroupArn, List<String> instanceIds) {
        var sb = new StringBuilder("Action=").append(action)
                                             .append("&Version=")
                                             .append(ELB_API_VERSION)
                                             .append("&TargetGroupArn=")
                                             .append(AwsSigV4Signer.urlEncode(targetGroupArn));

        IntStream.range(0,
                        instanceIds.size())
                 .forEach(i -> sb.append("&Targets.member.")
                                 .append(i + 1)
                                 .append(".Id=")
                                 .append(AwsSigV4Signer.urlEncode(instanceIds.get(i))));

        return sb.toString();
    }

    private static String buildDescribeTargetHealthForm(String targetGroupArn) {
        return "Action=DescribeTargetHealth&Version=" + ELB_API_VERSION
             + "&TargetGroupArn=" + AwsSigV4Signer.urlEncode(targetGroupArn);
    }
}
