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
}
