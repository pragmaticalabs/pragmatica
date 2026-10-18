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

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.all;


/// Instance-profile credentials from the EC2 instance metadata service, IMDSv2 only: a session token
/// (`PUT /latest/api/token`), the role name (`GET /latest/meta-data/iam/security-credentials/`), then the
/// role's temporary credentials. The last answer is reused until it is within [#REFRESH_MARGIN] of its
/// expiry. Two callers that find it stale at the same moment both fetch; the metadata service answers both
/// and the later answer wins, which is harmless because both are valid.
final class InstanceProfileCredentials implements AwsCredentialsProvider {
    static final String DEFAULT_ENDPOINT = "http://169.254.169.254";
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String TOKEN_TTL_SECONDS = "21600";
    private static final String CREDENTIALS_PATH = "/latest/meta-data/iam/security-credentials/";
    private static final String SOURCE = "the instance metadata service";

    private final HttpOperations http;
    private final String endpoint;
    private final AtomicReference<Option<AwsCredentials>> latest = new AtomicReference<>(Option.none());

    private InstanceProfileCredentials(HttpOperations http, String endpoint) {
        this.http = http;
        this.endpoint = endpoint;
    }

    static InstanceProfileCredentials instanceProfileCredentials(HttpOperations http, String endpoint) {
        return new InstanceProfileCredentials(http, endpoint);
    }

    @Override
    public Promise<AwsCredentials> credentials() {
        return latest.get()
                     .filter(current -> current.validFor(Instant.now(),
                                                         REFRESH_MARGIN))
                     .fold(this::fetch, Promise::success);
    }

    private Promise<AwsCredentials> fetch() {
        return fetchToken().flatMap(this::fetchWithToken)
                         .map(this::remember);
    }

    private Promise<AwsCredentials> fetchWithToken(String token) {
        return fetchRole(token).flatMap(role -> fetchCredentials(token, role));
    }

    /// Cached as a step of the chain, not as a side callback, so the answer is cached before any caller sees it:
    /// `onSuccess` would run after the promise resolved, and a caller asking again at once would fetch again.
    private AwsCredentials remember(AwsCredentials fresh) {
        latest.set(Option.some(fresh));

        return fresh;
    }

    private Promise<String> fetchToken() {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(endpoint + "/latest/api/token"))
                                 .timeout(REQUEST_TIMEOUT)
                                 .header("X-aws-ec2-metadata-token-ttl-seconds", TOKEN_TTL_SECONDS)
                                 .PUT(BodyPublishers.noBody())
                                 .build();

        return http.sendString(request)
                   .flatMap(result -> successBody(result, "session token"));
    }

    private Promise<String> fetchRole(String token) {
        return http.sendString(metadataRequest(CREDENTIALS_PATH, token))
                   .flatMap(result -> successBody(result, "role name"))
                   .flatMap(InstanceProfileCredentials::firstRole);
    }

    private Promise<AwsCredentials> fetchCredentials(String token, String role) {
        return http.sendString(metadataRequest(CREDENTIALS_PATH + role, token))
                   .flatMap(result -> successBody(result, "role credentials"))
                   .flatMap(body -> parseCredentials(body).async());
    }

    private HttpRequest metadataRequest(String path, String token) {
        return HttpRequest.newBuilder()
                          .uri(URI.create(endpoint + path))
                          .timeout(REQUEST_TIMEOUT)
                          .header("X-aws-ec2-metadata-token", token)
                          .GET()
                          .build();
    }

    private static Promise<String> successBody(HttpResult<String> result, String what) {
        return result.isSuccess()
               ? Promise.success(result.body())
               : new AwsError.CredentialsUnavailable(SOURCE,
                                                     "the " + what + " request answered HTTP " + result.statusCode()).promise();
    }

    private static Promise<String> firstRole(String body) {
        return Option.from(body.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst()).async(new AwsError.CredentialsUnavailable(SOURCE,
                                                                                                                                                 "no IAM role is attached to this instance"));
    }

    /// The role credentials document is flat JSON; only four string fields are read, so a field match is
    /// enough and keeps this free of a JSON mapper. A missing field fails naming the field, never a value.
    static Result<AwsCredentials> parseCredentials(String body) {
        return all(field(body, "AccessKeyId"),
                   field(body, "SecretAccessKey"),
                   field(body, "Token"),
                   field(body, "Expiration").flatMap(InstanceProfileCredentials::instant)).map((accessKeyId, secret, token, expiration) -> AwsCredentials.awsCredentials(accessKeyId,
                                                                                                                                                                         secret,
                                                                                                                                                                         Option.some(token),
                                                                                                                                                                         Option.some(expiration)));
    }

    private static Result<String> field(String body, String name) {
        var matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);

        return matcher.find()
               ? Result.success(matcher.group(1))
               : new AwsError.CredentialsUnavailable(SOURCE, "the credentials document has no " + name).result();
    }

    private static Result<Instant> instant(String text) {
        return Result.lift(t -> new AwsError.CredentialsUnavailable(SOURCE,
                                                                    "the Expiration field is not an instant",
                                                                    Option.some(t)),
                           () -> Instant.parse(text));
    }
}
