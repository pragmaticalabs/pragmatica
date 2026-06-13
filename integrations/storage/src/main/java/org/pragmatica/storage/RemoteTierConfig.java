package org.pragmatica.storage;

import org.pragmatica.cloud.aws.s3.S3Config;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

/// Configuration for S3-backed remote storage tier.
public record RemoteTierConfig(S3Config s3Config, String prefix, long maxBytes) {

    /// Validation failures for remote tier configuration.
    enum ConfigError implements Cause {
        S3_CONFIG_REQUIRED("s3Config must not be null"),
        PREFIX_REQUIRED("prefix must not be null or blank"),
        MAX_BYTES_NOT_POSITIVE("maxBytes must be positive");

        private final String message;

        ConfigError(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }

    /// Creates a validated remote tier configuration with the given S3 config, key prefix, and
    /// capacity limit. Validation is performed here (parse-don't-validate) so construction never
    /// throws: an invalid combination yields a [`Result.failure`].
    public static Result<RemoteTierConfig> remoteTierConfig(S3Config s3Config, String prefix, long maxBytes) {
        return validate(s3Config, prefix, maxBytes)
            .map(_ -> new RemoteTierConfig(s3Config, prefix, maxBytes));
    }

    /// Creates a validated remote tier configuration with default "blocks" prefix.
    public static Result<RemoteTierConfig> remoteTierConfig(S3Config s3Config, long maxBytes) {
        return remoteTierConfig(s3Config, "blocks", maxBytes);
    }

    private static Result<Boolean> validate(S3Config s3Config, String prefix, long maxBytes) {
        if (s3Config == null) {
            return ConfigError.S3_CONFIG_REQUIRED.result();
        }
        if (prefix == null || prefix.isBlank()) {
            return ConfigError.PREFIX_REQUIRED.result();
        }
        if (maxBytes <= 0) {
            return ConfigError.MAX_BYTES_NOT_POSITIVE.result();
        }
        return Result.success(true);
    }
}
