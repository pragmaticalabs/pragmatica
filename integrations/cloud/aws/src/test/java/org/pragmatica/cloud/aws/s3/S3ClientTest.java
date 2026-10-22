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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.cloud.aws.AwsCredentials;
import org.pragmatica.cloud.aws.AwsCredentialsProvider;
import org.pragmatica.cloud.aws.AwsError;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class S3ClientTest {
    private static final S3Config AWS_CONFIG = S3Config.s3Config("test-bucket", "us-east-1",
                                                                  "AKIDTEST", "secretkey123");
    private static final S3Config MINIO_CONFIG = S3Config.s3Config("http://minio:9000", "test-bucket",
                                                                    "us-east-1", "minioadmin", "minioadmin");

    private final AtomicReference<HttpRequest> capturedRequest = new AtomicReference<>();
    private TestHttpOperations testHttp;
    private S3Client awsClient;
    private S3Client minioClient;

    @BeforeEach
    void setUp() {
        testHttp = new TestHttpOperations(capturedRequest);
        awsClient = S3Client.s3Client(AWS_CONFIG, testHttp);
        minioClient = S3Client.s3Client(MINIO_CONFIG, testHttp);
    }

    @Nested
    class UrlConstruction {

        @Test
        void pathStyleUrl_minioFormat() {
            assertThat(MINIO_CONFIG.objectUrl("data/file.bin"))
                .isEqualTo("http://minio:9000/test-bucket/data/file.bin");
        }

        @Test
        void virtualHostedUrl_awsFormat() {
            assertThat(AWS_CONFIG.objectUrl("data/file.bin"))
                .isEqualTo("https://test-bucket.s3.us-east-1.amazonaws.com/data/file.bin");
        }

        @Test
        void bucketUrl_pathStyle() {
            assertThat(MINIO_CONFIG.bucketUrl())
                .isEqualTo("http://minio:9000/test-bucket");
        }

        @Test
        void bucketUrl_virtualHosted() {
            assertThat(AWS_CONFIG.bucketUrl())
                .isEqualTo("https://test-bucket.s3.us-east-1.amazonaws.com");
        }
    }

    @Nested
    class PutObject {

        @Test
        void putObject_success_returnsUnit() {
            testHttp.respondWithString(200, "");

            awsClient.putObject("test-key", "hello".getBytes(StandardCharsets.UTF_8), "text/plain")
                     .await()
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(unit -> assertThat(unit).isEqualTo(Unit.unit()));

            assertThat(capturedRequest.get().method()).isEqualTo("PUT");
        }

        @Test
        void putObject_error_returnsCause() {
            testHttp.respondWithString(403, ACCESS_DENIED_RESPONSE);

            awsClient.putObject("test-key", "hello".getBytes(StandardCharsets.UTF_8), "text/plain")
                     .await()
                     .onSuccess(_ -> assertThat(true).isFalse())
                     .onFailure(S3ClientTest::assertAccessDenied);
        }
    }

    @Nested
    class GetObject {

        @Test
        void getObject_existing_returnsSomeBytes() {
            var content = "file-content".getBytes(StandardCharsets.UTF_8);
            testHttp.respondWithBytes(200, content);

            awsClient.getObject("test-key")
                     .await()
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(opt -> assertThat(opt.map(v -> new String(v, StandardCharsets.UTF_8)).or("")).isEqualTo("file-content"));

            assertThat(capturedRequest.get().method()).isEqualTo("GET");
        }

        @Test
        void getObject_missing_returnsNone() {
            testHttp.respondWithBytes(404, new byte[0]);

            awsClient.getObject("missing-key")
                     .await()
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(opt -> assertThat(opt).isEqualTo(Option.none()));
        }

        /// #249: an unavailable provider is a failure, never "absent". Mutation that reddens it: map every
        /// non-2xx answer of GET to `none` again (the pre-#249 `toOptionalBody`).
        @Test
        void getObject_serviceUnavailable_failsInsteadOfNone() {
            testHttp.respondWithBytes(503, SLOW_DOWN_RESPONSE.getBytes(StandardCharsets.UTF_8));

            awsClient.getObject("test-key")
                     .await()
                     .onSuccess(opt -> fail("A 503 must fail, but read as " + opt))
                     .onFailure(cause -> assertThat(cause).isInstanceOf(S3Error.ApiError.class));
        }

        @Test
        void getObject_noSuchBucket_failsInsteadOfNone() {
            testHttp.respondWithBytes(404, NO_SUCH_BUCKET_RESPONSE.getBytes(StandardCharsets.UTF_8));

            awsClient.getObject("test-key")
                     .await()
                     .onSuccess(opt -> fail("A missing bucket must fail, but read as " + opt))
                     .onFailure(cause -> assertThat(cause).isInstanceOf(S3Error.BucketNotFound.class));
        }

        @Test
        void getObject_forbidden_failsInsteadOfNone() {
            testHttp.respondWithBytes(403, ACCESS_DENIED_RESPONSE.getBytes(StandardCharsets.UTF_8));

            awsClient.getObject("test-key")
                     .await()
                     .onSuccess(opt -> fail("A 403 must fail, but read as " + opt))
                     .onFailure(S3ClientTest::assertAccessDenied);
        }
    }

    @Nested
    class HeadObject {

        @Test
        void headObject_existing_returnsTrue() {
            testHttp.respondWithUnit(200);

            awsClient.headObject("test-key")
                     .await()
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(exists -> assertThat(exists).isTrue());

            assertThat(capturedRequest.get().method()).isEqualTo("HEAD");
        }

        @Test
        void headObject_missing_returnsFalse() {
            testHttp.respondWithUnit(404);

            awsClient.headObject("missing-key")
                     .await()
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(exists -> assertThat(exists).isFalse());
        }

        /// #249: mutation that reddens it: answer `isSuccess()` for HEAD again (any non-2xx read as absent).
        @Test
        void headObject_serviceUnavailable_failsInsteadOfFalse() {
            testHttp.respondWithUnit(503);

            awsClient.headObject("test-key")
                     .await()
                     .onSuccess(exists -> fail("A 503 must fail, but read as " + exists))
                     .onFailure(cause -> assertThat(cause).isInstanceOf(S3Error.ApiError.class));
        }
    }

    @Nested
    class Credentials {

        /// #249: temporary credentials carry a session token, which must be signed and sent. Mutation that
        /// reddens it: drop the token from the signed or the sent headers in `RestS3Client#sign`.
        @Test
        void sessionToken_isSignedAndSent() {
            var credentials = AwsCredentials.awsCredentials("ASIATEST", "secret", Option.some("session-token-1"), Option.none());
            var client = S3Client.s3Client(MINIO_CONFIG, testHttp, AwsCredentialsProvider.staticCredentials(credentials), Option.none());
            testHttp.respondWithUnit(200);

            client.headObject("test-key")
                  .await()
                  .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            var headers = capturedRequest.get().headers();
            assertThat(headers.firstValue("x-amz-security-token")).contains("session-token-1");
            assertThat(headers.firstValue("Authorization").orElseThrow()).contains("x-amz-security-token")
                                                                         .contains("Credential=ASIATEST/");
        }

        @Test
        void provider_isAskedPerRequest() {
            var calls = new AtomicInteger();
            AwsCredentialsProvider provider = () -> Promise.success(AwsCredentials.awsCredentials("AKID" + calls.incrementAndGet(),
                                                                                                  "secret"));
            var client = S3Client.s3Client(MINIO_CONFIG, testHttp, provider, Option.none());
            testHttp.respondWithUnit(200);

            client.headObject("a").await();
            client.headObject("b").await();

            assertThat(calls.get()).isEqualTo(2);
            assertThat(capturedRequest.get().headers().firstValue("Authorization").orElseThrow()).contains("Credential=AKID2/");
        }

        @Test
        void requestTimeout_isSetOnEveryRequest() {
            var client = S3Client.s3Client(MINIO_CONFIG,
                                           testHttp,
                                           AwsCredentialsProvider.staticCredentials(AwsCredentials.awsCredentials("AKID", "s")),
                                           Option.some(Duration.ofSeconds(7)));
            testHttp.respondWithUnit(200);

            client.headObject("a").await();

            assertThat(capturedRequest.get().timeout()).contains(Duration.ofSeconds(7));
        }

        @Test
        void providerFailure_failsTheRequestWithoutSending() {
            capturedRequest.set(null);
            AwsCredentialsProvider provider = () -> new AwsError.CredentialsUnavailable("test", "nothing configured").promise();
            var client = S3Client.s3Client(MINIO_CONFIG, testHttp, provider, Option.none());

            client.headObject("a")
                  .await()
                  .onSuccess(_ -> fail("Expected failure"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(AwsError.CredentialsUnavailable.class));

            assertThat(capturedRequest.get()).isNull();
        }
    }

    @Nested
    class VirtualHostedEndpoint {

        @Test
        void virtualHosted_customEndpoint_prefixesBucketToEndpointHost() {
            var config = S3Config.s3Endpoint("https://objects.example.com:9443", "b1", "eu-1", false);

            assertThat(config.objectUrl("k")).isEqualTo("https://b1.objects.example.com:9443/k");
            assertThat(config.bucketUrl()).isEqualTo("https://b1.objects.example.com:9443");
        }
    }

    @Nested
    class DeleteObject {

        @Test
        void deleteObject_existing_succeeds() {
            testHttp.respondWithString(204, "");

            awsClient.deleteObject("test-key")
                     .await()
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(unit -> assertThat(unit).isEqualTo(Unit.unit()));

            assertThat(capturedRequest.get().method()).isEqualTo("DELETE");
        }

        @Test
        void deleteObject_missing_succeeds() {
            testHttp.respondWithString(404, NOT_FOUND_RESPONSE);

            awsClient.deleteObject("missing-key")
                     .await()
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(unit -> assertThat(unit).isEqualTo(Unit.unit()));
        }
    }

    @Nested
    class ListObjects {

        @Test
        void listObjects_withPrefix_filtersCorrectly() {
            testHttp.respondWithString(200, LIST_OBJECTS_RESPONSE);

            awsClient.listObjects("data/", 100)
                     .await()
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(keys -> assertThat(keys).containsExactly("data/file1.txt", "data/file2.txt"));

            assertThat(capturedRequest.get().method()).isEqualTo("GET");
            assertThat(capturedRequest.get().uri().toString()).contains("list-type=2");
            assertThat(capturedRequest.get().uri().toString()).contains("prefix=data%2F");
        }

        @Test
        void listObjects_empty_returnsEmptyList() {
            testHttp.respondWithString(200, EMPTY_LIST_RESPONSE);

            awsClient.listObjects("nonexistent/", 10)
                     .await()
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(keys -> assertThat(keys).isEmpty());
        }
    }

    @Nested
    class SigV4Signing {

        @Test
        void sigV4_signsRequest_authorizationHeaderPresent() {
            testHttp.respondWithUnit(200);

            awsClient.headObject("test-key")
                     .await()
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            var authHeader = capturedRequest.get().headers().firstValue("Authorization");
            assertThat(authHeader).isPresent();
            assertThat(authHeader.get()).startsWith("AWS4-HMAC-SHA256 Credential=AKIDTEST/");
        }

        @Test
        void sigV4_signsRequest_amzDatePresent() {
            testHttp.respondWithUnit(200);

            awsClient.headObject("test-key")
                     .await()
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            var dateHeader = capturedRequest.get().headers().firstValue("X-Amz-Date");
            assertThat(dateHeader).isPresent();
        }
    }

    @Nested
    class RoundTrip {

        @Test
        void putObject_getObject_roundTrip() {
            var content = "round-trip-content".getBytes(StandardCharsets.UTF_8);

            testHttp.respondWithString(200, "");
            awsClient.putObject("rt-key", content, "text/plain")
                     .await()
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            assertThat(capturedRequest.get().method()).isEqualTo("PUT");

            testHttp.respondWithBytes(200, content);
            awsClient.getObject("rt-key")
                     .await()
                     .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                     .onSuccess(opt -> assertThat(opt.map(v -> new String(v, StandardCharsets.UTF_8)).or("")).isEqualTo("round-trip-content"));
        }
    }

    @Nested
    class XmlParsing {

        @Test
        void extractKeys_validXml_extractsAllKeys() {
            var keys = RestS3Client.extractKeys(LIST_OBJECTS_RESPONSE);
            assertThat(keys).containsExactly("data/file1.txt", "data/file2.txt");
        }

        @Test
        void extractKeys_emptyXml_returnsEmptyList() {
            var keys = RestS3Client.extractKeys(EMPTY_LIST_RESPONSE);
            assertThat(keys).isEmpty();
        }

        @Test
        void extractKeys_noKeyTags_returnsEmptyList() {
            var keys = RestS3Client.extractKeys("<ListBucketResult></ListBucketResult>");
            assertThat(keys).isEmpty();
        }
    }

    @Nested
    class ContentMd5 {

        @Test
        void contentMd5_nonEmpty_returnsBase64() {
            RestS3Client.contentMd5("hello".getBytes(StandardCharsets.UTF_8))
                        .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                        .onSuccess(ContentMd5::assertNonEmptyBase64);
        }

        private static void assertNonEmptyBase64(String md5) {
            assertThat(md5).isNotEmpty();
            assertThat(md5).isBase64();
        }

        @Test
        void contentMd5_empty_returnsBase64() {
            RestS3Client.contentMd5(new byte[0])
                        .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                        .onSuccess(md5 -> assertThat(md5).isNotEmpty());
        }
    }

    // --- Assertion helpers ---

    private static void assertAccessDenied(Cause cause) {
        assertThat(cause).isInstanceOf(S3Error.AccessDenied.class);
    }

    // --- Test HTTP operations ---

    static final class TestHttpOperations implements HttpOperations {
        private final AtomicReference<HttpRequest> capturedRequest;
        private int responseStatus;
        private String responseStringBody;
        private byte[] responseBytesBody;
        private ResponseMode responseMode = ResponseMode.STRING;

        TestHttpOperations(AtomicReference<HttpRequest> capturedRequest) {
            this.capturedRequest = capturedRequest;
        }

        void respondWithString(int status, String body) {
            this.responseStatus = status;
            this.responseStringBody = body;
            this.responseMode = ResponseMode.STRING;
        }

        void respondWithBytes(int status, byte[] body) {
            this.responseStatus = status;
            this.responseBytesBody = body;
            this.responseMode = ResponseMode.BYTES;
        }

        void respondWithUnit(int status) {
            this.responseStatus = status;
            this.responseMode = ResponseMode.UNIT;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            capturedRequest.set(request);
            var body = switch (responseMode) {
                case STRING -> (T) responseStringBody;
                case BYTES -> (T) responseBytesBody;
                case UNIT -> (T) Unit.unit();
            };
            var result = new HttpResult<>(responseStatus,
                                          HttpHeaders.of(Map.of(), (a, b) -> true),
                                          body);
            return Promise.success(result);
        }

        enum ResponseMode { STRING, BYTES, UNIT }
    }

    // --- Fixtures ---

    private static final String LIST_OBJECTS_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
            <Name>test-bucket</Name>
            <Prefix>data/</Prefix>
            <MaxKeys>100</MaxKeys>
            <IsTruncated>false</IsTruncated>
            <Contents>
                <Key>data/file1.txt</Key>
                <Size>1024</Size>
                <LastModified>2025-01-01T00:00:00.000Z</LastModified>
            </Contents>
            <Contents>
                <Key>data/file2.txt</Key>
                <Size>2048</Size>
                <LastModified>2025-01-02T00:00:00.000Z</LastModified>
            </Contents>
        </ListBucketResult>
        """;

    private static final String EMPTY_LIST_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
            <Name>test-bucket</Name>
            <Prefix>nonexistent/</Prefix>
            <MaxKeys>10</MaxKeys>
            <IsTruncated>false</IsTruncated>
        </ListBucketResult>
        """;

    private static final String ACCESS_DENIED_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Error>
            <Code>AccessDenied</Code>
            <Message>Access Denied</Message>
        </Error>
        """;

    private static final String SLOW_DOWN_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Error>
            <Code>SlowDown</Code>
            <Message>Please reduce your request rate.</Message>
        </Error>
        """;

    private static final String NO_SUCH_BUCKET_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Error>
            <Code>NoSuchBucket</Code>
            <Message>The specified bucket does not exist</Message>
        </Error>
        """;

    private static final String NOT_FOUND_RESPONSE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Error>
            <Code>NoSuchKey</Code>
            <Message>The specified key does not exist.</Message>
        </Error>
        """;
}
