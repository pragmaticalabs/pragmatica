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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
    private static final Pattern CONTENTS_PATTERN = Pattern.compile("<Contents>(.*?)</Contents>", Pattern.DOTALL);
    private static final Pattern SIZE_PATTERN = Pattern.compile("<Size>(.*?)</Size>");
    private static final Pattern LAST_MODIFIED_PATTERN = Pattern.compile("<LastModified>(.*?)</LastModified>");
    private static final String COPY_SOURCE_HEADER = "x-amz-copy-source";
    private static final String METADATA_DIRECTIVE_HEADER = "x-amz-metadata-directive";
    private static final String COPY_SOURCE_IF_MATCH_HEADER = "x-amz-copy-source-if-match";
    private static final String IF_MATCH_HEADER = "if-match";

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

    @Override
    public Promise<Option<ObjectStat>> statObject(String key) {
        return head(key).flatMap(result -> toStat(result, key));
    }

    private Promise<HttpResult<Unit>> head(String key) {
        var url = config.objectUrl(key);

        return authorize("HEAD",
                         url,
                         Map.of(),
                         EMPTY_BODY).map(signed -> withHeaders(request(url).method("HEAD",
                                                                                   BodyPublishers.noBody()),
                                                               signed))
                        .flatMap(http::sendDiscarding);
    }

    @Override
    public Promise<ObjectListing> listObjectStats(String prefix, Option<String> startAfter, int maxKeys) {
        var url = buildListUrl(prefix, maxKeys) + startAfter.map(key -> "&start-after=" + AwsSigV4Signer.urlEncode(key))
                                                            .or("");

        return authorize("GET",
                         url,
                         Map.of(),
                         EMPTY_BODY).map(signed -> withHeaders(request(url).GET(),
                                                               signed))
                        .flatMap(http::sendString)
                        .flatMap(RestS3Client::parseListing);
    }

    /// A HEAD reads the object's ETag, then the copy is conditional on it twice: on the source
    /// (`x-amz-copy-source-if-match`) and on the destination (`If-Match`, a conditional write), so a delete landing
    /// between the HEAD and the copy's write fails the copy instead of letting it recreate the object. The copy source is
    /// `{bucket}/{key}` as is: the keys this client is used with (hex block ids under an operator prefix) need no encoding.
    @Override
    public Promise<Boolean> touchObject(String key, String contentType) {
        return head(key).flatMap(result -> toEtag(result, key))
                   .flatMap(etag -> etag.fold(() -> success(false),
                                              tag -> copyIfMatch(key, contentType, tag)));
    }

    private Promise<Boolean> copyIfMatch(String key, String contentType, String etag) {
        var url = config.objectUrl(key);
        var headers = Map.of("content-type",
                             contentType,
                             COPY_SOURCE_HEADER,
                             config.bucket() + "/" + key,
                             METADATA_DIRECTIVE_HEADER,
                             "REPLACE",
                             COPY_SOURCE_IF_MATCH_HEADER,
                             etag,
                             IF_MATCH_HEADER,
                             etag);

        return authorize("PUT", url, headers, EMPTY_BODY).map(signed -> buildCopyRequest(url, headers, signed))
                        .flatMap(http::sendString)
                        .flatMap(result -> toCopied(result, key));
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

    private HttpRequest buildCopyRequest(String url, Map<String, String> headers, Map<String, String> signedHeaders) {
        var builder = request(url).PUT(BodyPublishers.noBody());

        headers.forEach(builder::header);

        return withHeaders(builder, signedHeaders);
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

    /// A 2xx carries the object's `Content-Length` and `Last-Modified` (RFC 1123, whole seconds); a 404 is absence, as
    /// for [#toPresence]; a 2xx missing either header fails with [S3Error.ParseError].
    private static Promise<Option<ObjectStat>> toStat(HttpResult<Unit> result, String key) {
        if (result.isSuccess()) {
            return statFromHeaders(result, key).map(Option::some)
                                  .async();
        }

        return result.statusCode() == 404
               ? success(none())
               : S3Error.fromResponse(result.statusCode(),
                                      "",
                                      key)
                        .promise();
    }

    /// A 2xx carries the object's `ETag`; a 404 is absence; a 2xx without it fails with [S3Error.ParseError].
    private static Promise<Option<String>> toEtag(HttpResult<Unit> result, String key) {
        if (result.isSuccess()) {
            return header(result, "ETag", key).map(Option::some)
                         .async();
        }

        return result.statusCode() == 404
               ? success(none())
               : S3Error.fromResponse(result.statusCode(),
                                      "",
                                      key)
                        .promise();
    }

    private static Result<ObjectStat> statFromHeaders(HttpResult<Unit> result, String key) {
        return header(result, "Content-Length", key).flatMap(RestS3Client::parseSize)
                     .flatMap(size -> header(result, "Last-Modified", key).flatMap(RestS3Client::parseHttpDate)
                                            .map(modified -> ObjectStat.objectStat(key, size, modified)));
    }

    private static Result<String> header(HttpResult<Unit> result, String name, String key) {
        return result.header(name)
                     .toResult(new S3Error.ParseError("HEAD " + key + " carries no " + name));
    }

    private static Result<Long> parseSize(String value) {
        return Result.lift(_ -> new S3Error.ParseError("size '" + value + "' is not a number"),
                           () -> Long.parseLong(value.trim()));
    }

    private static Result<Long> parseHttpDate(String value) {
        return Result.lift(_ -> new S3Error.ParseError("date '" + value + "' is not an RFC 1123 date"),
                           () -> DateTimeFormatter.RFC_1123_DATE_TIME.parse(value.trim(),
                                                                            Instant::from)
                                                                     .toEpochMilli());
    }

    private static Result<Long> parseIsoDate(String value) {
        return Result.lift(_ -> new S3Error.ParseError("date '" + value + "' is not an ISO-8601 instant"),
                           () -> Instant.parse(value.trim()).toEpochMilli());
    }

    /// S3 can answer a copy with 200 and an `<Error>` body when it fails after starting: that is a failure, not a copy. A
    /// 412 is a failed `If-Match`: the object was deleted or replaced since its HEAD, so it was not touched.
    private static Promise<Boolean> toCopied(HttpResult<String> result, String key) {
        if (result.isSuccess() && !result.body().contains("<Error>")) {
            return success(true);
        }

        if (result.statusCode() == 412) {
            return success(false);
        }

        var error = S3Error.fromResponse(result.isSuccess()
                                         ? 500
                                         : result.statusCode(),
                                         result.body(),
                                         key);

        return error instanceof S3Error.NotFound
               ? success(false)
               : error.promise();
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

    private static Promise<ObjectListing> parseListing(HttpResult<String> result) {
        if (!result.isSuccess()) {
            return S3Error.fromResponse(result.statusCode(),
                                        result.body(),
                                        "")
                          .promise();
        }

        return extractListing(result.body()).async();
    }

    /// Each `<Contents>` element's `Key`, `Size` and `LastModified`, in document order (ascending key order on the
    /// wire), and `IsTruncated`. Every element missing any of the three adds an [S3Error.ParseError] to the failure, one
    /// composite cause ([org.pragmatica.lang.Cause#stream]).
    static Result<ObjectListing> extractListing(String xml) {
        return Result.allOf(CONTENTS_PATTERN.matcher(xml)
                                            .results()
                                            .map(m -> m.group(1))
                                            .map(RestS3Client::objectStat)
                                            .toList()).map(objects -> ObjectListing.objectListing(objects,
                                                                                                  xml.contains("<IsTruncated>true</IsTruncated>")));
    }

    private static Result<ObjectStat> objectStat(String contents) {
        return element(contents, KEY_PATTERN).flatMap(key -> element(contents, SIZE_PATTERN).flatMap(RestS3Client::parseSize)
                                                                    .flatMap(size -> objectStatOf(contents, key, size)));
    }

    private static Result<ObjectStat> objectStatOf(String contents, String key, long size) {
        return element(contents, LAST_MODIFIED_PATTERN).flatMap(RestS3Client::parseIsoDate)
                      .map(modified -> ObjectStat.objectStat(key, size, modified));
    }

    private static Result<String> element(String contents, Pattern pattern) {
        return Option.from(pattern.matcher(contents).results().map(m -> m.group(1)).findFirst()).toResult(new S3Error.ParseError("listed object lacks " + pattern.pattern()));
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
