package org.pragmatica.storage;

import org.pragmatica.cloud.aws.s3.S3Config;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


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
        return Result.all(requireS3Config(s3Config), requirePrefix(prefix), requirePositive(maxBytes)).map(RemoteTierConfig::new);
    }

    /// Creates a validated remote tier configuration with default "blocks" prefix.
    public static Result<RemoteTierConfig> remoteTierConfig(S3Config s3Config, long maxBytes) {
        return remoteTierConfig(s3Config, "blocks", maxBytes);
    }

    private static Result<S3Config> requireS3Config(S3Config s3Config) {
        return option(s3Config).toResult(ConfigError.S3_CONFIG_REQUIRED);
    }

    private static Result<String> requirePrefix(String prefix) {
        return option(prefix).filter(value -> !value.isBlank())
                     .toResult(ConfigError.PREFIX_REQUIRED);
    }

    private static Result<Long> requirePositive(long maxBytes) {
        return maxBytes > 0
               ? success(maxBytes)
               : ConfigError.MAX_BYTES_NOT_POSITIVE.result();
    }
}
