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

import org.pragmatica.cloud.aws.AwsSigV4Signer;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import org.pragmatica.lang.Result;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Promise.success;

/// S3-compatible REST client implementation using SigV4 signing.
/// Supports both AWS S3 (virtual-hosted) and MinIO (path-style) URL formats.
record RestS3Client(S3Config config, HttpOperations http) implements S3Client {
    private static final String S3_SERVICE = "s3";
    private static final String OCTET_STREAM = "application/octet-stream";

    @Override
    public Promise<Unit> putObject(String key, byte[] content, String contentType) {
        var url = config.objectUrl(key);
        var headers = Map.of("content-type", contentType, "content-md5", contentMd5(content));
        return AwsSigV4Signer.sign(config.toAwsConfig(), S3_SERVICE, "PUT", url, headers, content)
                             .async()
                             .flatMap(signed -> sendPutRequest(url, content, contentType, signed))
                             .flatMap(result -> checkPutSuccess(result, key));
    }

    @Override
    public Promise<Option<byte[]>> getObject(String key) {
        var url = config.objectUrl(key);
        var emptyBody = new byte[0];
        return AwsSigV4Signer.sign(config.toAwsConfig(), S3_SERVICE, "GET", url, Map.of(), emptyBody)
                             .async()
                             .flatMap(signed -> sendGetRequest(url, signed))
                             .map(result -> toOptionalBody(result, key));
    }

    @Override
    public Promise<Boolean> headObject(String key) {
        var url = config.objectUrl(key);
        var emptyBody = new byte[0];
        return AwsSigV4Signer.sign(config.toAwsConfig(), S3_SERVICE, "HEAD", url, Map.of(), emptyBody)
                             .async()
                             .flatMap(signed -> sendHeadRequest(url, signed))
                             .map(HttpResult::isSuccess);
    }

    @Override
    public Promise<Unit> deleteObject(String key) {
        var url = config.objectUrl(key);
        var emptyBody = new byte[0];
        return AwsSigV4Signer.sign(config.toAwsConfig(), S3_SERVICE, "DELETE", url, Map.of(), emptyBody)
                             .async()
                             .flatMap(signed -> sendDeleteRequest(url, signed))
                             .flatMap(result -> checkDeleteSuccess(result, key));
    }

    @Override
    public Promise<List<String>> listObjects(String prefix, int maxKeys) {
        var url = buildListUrl(prefix, maxKeys);
        var emptyBody = new byte[0];
        return AwsSigV4Signer.sign(config.toAwsConfig(), S3_SERVICE, "GET", url, Map.of(), emptyBody)
                             .async()
                             .flatMap(signed -> sendListRequest(url, signed))
                             .flatMap(RestS3Client::parseListResponse);
    }

    // --- Request senders (Leaf functions) ---

    private Promise<HttpResult<String>> sendPutRequest(String url, byte[] content,
                                                       String contentType,
                                                       Map<String, String> signedHeaders) {
        var builder = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .PUT(BodyPublishers.ofByteArray(content))
                                .header("Content-Type", contentType)
                                .header("Content-MD5", contentMd5(content));
        signedHeaders.forEach(builder::header);
        return http.sendString(builder.build());
    }

    private Promise<HttpResult<byte[]>> sendGetRequest(String url, Map<String, String> signedHeaders) {
        var builder = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .GET();
        signedHeaders.forEach(builder::header);
        return http.sendBytes(builder.build());
    }

    private Promise<HttpResult<Unit>> sendHeadRequest(String url, Map<String, String> signedHeaders) {
        var builder = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .method("HEAD", BodyPublishers.noBody());
        signedHeaders.forEach(builder::header);
        return http.sendDiscarding(builder.build());
    }

    private Promise<HttpResult<String>> sendDeleteRequest(String url, Map<String, String> signedHeaders) {
        var builder = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .DELETE();
        signedHeaders.forEach(builder::header);
        return http.sendString(builder.build());
    }

    private Promise<HttpResult<String>> sendListRequest(String url, Map<String, String> signedHeaders) {
        var builder = HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .GET();
        signedHeaders.forEach(builder::header);
        return http.sendString(builder.build());
    }

    // --- Response handlers (Leaf functions) ---

    private static Promise<Unit> checkPutSuccess(HttpResult<String> result, String key) {
        if (result.isSuccess()) {
            return success(Unit.unit());
        }
        return S3Error.fromResponse(result.statusCode(), result.body(), key).promise();
    }

    private static Option<byte[]> toOptionalBody(HttpResult<byte[]> result, String key) {
        return result.isSuccess() ? some(result.body()) : none();
    }

    private static Promise<Unit> checkDeleteSuccess(HttpResult<String> result, String key) {
        if (result.isSuccess() || result.statusCode() == 404) {
            return success(Unit.unit());
        }
        return S3Error.fromResponse(result.statusCode(), result.body(), key).promise();
    }

    private static Promise<List<String>> parseListResponse(HttpResult<String> result) {
        if (!result.isSuccess()) {
            return S3Error.fromResponse(result.statusCode(), result.body(), "").promise();
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
        var keys = new ArrayList<String>();
        var keyTag = "<Key>";
        var keyCloseTag = "</Key>";
        var searchFrom = 0;

        while (searchFrom < xml.length()) {
            var startIdx = xml.indexOf(keyTag, searchFrom);
            if (startIdx < 0) {
                break;
            }
            var valueStart = startIdx + keyTag.length();
            var endIdx = xml.indexOf(keyCloseTag, valueStart);
            if (endIdx < 0) {
                break;
            }
            keys.add(xml.substring(valueStart, endIdx));
            searchFrom = endIdx + keyCloseTag.length();
        }

        return List.copyOf(keys);
    }

    // --- Content-MD5 computation ---

    static String contentMd5(byte[] content) {
        return Base64.getEncoder().encodeToString(md5Digest(content));
    }

    private static byte[] md5Digest(byte[] content) {
        return liftDigest(content, "MD5");
    }

    private static byte[] liftDigest(byte[] content, String algorithm) {
        return Result.lift(
            t -> new S3Error.NetworkError("Failed to compute " + algorithm, Option.option(t)),
            () -> MessageDigest.getInstance(algorithm).digest(content)
        ).or(new byte[0]);
    }
}
