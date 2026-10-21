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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.all;


/// Where a client's signing credentials come from. The client asks before every request, so a provider whose
/// credentials expire (the instance profile) can refresh them; the static ones answer the same value each time.
///
/// There is no AWS SDK in this module: the S3 and EC2 clients sign requests themselves (SigV4), so the
/// three sources an operator can name are resolved here:
/// - [#environment]: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and optionally `AWS_SESSION_TOKEN`,
///   read once, when the provider is built;
/// - [#profileFile]: the `[default]` profile of a file in the AWS shared-credentials format, read once;
/// - [#instanceProfile]: the EC2 instance metadata service (IMDSv2), fetched lazily and refreshed before expiry.
///
/// No provider ever puts a secret into a [org.pragmatica.lang.Cause] message or a log line.
@FunctionalInterface
public interface AwsCredentialsProvider {
    String ENV_ACCESS_KEY_ID = "AWS_ACCESS_KEY_ID";
    String ENV_SECRET_ACCESS_KEY = "AWS_SECRET_ACCESS_KEY";
    String ENV_SESSION_TOKEN = "AWS_SESSION_TOKEN";
    String DEFAULT_PROFILE = "default";
    /// The credentials to sign the next request with.
    Promise<AwsCredentials> credentials();

    /// Always answers `credentials`.
    static AwsCredentialsProvider staticCredentials(AwsCredentials credentials) {
        var answer = Promise.success(credentials);

        return () -> answer;
    }

    /// Credentials from the process environment, resolved now. Fails naming the missing variable, never a value.
    static Result<AwsCredentialsProvider> environment() {
        return environment(name -> Option.option(System.getenv(name)));
    }

    /// [#environment()] over an injected variable lookup (tests).
    static Result<AwsCredentialsProvider> environment(Fn1<Option<String>, String> env) {
        return all(requiredVariable(env, ENV_ACCESS_KEY_ID),
                   requiredVariable(env, ENV_SECRET_ACCESS_KEY)).map((accessKeyId, secret) -> AwsCredentials.awsCredentials(accessKeyId,
                                                                                                                            secret,
                                                                                                                            nonBlank(env.apply(ENV_SESSION_TOKEN)),
                                                                                                                            Option.none()))
                  .map(AwsCredentialsProvider::staticCredentials);
    }

    /// Credentials from the `[default]` profile of `file` (AWS shared-credentials format:
    /// `aws_access_key_id`, `aws_secret_access_key`, optional `aws_session_token`), resolved now.
    static Result<AwsCredentialsProvider> profileFile(Path file) {
        return Result.lift(t -> new AwsError.CredentialsUnavailable("file " + file,
                                                                    "the file cannot be read",
                                                                    Option.some(t)),
                           () -> Files.readString(file))
                     .flatMap(content -> profileCredentials(file, content))
                     .map(AwsCredentialsProvider::staticCredentials);
    }

    /// Credentials from the EC2 instance metadata service at its standard address, refreshed before they expire.
    static AwsCredentialsProvider instanceProfile(HttpOperations http) {
        return InstanceProfileCredentials.instanceProfileCredentials(http, InstanceProfileCredentials.DEFAULT_ENDPOINT);
    }

    /// [#instanceProfile(HttpOperations)] against another metadata endpoint (tests, emulators).
    static AwsCredentialsProvider instanceProfile(HttpOperations http, String endpoint) {
        return InstanceProfileCredentials.instanceProfileCredentials(http, endpoint);
    }

    private static Result<String> requiredVariable(Fn1<Option<String>, String> env, String name) {
        return nonBlank(env.apply(name)).toResult(new AwsError.CredentialsUnavailable("the environment",
                                                                                      name + " is not set"));
    }

    private static Option<String> nonBlank(Option<String> value) {
        return value.map(String::trim)
                    .filter(text -> !text.isEmpty());
    }

    private static Result<AwsCredentials> profileCredentials(Path file, String content) {
        var profile = profileEntries(content, DEFAULT_PROFILE);

        return all(requiredEntry(file, profile, "aws_access_key_id"),
                   requiredEntry(file, profile, "aws_secret_access_key")).map((accessKeyId, secret) -> AwsCredentials.awsCredentials(accessKeyId,
                                                                                                                                     secret,
                                                                                                                                     nonBlank(Option.option(profile.get("aws_session_token"))),
                                                                                                                                     Option.none()));
    }

    private static Result<String> requiredEntry(Path file, Map<String, String> profile, String key) {
        return nonBlank(Option.option(profile.get(key))).toResult(new AwsError.CredentialsUnavailable("file " + file,
                                                                                                      "profile [" + DEFAULT_PROFILE
                                                                                                     + "] has no " + key));
    }

    /// The `key = value` lines of the first section `[profile]`; comments (`#`, `;`) and other sections are skipped.
    private static Map<String, String> profileEntries(String content, String profile) {
        return content.lines()
                      .map(String::trim)
                      .filter(line -> !line.isEmpty()
                                      && !line.startsWith("#")
                                      && !line.startsWith(";"))
                      .dropWhile(line -> !line.equals("[" + profile + "]"))
                      .skip(1)
                      .takeWhile(line -> !line.startsWith("["))
                      .filter(line -> line.indexOf('=') > 0)
                      .collect(Collectors.toMap(AwsCredentialsProvider::entryKey,
                                                AwsCredentialsProvider::entryValue,
                                                (first, _) -> first));
    }

    private static String entryKey(String line) {
        return line.substring(0,
                              line.indexOf('='))
                   .trim();
    }

    private static String entryValue(String line) {
        return line.substring(line.indexOf('=') + 1)
                   .trim();
    }
}
