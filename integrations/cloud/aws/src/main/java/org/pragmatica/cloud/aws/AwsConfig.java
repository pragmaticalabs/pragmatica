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

import org.pragmatica.lang.Option;

/// Configuration for the AWS Cloud API client.
///
/// [#endpointOverride] is an optional service endpoint base URL. When present, all three service
/// URLs resolve to it instead of the region-derived public AWS endpoints — used to retarget the
/// client at a local emulator (e.g. a LocalStack edge endpoint) for contract testing. It is added
/// additively: the region-based [#awsConfig] factory and every existing caller are unaffected
/// (default is [Option#empty]), mirroring the additive `RunInstancesRequest` spot-options change.
public record AwsConfig(String accessKeyId, String secretAccessKey, String region, Option<String> endpointOverride) {
    /// Creates AWS configuration targeting the public AWS endpoints for the region.
    public static AwsConfig awsConfig(String accessKeyId, String secretAccessKey, String region) {
        return new AwsConfig(accessKeyId, secretAccessKey, region, Option.empty());
    }

    /// Returns a copy that routes every service URL to the given endpoint base (e.g. a LocalStack
    /// edge endpoint) instead of the region-derived public AWS endpoints.
    public AwsConfig withEndpointOverride(String endpointUrl) {
        return new AwsConfig(accessKeyId, secretAccessKey, region, Option.some(endpointUrl));
    }

    /// EC2 service base URL.
    public String ec2Url() {
        return endpointOverride.or("https://ec2." + region + ".amazonaws.com");
    }

    /// ELBv2 service base URL.
    public String elbv2Url() {
        return endpointOverride.or("https://elasticloadbalancing." + region + ".amazonaws.com");
    }

    /// Secrets Manager service base URL.
    public String secretsManagerUrl() {
        return endpointOverride.or("https://secretsmanager." + region + ".amazonaws.com");
    }

    @Override
    public String toString() {
        return "AwsConfig[accessKeyId=" + accessKeyId + ", secretAccessKey=REDACTED, region=" + region
             + ", endpointOverride=" + endpointOverride + "]";
    }
}
