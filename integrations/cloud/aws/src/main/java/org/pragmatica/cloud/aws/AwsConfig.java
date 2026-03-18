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

/// Configuration for the AWS Cloud API client.
public record AwsConfig(String accessKeyId, String secretAccessKey, String region) {
    /// Creates AWS configuration.
    public static AwsConfig awsConfig(String accessKeyId, String secretAccessKey, String region) {
        return new AwsConfig(accessKeyId, secretAccessKey, region);
    }

    /// EC2 service base URL.
    public String ec2Url() {
        return "https://ec2." + region + ".amazonaws.com";
    }

    /// ELBv2 service base URL.
    public String elbv2Url() {
        return "https://elasticloadbalancing." + region + ".amazonaws.com";
    }

    /// Secrets Manager service base URL.
    public String secretsManagerUrl() {
        return "https://secretsmanager." + region + ".amazonaws.com";
    }
}
