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

package org.pragmatica.cloud.aws.s3;

import org.pragmatica.cloud.aws.AwsConfig;

/// S3-compatible object storage configuration.
/// Supports both AWS S3 (virtual-hosted style) and MinIO/S3-compatible (path style).
public record S3Config(String endpoint,
                       String bucket,
                       String region,
                       String accessKeyId,
                       String secretAccessKey,
                       boolean pathStyle) {

    /// Creates S3 configuration for AWS S3 with virtual-hosted URL style.
    public static S3Config s3Config(String bucket, String region, String accessKeyId, String secretAccessKey) {
        return new S3Config("https://s3." + region + ".amazonaws.com", bucket, region,
                            accessKeyId, secretAccessKey, false);
    }

    /// Creates S3 configuration for MinIO or S3-compatible storage with path-style URLs.
    public static S3Config s3Config(String endpoint, String bucket, String region,
                                    String accessKeyId, String secretAccessKey) {
        return new S3Config(endpoint, bucket, region, accessKeyId, secretAccessKey, true);
    }

    /// Builds the full URL for an object key.
    /// Path style: http://minio:9000/bucket/key
    /// Virtual-hosted: https://bucket.s3.region.amazonaws.com/key
    public String objectUrl(String key) {
        return pathStyle
               ? endpoint + "/" + bucket + "/" + key
               : virtualHostedBase() + "/" + key;
    }

    /// Builds the bucket-level URL (for listing).
    /// Path style: http://minio:9000/bucket
    /// Virtual-hosted: https://bucket.s3.region.amazonaws.com
    public String bucketUrl() {
        return pathStyle
               ? endpoint + "/" + bucket
               : virtualHostedBase();
    }

    /// Extracts the host portion for signing.
    public String signingHost() {
        return pathStyle
               ? extractHost(endpoint)
               : bucket + ".s3." + region + ".amazonaws.com";
    }

    /// Converts to AwsConfig for SigV4 signing.
    public AwsConfig toAwsConfig() {
        return AwsConfig.awsConfig(accessKeyId, secretAccessKey, region);
    }

    private String virtualHostedBase() {
        return "https://" + bucket + ".s3." + region + ".amazonaws.com";
    }

    private static String extractHost(String url) {
        var withoutScheme = url.contains("://") ? url.substring(url.indexOf("://") + 3) : url;
        var slashIdx = withoutScheme.indexOf('/');
        return slashIdx >= 0 ? withoutScheme.substring(0, slashIdx) : withoutScheme;
    }
}
