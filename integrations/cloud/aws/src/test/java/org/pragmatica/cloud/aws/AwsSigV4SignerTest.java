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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.aws.AwsConfig.awsConfig;

class AwsSigV4SignerTest {
    private static final AwsConfig CONFIG = awsConfig("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "us-east-1");
    private static final Instant FIXED_TIME = Instant.parse("2015-08-30T12:36:00Z");

    @Nested
    class SignatureComputation {

        @Test
        void sign_withEmptyBody_producesValidSignature() {
            AwsSigV4Signer.signAtTime(CONFIG, "ec2", "POST", "https://ec2.us-east-1.amazonaws.com",
                                      Map.of("content-type", "application/x-www-form-urlencoded"),
                                      "Action=DescribeInstances&Version=2016-11-15"
                                          .getBytes(StandardCharsets.UTF_8),
                                      FIXED_TIME)
                          .onFailure(cause -> assertThat(cause).isNull())
                          .onSuccess(AwsSigV4SignerTest::assertAuthorizationHeaderPresent);
        }

        @Test
        void sign_withBody_includesPayloadHash() {
            var body = "Action=RunInstances&ImageId=ami-12345&Version=2016-11-15";
            AwsSigV4Signer.signAtTime(CONFIG, "ec2", "POST", "https://ec2.us-east-1.amazonaws.com",
                                      Map.of("content-type", "application/x-www-form-urlencoded"),
                                      body.getBytes(StandardCharsets.UTF_8),
                                      FIXED_TIME)
                          .onFailure(cause -> assertThat(cause).isNull())
                          .onSuccess(AwsSigV4SignerTest::assertPayloadHashPresent);
        }

        @Test
        void sign_producesCorrectCredentialScope() {
            AwsSigV4Signer.signAtTime(CONFIG, "ec2", "POST", "https://ec2.us-east-1.amazonaws.com",
                                      Map.of(), "".getBytes(StandardCharsets.UTF_8), FIXED_TIME)
                          .onFailure(cause -> assertThat(cause).isNull())
                          .onSuccess(AwsSigV4SignerTest::assertCredentialScopeCorrect);
        }

        @Test
        void sign_producesCorrectAmzDate() {
            AwsSigV4Signer.signAtTime(CONFIG, "ec2", "POST", "https://ec2.us-east-1.amazonaws.com",
                                      Map.of(), "".getBytes(StandardCharsets.UTF_8), FIXED_TIME)
                          .onFailure(cause -> assertThat(cause).isNull())
                          .onSuccess(AwsSigV4SignerTest::assertAmzDateCorrect);
        }
    }

    @Nested
    class HashFunctions {

        @Test
        void sha256Hex_emptyInput_producesKnownHash() {
            var hash = AwsSigV4Signer.sha256Hex(new byte[0]);
            assertThat(hash).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        }

        @Test
        void sha256Hex_knownInput_producesCorrectHash() {
            var hash = AwsSigV4Signer.sha256Hex("test".getBytes(StandardCharsets.UTF_8));
            assertThat(hash).isEqualTo("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
        }

        @Test
        void hmacSha256_knownInput_producesResult() {
            AwsSigV4Signer.hmacSha256("key".getBytes(StandardCharsets.UTF_8), "data")
                          .onFailure(cause -> assertThat(cause).isNull())
                          .onSuccess(result -> assertThat(result).isNotEmpty());
        }
    }

    @Nested
    class UrlEncoding {

        @Test
        void urlEncode_spacesBecomePlus20() {
            assertThat(AwsSigV4Signer.urlEncode("hello world")).isEqualTo("hello%20world");
        }

        @Test
        void urlEncode_tildePreserved() {
            assertThat(AwsSigV4Signer.urlEncode("~test")).isEqualTo("~test");
        }
    }

    // --- Assertion helpers ---

    private static void assertAuthorizationHeaderPresent(Map<String, String> headers) {
        assertThat(headers).containsKey("Authorization");
        assertThat(headers.get("Authorization")).startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/");
    }

    private static void assertPayloadHashPresent(Map<String, String> headers) {
        assertThat(headers).containsKey("x-amz-content-sha256");
        assertThat(headers.get("x-amz-content-sha256")).hasSize(64);
    }

    private static void assertCredentialScopeCorrect(Map<String, String> headers) {
        assertThat(headers.get("Authorization")).contains("20150830/us-east-1/ec2/aws4_request");
    }

    private static void assertAmzDateCorrect(Map<String, String> headers) {
        assertThat(headers.get("X-Amz-Date")).isEqualTo("20150830T123600Z");
    }

    /// #249: the signed host must equal the sent `Host` header. Mutation that reddens it: sign `URI#getHost`
    /// alone again (MinIO on :9000 then answers SignatureDoesNotMatch).
    /// #249: vectors where botocore's (name, value) ordering differs from sorting whole `name=value` strings, plus
    /// AWS's published S3 SigV4 examples. Mutations that redden it: sort `name=value` strings (the first two
    /// cases), or drop the sort (every case with more than one parameter).
    @Nested
    class CanonicalQuery {
        @Test
        void canonicalQuery_nameIsAPrefixOfAnother_sortsByNameThenValue() {
            assertThat(AwsSigV4Signer.canonicalQuery("a-b=1&a=2")).isEqualTo("a=2&a-b=1");
            assertThat(AwsSigV4Signer.canonicalQuery("InstanceId.10=y&InstanceId.1=x")).isEqualTo("InstanceId.1=x&InstanceId.10=y");
            assertThat(AwsSigV4Signer.canonicalQuery("key.x=2&key=1")).isEqualTo("key=1&key.x=2");
        }

        @Test
        void canonicalQuery_sameName_sortsByValue_andFillsBareNames() {
            assertThat(AwsSigV4Signer.canonicalQuery("a=2&a=1")).isEqualTo("a=1&a=2");
            assertThat(AwsSigV4Signer.canonicalQuery("list-type=2&prefix=a%2F&max-keys=10"))
                .isEqualTo("list-type=2&max-keys=10&prefix=a%2F");
            assertThat(AwsSigV4Signer.canonicalQuery("uploads")).isEqualTo("uploads=");
            assertThat(AwsSigV4Signer.canonicalQuery("")).isEmpty();
        }

        /// AWS S3 documentation, "Signature Calculations for the Authorization Header", Example: GET Object.
        @Test
        void sign_awsPublishedGetObjectExample_matchesTheDocumentedSignature() {
            assertSignature("https://examplebucket.s3.amazonaws.com/test.txt",
                            Map.of("range", "bytes=0-9"),
                            "f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
        }

        /// Same documentation, Example: GET Bucket (List Objects), `?max-keys=2&prefix=J`, given out of order.
        @Test
        void sign_awsPublishedListObjectsExample_matchesTheDocumentedSignature() {
            assertSignature("https://examplebucket.s3.amazonaws.com/?prefix=J&max-keys=2",
                            Map.of(),
                            "34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7");
        }

        private static void assertSignature(String url, Map<String, String> headers, String expected) {
            var config = awsConfig("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");

            AwsSigV4Signer.signAtTime(config, "s3", "GET", url, headers, new byte[0], Instant.parse("2013-05-24T00:00:00Z"))
                          .onFailure(cause -> assertThat(cause).isNull())
                          .onSuccess(signed -> assertThat(signed.get("Authorization")).endsWith("Signature=" + expected));
        }
    }

    @Nested
    class CanonicalHost {
        @Test
        void canonicalHost_nonDefaultPort_isIncluded() {
            assertThat(AwsSigV4Signer.canonicalHost("http://localhost:9000/bucket/key"))
                                           .isEqualTo("localhost:9000");
        }

        @Test
        void canonicalHost_defaultOrAbsentPort_isOmitted() {
            assertThat(AwsSigV4Signer.canonicalHost("https://s3.eu-central-1.amazonaws.com/k"))
                                           .isEqualTo("s3.eu-central-1.amazonaws.com");
            assertThat(AwsSigV4Signer.canonicalHost("https://s3.example.com:443/k"))
                                           .isEqualTo("s3.example.com");
            assertThat(AwsSigV4Signer.canonicalHost("http://minio:80/k"))
                                           .isEqualTo("minio");
        }
    }
}
