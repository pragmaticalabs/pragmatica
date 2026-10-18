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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// AWS Signature Version 4 signer. Pure functions, no state.
/// Implements the complete SigV4 signing process for AWS API requests.
public sealed interface AwsSigV4Signer {
    DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    String ALGORITHM = "AWS4-HMAC-SHA256";
    String HMAC_SHA256 = "HmacSHA256";

    String SHA256 = "SHA-256";

    /// Signs an AWS request and returns the headers to add.
    ///
    /// @param config    AWS credentials and region
    /// @param service   AWS service name (e.g., "ec2", "elasticloadbalancing")
    /// @param method    HTTP method (e.g., "POST")
    /// @param url       Full request URL
    /// @param headers   Existing headers to include in signing
    /// @param body      Request body bytes
    ///
    /// @return Result containing map of headers to add (Authorization, X-Amz-Date, x-amz-content-sha256)
    static Result<Map<String, String>> sign(AwsConfig config,
                                            String service,
                                            String method,
                                            String url,
                                            Map<String, String> headers,
                                            byte[] body) {
        var now = Instant.now();

        return signAtTime(config, service, method, url, headers, body, now);
    }

    /// Signs an AWS request at a specific time (for testing).
    static Result<Map<String, String>> signAtTime(AwsConfig config,
                                                  String service,
                                                  String method,
                                                  String url,
                                                  Map<String, String> headers,
                                                  byte[] body,
                                                  Instant timestamp) {
        var amzDate = TIMESTAMP_FORMAT.format(timestamp);
        var dateStamp = DATE_FORMAT.format(timestamp);
        var payloadHash = sha256Hex(body);
        var allHeaders = buildSigningHeaders(headers, canonicalHost(url), amzDate, payloadHash);
        var signedHeaderNames = buildSignedHeaderNames(allHeaders);
        var canonicalRequest = buildCanonicalRequest(method, url, allHeaders, signedHeaderNames, payloadHash);
        var credentialScope = buildCredentialScope(dateStamp, config.region(), service);
        var stringToSign = buildStringToSign(amzDate, credentialScope, canonicalRequest);

        return computeSigningKey(config.secretAccessKey(),
                                 dateStamp,
                                 config.region(),
                                 service).map(signingKey -> computeSignature(signingKey, stringToSign))
                                .map(signature -> buildResultHeaders(config.accessKeyId(),
                                                                     credentialScope,
                                                                     signedHeaderNames,
                                                                     signature,
                                                                     amzDate,
                                                                     payloadHash));
    }

    private static TreeMap<String, String> buildSigningHeaders(Map<String, String> headers,
                                                               String host,
                                                               String amzDate,
                                                               String payloadHash) {
        var signingHeaders = new TreeMap<String, String>();

        headers.forEach((k, v) -> signingHeaders.put(k.toLowerCase(), v.trim()));
        signingHeaders.put("host", host);
        signingHeaders.put("x-amz-date", amzDate);
        signingHeaders.put("x-amz-content-sha256", payloadHash);

        return signingHeaders;
    }

    private static String buildSignedHeaderNames(TreeMap<String, String> headers) {
        return String.join(";", headers.keySet());
    }

    private static String buildCanonicalRequest(String method,
                                                String url,
                                                TreeMap<String, String> headers,
                                                String signedHeaders,
                                                String payloadHash) {
        var uri = URI.create(url);
        var canonicalUri = Option.option(uri.getRawPath()).filter(p -> !p.isEmpty()).or("/");
        var canonicalQueryString = canonicalQuery(Option.option(uri.getRawQuery()).or(""));
        var canonicalHeaders = headers.entrySet()
                                      .stream()
                                      .map(e -> e.getKey() + ":" + e.getValue() + "\n")
                                      .collect(Collectors.joining());

        return method
             + "\n" + canonicalUri
             + "\n" + canonicalQueryString
             + "\n" + canonicalHeaders
             + "\n" + signedHeaders
             + "\n" + payloadHash;
    }

    /// SigV4's canonical query string: the (already URI-encoded) parameters sorted by encoded NAME, then by encoded
    /// value, each written `name=value`, a bare name given an empty value. #249: the raw query was signed in request
    /// order, so S3 ListObjectsV2 failed with `SignatureDoesNotMatch`. Sorting whole `name=value` strings instead of
    /// (name, value) pairs is wrong whenever one name is a prefix of another and the next character sorts before
    /// `=` (`a=2&a-b=1`, `InstanceId.1` beside `InstanceId.10`); botocore sorts the pairs, and so does this.
    static String canonicalQuery(String rawQuery) {
        return Arrays.stream(rawQuery.split("&"))
                     .filter(pair -> !pair.isEmpty())
                     .map(QueryParameter::queryParameter)
                     .sorted(QueryParameter.ORDER)
                     .map(QueryParameter::canonical)
                     .collect(Collectors.joining("&"));
    }

    /// One encoded query parameter; a bare name has an empty value.
    record QueryParameter(String name, String value) {
        static final Comparator<QueryParameter> ORDER = Comparator.comparing(QueryParameter::name).thenComparing(QueryParameter::value);

        static QueryParameter queryParameter(String pair) {
            var eq = pair.indexOf('=');

            return eq < 0
                   ? new QueryParameter(pair, "")
                   : new QueryParameter(pair.substring(0, eq), pair.substring(eq + 1));
        }

        String canonical() {
            return name + "=" + value;
        }
    }

    private static String buildCredentialScope(String dateStamp, String region, String service) {
        return dateStamp + "/" + region + "/" + service + "/aws4_request";
    }

    private static String buildStringToSign(String amzDate, String credentialScope, String canonicalRequest) {
        return ALGORITHM
             + "\n" + amzDate
             + "\n" + credentialScope
             + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
    }

    private static Result<byte[]> computeSigningKey(String secretKey, String dateStamp, String region, String service) {
        return hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8),
                          dateStamp).flatMap(dateKey -> hmacSha256(dateKey, region))
                         .flatMap(regionKey -> hmacSha256(regionKey, service))
                         .flatMap(serviceKey -> hmacSha256(serviceKey, "aws4_request"));
    }

    private static String computeSignature(byte[] signingKey, String stringToSign) {
        return hmacSha256(signingKey, stringToSign).map(HexFormat.of()::formatHex)
                         .or("");
    }

    private static Map<String, String> buildResultHeaders(String accessKeyId,
                                                          String credentialScope,
                                                          String signedHeaders,
                                                          String signature,
                                                          String amzDate,
                                                          String payloadHash) {
        var result = new LinkedHashMap<String, String>();

        result.put("Authorization",
                   ALGORITHM
                  + " Credential=" + accessKeyId
                  + "/" + credentialScope
                  + ", SignedHeaders=" + signedHeaders
                  + ", Signature=" + signature);
        result.put("X-Amz-Date", amzDate);
        result.put("x-amz-content-sha256", payloadHash);

        return result;
    }

    /// The `host` header value the HTTP client will send, which the signature must cover byte for byte: the
    /// host, plus `:port` when the URL names a port other than the scheme's default (the JDK client's rule).
    /// #249: signing the bare host made every request to an endpoint on a non-default port (MinIO on `:9000`,
    /// a LocalStack edge) fail with `SignatureDoesNotMatch`.
    static String canonicalHost(String url) {
        var uri = URI.create(url);
        var port = uri.getPort();
        var defaultPort = port == -1 || ("https".equalsIgnoreCase(uri.getScheme())
                                         ? port == 443
                                         : port == 80);

        return defaultPort
               ? uri.getHost()
               : uri.getHost() + ":" + port;
    }

    /// Computes HMAC-SHA256.
    static Result<byte[]> hmacSha256(byte[] key, String data) {
        return Result.lift(t -> new AwsError.SigningError("HMAC-SHA256 computation failed", Option.option(t)),
                           () -> computeHmac(key, data));
    }

    private static byte[] computeHmac(byte[] key, String data) throws Exception {
        var mac = Mac.getInstance(HMAC_SHA256);

        mac.init(new SecretKeySpec(key, HMAC_SHA256));

        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    /// Computes SHA-256 hex digest.
    static String sha256Hex(byte[] data) {
        return Result.lift(t -> new AwsError.SigningError("SHA-256 computation failed",
                                                          Option.option(t)),
                           () -> HexFormat.of().formatHex(MessageDigest.getInstance(SHA256).digest(data)))
                     .or("");
    }

    /// Encodes a URL component per RFC 3986.
    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                         .replace("+", "%20")
                         .replace("*", "%2A")
                         .replace("%7E", "~");
    }

    record Unused() implements AwsSigV4Signer {}
}
