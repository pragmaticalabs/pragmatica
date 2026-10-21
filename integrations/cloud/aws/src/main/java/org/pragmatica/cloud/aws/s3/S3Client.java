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

import java.time.Duration;
import java.util.List;

import org.pragmatica.cloud.aws.AwsCredentials;
import org.pragmatica.cloud.aws.AwsCredentialsProvider;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// S3-compatible object storage client.
/// Supports AWS S3 (virtual-hosted URLs) and MinIO (path-style URLs).
/// Uses SigV4 signing and Promise-based async operations.
public interface S3Client {
    /// Stores an object in the bucket.
    Promise<Unit> putObject(String key, byte[] content, String contentType);
    /// Retrieves an object from the bucket. Returns None only if the object is not found (404); any other
    /// failure, including the provider being unavailable, fails the promise.
    Promise<Option<byte[]>> getObject(String key);
    /// Checks whether an object exists in the bucket: false only on a 404, any other failure fails the promise.
    Promise<Boolean> headObject(String key);
    /// Deletes an object from the bucket.
    Promise<Unit> deleteObject(String key);
    /// Lists object keys matching a prefix.
    Promise<List<String>> listObjects(String prefix, int maxKeys);

    /// Creates an S3Client with default HTTP operations.
    static S3Client s3Client(S3Config config) {
        return s3Client(config, JdkHttpOperations.jdkHttpOperations());
    }

    /// Creates an S3Client with custom HTTP operations (for testing).
    static S3Client s3Client(S3Config config, HttpOperations http) {
        return s3Client(config,
                        http,
                        AwsCredentialsProvider.staticCredentials(AwsCredentials.awsCredentials(config.accessKeyId(),
                                                                                               config.secretAccessKey())),
                        Option.none());
    }

    /// Creates an S3Client that signs with what `credentials` answers per request (the static keys in
    /// `config` are not read) and bounds each HTTP exchange by `requestTimeout` when present.
    static S3Client s3Client(S3Config config,
                             HttpOperations http,
                             AwsCredentialsProvider credentials,
                             Option<Duration> requestTimeout) {
        return new RestS3Client(config, http, credentials, requestTimeout);
    }
}
