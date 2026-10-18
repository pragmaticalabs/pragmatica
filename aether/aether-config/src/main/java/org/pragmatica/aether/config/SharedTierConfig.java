// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #249: the `[storage.<instance>.shared]` section -- which cluster-shared tier is the last tier of an
/// instance's stack. `kind = "dht"` (the default, and the behaviour of a config without the section) is the
/// in-memory DHT; `kind = "s3"` is an S3-compatible bucket ([S3]). Nothing above the storage tier sees which
/// one is configured. `"dht-durable"` is reserved for the durable DHT tier, a separate phase; until it lands
/// it is refused as an unknown kind rather than silently read as `"dht"`.
public sealed interface SharedTierConfig {
    String KIND_DHT = "dht";
    String KIND_S3 = "s3";
    int DEFAULT_MAX_INFLIGHT = 8;
    TimeSpan DEFAULT_REQUEST_TIMEOUT = timeSpan(30).seconds();

    /// The in-memory DHT tier (today's behaviour).
    record Dht() implements SharedTierConfig {}

    /// An S3-compatible bucket. Keys live under `prefix`; `pathStyle` is true for MinIO and most
    /// S3-compatibles. `credentials` names where the signing keys come from -- never the keys themselves.
    record S3(String endpoint,
              String region,
              String bucket,
              String prefix,
              boolean pathStyle,
              Credentials credentials,
              int maxInflight,
              TimeSpan requestTimeout) implements SharedTierConfig {}

    /// Where an S3 tier's credentials come from. Secrets never live in the TOML.
    sealed interface Credentials {
        /// `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, optional `AWS_SESSION_TOKEN`.
        record Environment() implements Credentials {}

        /// The `[default]` profile of an AWS shared-credentials file.
        record File(Path path) implements Credentials {}

        /// The EC2 instance profile, through the instance metadata service.
        record InstanceProfile() implements Credentials {}
    }

    static SharedTierConfig dht() {
        return new Dht();
    }

    /// Typed refusals of a `[storage.<instance>.shared]` section; each names the instance and the key.
    sealed interface SharedTierConfigError extends Cause {
        record UnknownKind(String instance, String kind, String message) implements SharedTierConfigError {
            static final Fn2<UnknownKind, String, String> FACTORY = Causes.forTwoValues("[storage.%s.shared] kind '%s' is not a shared tier kind: use 'dht' or 's3'",
                                                                                        UnknownKind::new);
        }

        record MissingKey(String instance, String key, String message) implements SharedTierConfigError {
            static final Fn2<MissingKey, String, String> FACTORY = Causes.forTwoValues("[storage.%s.shared] kind 's3' requires %s",
                                                                                       MissingKey::new);
        }

        /// A key that would carry a secret. The value is never echoed.
        record InlineSecretRejected(String instance, String key, String message) implements SharedTierConfigError {
            static final Fn2<InlineSecretRejected, String, String> FACTORY = Causes.forTwoValues("[storage.%s.shared] %s: inline secrets are rejected; set credentials to 'env', 'file:<path>' or 'instance'",
                                                                                                 InlineSecretRejected::new);
        }

        /// A `credentials` value that is not a known source. The value is never echoed: it may be a secret.
        record InvalidCredentials(String instance, String message) implements SharedTierConfigError {
            static final Fn1<InvalidCredentials, String> FACTORY = Causes.forOneValue("[storage.%s.shared] credentials must be 'env', 'file:<path>' or 'instance' (the value given is not shown; inline secrets are rejected)",
                                                                                      InvalidCredentials::new);
        }

        record InvalidValue(String instance, String key, String expected, String message) implements SharedTierConfigError {
            static final Fn3<InvalidValue, String, String, String> FACTORY = Causes.forThreeValues("[storage.%s.shared] %s must be %s",
                                                                                                   InvalidValue::new);
        }

        record UnknownKey(String instance, String key, String message) implements SharedTierConfigError {
            static final Fn2<UnknownKey, String, String> FACTORY = Causes.forTwoValues("[storage.%s.shared] has no key %s for its kind",
                                                                                       UnknownKey::new);
        }
    }

    /// Keys a `kind = "s3"` section may carry.
    Set<String> S3_KEYS = Set.of("kind",
                                 "endpoint",
                                 "region",
                                 "bucket",
                                 "prefix",
                                 "path_style",
                                 "credentials",
                                 "max_inflight",
                                 "request_timeout");

    /// Key names that can only hold a secret. Named in their own refusal so the operator learns why.
    Set<String> SECRET_KEYS = Set.of("access_key",
                                     "access_key_id",
                                     "aws_access_key_id",
                                     "secret",
                                     "secret_key",
                                     "secret_access_key",
                                     "aws_secret_access_key",
                                     "session_token",
                                     "aws_session_token",
                                     "password",
                                     "token");

    /// Parses `[storage.<instance>.shared]` from `doc`. Absent section -> [Dht].
    static Result<SharedTierConfig> sharedTierConfig(TomlDocument doc, String instance) {
        var section = "storage." + instance + ".shared";

        return doc.hasSection(section)
               ? refuseSecretKeys(doc, section, instance).flatMap(_ -> byKind(doc, section, instance))
               : success(dht());
    }

    private static Result<SharedTierConfig> byKind(TomlDocument doc, String section, String instance) {
        var kind = doc.getString(section, "kind").map(String::trim).or(KIND_DHT);

        return switch (kind) {
            case KIND_DHT -> refuseUnknownKeys(doc, section, instance, Set.of("kind")).map(_ -> dht());
            case KIND_S3 -> refuseUnknownKeys(doc, section, instance, S3_KEYS).flatMap(_ -> s3(doc, section, instance));
            default -> SharedTierConfigError.UnknownKind.FACTORY.apply(instance, kind).result();
        };
    }

    private static Result<SharedTierConfig> s3(TomlDocument doc, String section, String instance) {
        return Result.all(required(doc, section, instance, "endpoint"),
                          required(doc, section, instance, "region"),
                          required(doc, section, instance, "bucket"),
                          booleanKey(doc, section, instance, "path_style", false),
                          credentials(doc, section, instance),
                          maxInflight(doc, section, instance),
                          requestTimeout(doc, section, instance)).map((endpoint, region, bucket, pathStyle, credentials, maxInflight, timeout) -> new S3(endpoint,
                                                                                                                                                         region,
                                                                                                                                                         bucket,
                                                                                                                                                         doc.getString(section,
                                                                                                                                                                       "prefix")
                                                                                                                                                            .or(""),
                                                                                                                                                         pathStyle,
                                                                                                                                                         credentials,
                                                                                                                                                         maxInflight,
                                                                                                                                                         timeout));
    }

    private static Result<String> required(TomlDocument doc, String section, String instance, String key) {
        return doc.getString(section, key)
                  .map(String::trim)
                  .filter(value -> !value.isEmpty())
                  .toResult(SharedTierConfigError.MissingKey.FACTORY.apply(instance, key));
    }

    private static Result<Boolean> booleanKey(TomlDocument doc,
                                              String section,
                                              String instance,
                                              String key,
                                              boolean fallback) {
        return doc.hasKey(section, key)
               ? doc.getBoolean(section, key)
                    .toResult(SharedTierConfigError.InvalidValue.FACTORY.apply(instance, key, "true or false"))
               : success(fallback);
    }

    private static Result<Credentials> credentials(TomlDocument doc, String section, String instance) {
        return doc.getString(section, "credentials")
                  .map(String::trim)
                  .toResult(SharedTierConfigError.MissingKey.FACTORY.apply(instance,
                                                                           "credentials ('env', 'file:<path>' or 'instance')"))
                  .flatMap(value -> credentialsOf(value, instance));
    }

    private static Result<Credentials> credentialsOf(String value, String instance) {
        if (value.equals("env")) {
            return success(new Credentials.Environment());
        }

        if (value.equals("instance")) {
            return success(new Credentials.InstanceProfile());
        }

        return some(value).filter(text -> text.startsWith("file:"))
                   .map(text -> text.substring("file:".length())
                                    .trim())
                   .filter(path -> !path.isEmpty())
                   .<Credentials> map(path -> new Credentials.File(Path.of(path)))
                   .toResult(SharedTierConfigError.InvalidCredentials.FACTORY.apply(instance));
    }

    private static Result<Integer> maxInflight(TomlDocument doc, String section, String instance) {
        return doc.hasKey(section, "max_inflight")
               ? doc.getInt(section, "max_inflight")
                    .filter(value -> value > 0)
                    .toResult(SharedTierConfigError.InvalidValue.FACTORY.apply(instance,
                                                                               "max_inflight",
                                                                               "a positive integer"))
               : success(DEFAULT_MAX_INFLIGHT);
    }

    /// `org.pragmatica.lang.parse.TimeSpan` is named in full: this file's `TimeSpan` is the unrelated
    /// `org.pragmatica.lang.io.TimeSpan` the tier consumes (the same split as `ConfigLoader#parseTimeSpan`).
    private static Result<TimeSpan> requestTimeout(TomlDocument doc, String section, String instance) {
        return doc.hasKey(section, "request_timeout")
               ? doc.getString(section, "request_timeout")
                    .flatMap(SharedTierConfig::positiveDuration)
                    .toResult(SharedTierConfigError.InvalidValue.FACTORY.apply(instance,
                                                                               "request_timeout",
                                                                               "a positive duration such as '30s'"))
               : success(DEFAULT_REQUEST_TIMEOUT);
    }

    private static Option<TimeSpan> positiveDuration(String text) {
        return org.pragmatica.lang.parse.TimeSpan.timeSpan(text)
                                                 .option()
                                                 .map(parsed -> TimeSpan.fromDuration(parsed.duration()))
                                                 .filter(parsed -> parsed.millis() > 0);
    }

    private static Result<Set<String>> refuseSecretKeys(TomlDocument doc, String section, String instance) {
        return firstOf(doc.keys(section), SECRET_KEYS::contains).fold(() -> success(doc.keys(section)),
                                                                      key -> SharedTierConfigError.InlineSecretRejected.FACTORY.apply(instance,
                                                                                                                                      key).result());
    }

    private static Result<Set<String>> refuseUnknownKeys(TomlDocument doc,
                                                         String section,
                                                         String instance,
                                                         Set<String> allowed) {
        return firstOf(doc.keys(section), key -> !allowed.contains(key)).fold(() -> success(doc.keys(section)),
                                                                              key -> SharedTierConfigError.UnknownKey.FACTORY.apply(instance,
                                                                                                                                    key).result());
    }

    private static Option<String> firstOf(Set<String> keys, Predicate<String> predicate) {
        return Option.from(List.copyOf(keys).stream().sorted().filter(predicate).findFirst());
    }
}
