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

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.pragmatica.cloud.aws.AwsCredentials;
import org.pragmatica.cloud.aws.AwsCredentialsProvider;
import org.pragmatica.cloud.aws.AwsSigV4Signer;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Promise.success;


/// S3-compatible REST client implementation using SigV4 signing.
/// Supports both AWS S3 (virtual-hosted) and MinIO (path-style) URL formats.
///
/// Every request is signed with what `credentials` answers at that moment, so expiring credentials (an
/// instance profile) refresh between requests; a session token is signed and sent as `x-amz-security-token`.
/// `requestTimeout`, when present, bounds each HTTP exchange and cancels it on expiry.
///
/// Absence is reported only for an answer that means absence: a 404 for the object (not `NoSuchBucket`).
/// Any other non-2xx answer, and any transport failure, is a failure -- never `none`/`false` -- so a caller
/// cannot read "the provider is unavailable" as "the object does not exist".
record RestS3Client(S3Config config,
                    HttpOperations http,
                    AwsCredentialsProvider credentials,
                    Option<Duration> requestTimeout) implements S3Client {
    private static final String S3_SERVICE = "s3";
    private static final String SECURITY_TOKEN_HEADER = "x-amz-security-token";
    private static final byte[] EMPTY_BODY = new byte[0];
    private static final Pattern KEY_PATTERN = Pattern.compile("<Key>(.*?)</Key>");

    @Override
    public Promise<Unit> putObject(String key, byte[] content, String contentType) {
        var url = config.objectUrl(key);

        return contentMd5(content).async()
                         .flatMap(md5 -> authorize("PUT",
                                                   url,
                                                   Map.of("content-type", contentType, "content-md5", md5),
                                                   content).map(signed -> buildPutRequest(url,
                                                                                          content,
                                                                                          contentType,
                                                                                          md5,
                                                                                          signed)))
                         .flatMap(http::sendString)
                         .flatMap(result -> checkPutSuccess(result, key));
    }

    @Override
    public Promise<Option<byte[]>> getObject(String key) {
        var url = config.objectUrl(key);

        return authorize("GET",
                         url,
                         Map.of(),
                         EMPTY_BODY).map(signed -> withHeaders(request(url).GET(),
                                                               signed))
                        .flatMap(http::sendBytes)
                        .flatMap(result -> toOptionalBody(result, key));
    }

    @Override
    public Promise<Boolean> headObject(String key) {
        var url = config.objectUrl(key);

        return authorize("HEAD",
                         url,
                         Map.of(),
                         EMPTY_BODY).map(signed -> withHeaders(request(url).method("HEAD",
                                                                                   BodyPublishers.noBody()),
                                                               signed))
                        .flatMap(http::sendDiscarding)
                        .flatMap(result -> toPresence(result, key));
    }

    @Override
    public Promise<Unit> deleteObject(String key) {
        var url = config.objectUrl(key);

        return authorize("DELETE",
                         url,
                         Map.of(),
                         EMPTY_BODY).map(signed -> withHeaders(request(url).DELETE(),
                                                               signed))
                        .flatMap(http::sendString)
                        .flatMap(result -> checkDeleteSuccess(result, key));
    }

    @Override
    public Promise<List<String>> listObjects(String prefix, int maxKeys) {
        var url = buildListUrl(prefix, maxKeys);

        return authorize("GET",
                         url,
                         Map.of(),
                         EMPTY_BODY).map(signed -> withHeaders(request(url).GET(),
                                                               signed))
                        .flatMap(http::sendString)
                        .flatMap(RestS3Client::parseListResponse);
    }

    // --- Signing ---
    /// The headers to add to a request: the SigV4 set, plus the session token when the credentials carry one
    /// (it is part of the signed headers, so it is passed to the signer as well as returned).
    private Promise<Map<String, String>> authorize(String method,
                                                   String url,
                                                   Map<String, String> headers,
                                                   byte[] body) {
        return credentials.credentials()
                          .flatMap(creds -> sign(creds, method, url, headers, body).async());
    }

    private Result<Map<String, String>> sign(AwsCredentials creds,
                                             String method,
                                             String url,
                                             Map<String, String> headers,
                                             byte[] body) {
        var tokenHeaders = creds.sessionToken().map(token -> Map.of(SECURITY_TOKEN_HEADER, token)).or(Map.of());

        return AwsSigV4Signer.sign(creds.toAwsConfig(config.region()),
                                   S3_SERVICE,
                                   method,
                                   url,
                                   merged(headers, tokenHeaders),
                                   body)
                             .map(signed -> merged(signed, tokenHeaders));
    }

    private static Map<String, String> merged(Map<String, String> first, Map<String, String> second) {
        var all = new LinkedHashMap<>(first);

        all.putAll(second);

        return all;
    }

    // --- Request builders (Leaf functions) ---
    private HttpRequest.Builder request(String url) {
        var builder = HttpRequest.newBuilder().uri(URI.create(url));

        requestTimeout.onPresent(builder::timeout);

        return builder;
    }

    private static HttpRequest withHeaders(HttpRequest.Builder builder, Map<String, String> signedHeaders) {
        signedHeaders.forEach(builder::header);

        return builder.build();
    }

    private HttpRequest buildPutRequest(String url,
                                        byte[] content,
                                        String contentType,
                                        String md5,
                                        Map<String, String> signedHeaders) {
        return withHeaders(request(url).PUT(BodyPublishers.ofByteArray(content))
                                  .header("Content-Type", contentType)
                                  .header("Content-MD5", md5),
                           signedHeaders);
    }

    // --- Response handlers (Leaf functions) ---
    private static Promise<Unit> checkPutSuccess(HttpResult<String> result, String key) {
        if (result.isSuccess()) {
            return success(Unit.unit());
        }

        return S3Error.fromResponse(result.statusCode(),
                                    result.body(),
                                    key)
                      .promise();
    }

    /// 2xx is the object; a 404 that names the object (not the bucket) is absence; anything else fails.
    private static Promise<Option<byte[]>> toOptionalBody(HttpResult<byte[]> result, String key) {
        if (result.isSuccess()) {
            return success(some(result.body()));
        }

        var error = S3Error.fromResponse(result.statusCode(), new String(result.body(), StandardCharsets.UTF_8), key);

        return error instanceof S3Error.NotFound
               ? success(none())
               : error.promise();
    }

    /// HEAD carries no body, so a 404 cannot tell a missing bucket from a missing object; it reads as absent.
    private static Promise<Boolean> toPresence(HttpResult<Unit> result, String key) {
        if (result.isSuccess()) {
            return success(true);
        }

        return result.statusCode() == 404
               ? success(false)
               : S3Error.fromResponse(result.statusCode(),
                                      "",
                                      key)
                        .promise();
    }

    private static Promise<Unit> checkDeleteSuccess(HttpResult<String> result, String key) {
        if (result.isSuccess() || result.statusCode() == 404) {
            return success(Unit.unit());
        }

        return S3Error.fromResponse(result.statusCode(),
                                    result.body(),
                                    key)
                      .promise();
    }

    private static Promise<List<String>> parseListResponse(HttpResult<String> result) {
        if (!result.isSuccess()) {
            return S3Error.fromResponse(result.statusCode(),
                                        result.body(),
                                        "")
                          .promise();
        }

        return success(extractKeys(result.body()));
    }

    // --- URL builders ---
    private String buildListUrl(String prefix, int maxKeys) {
        var encodedPrefix = AwsSigV4Signer.urlEncode(prefix);

        return config.bucketUrl() + "?list-type=2&prefix=" + encodedPrefix + "&max-keys=" + maxKeys;
    }

    // --- XML key extraction ---
    static List<String> extractKeys(String xml) {
        return KEY_PATTERN.matcher(xml)
                          .results()
                          .map(m -> m.group(1))
                          .toList();
    }

    // --- Content-MD5 computation ---
    static Result<String> contentMd5(byte[] content) {
        return liftDigest(content, "MD5").map(Base64.getEncoder()::encodeToString);
    }

    private static Result<byte[]> liftDigest(byte[] content, String algorithm) {
        return Result.lift(t -> new S3Error.NetworkError("Failed to compute " + algorithm, Option.option(t)),
                           () -> MessageDigest.getInstance(algorithm).digest(content));
    }
}
