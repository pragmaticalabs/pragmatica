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
        return new S3Config("https://s3." + region + ".amazonaws.com",
                            bucket,
                            region,
                            accessKeyId,
                            secretAccessKey,
                            false);
    }

    /// Creates S3 configuration for MinIO or S3-compatible storage with path-style URLs.
    public static S3Config s3Config(String endpoint,
                                    String bucket,
                                    String region,
                                    String accessKeyId,
                                    String secretAccessKey) {
        return new S3Config(endpoint, bucket, region, accessKeyId, secretAccessKey, true);
    }

    /// Endpoint, bucket and addressing style only, for a client whose credentials come from an
    /// [org.pragmatica.cloud.aws.AwsCredentialsProvider] rather than from this record: the static key
    /// fields stay empty and are never read by such a client.
    public static S3Config s3Endpoint(String endpoint, String bucket, String region, boolean pathStyle) {
        return new S3Config(endpoint, bucket, region, "", "", pathStyle);
    }

    /// Builds the full URL for an object key.
    /// Path style: http://minio:9000/bucket/key
    /// Virtual-hosted: https://bucket.s3.region.amazonaws.com/key (the bucket prefixed to the endpoint's host)
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
               : bucket + "." + extractHost(endpoint);
    }

    /// Converts to AwsConfig for SigV4 signing.
    public AwsConfig toAwsConfig() {
        return AwsConfig.awsConfig(accessKeyId, secretAccessKey, region);
    }

    /// The endpoint with the bucket prefixed to its host. For the AWS factory's endpoint
    /// (`https://s3.<region>.amazonaws.com`) that is `https://<bucket>.s3.<region>.amazonaws.com`, as before;
    /// for another provider's endpoint it is that provider's virtual-hosted form instead of an AWS host.
    private String virtualHostedBase() {
        var schemeEnd = endpoint.indexOf("://");
        var scheme = schemeEnd >= 0
                     ? endpoint.substring(0, schemeEnd + 3)
                     : "https://";

        return scheme + bucket + "." + extractHost(endpoint);
    }

    private static String extractHost(String url) {
        var withoutScheme = url.contains("://")
                            ? url.substring(url.indexOf("://") + 3)
                            : url;
        var slashIdx = withoutScheme.indexOf('/');

        return slashIdx >= 0
               ? withoutScheme.substring(0, slashIdx)
               : withoutScheme;
    }

    @Override
    public String toString() {
        return "S3Config[endpoint=" + endpoint
             + ", bucket=" + bucket
             + ", region=" + region
             + ", accessKeyId=" + accessKeyId
             + ", secretAccessKey=REDACTED"
             + ", pathStyle=" + pathStyle
             + "]";
    }
}
