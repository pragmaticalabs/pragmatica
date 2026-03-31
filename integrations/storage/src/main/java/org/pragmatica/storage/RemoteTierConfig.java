package org.pragmatica.storage;

import org.pragmatica.cloud.aws.s3.S3Config;

/// Configuration for S3-backed remote storage tier.
public record RemoteTierConfig(S3Config s3Config, String prefix, long maxBytes) {

    /// Creates a remote tier configuration with the given S3 config, key prefix, and capacity limit.
    public static RemoteTierConfig remoteTierConfig(S3Config s3Config, String prefix, long maxBytes) {
        return new RemoteTierConfig(s3Config, prefix, maxBytes);
    }

    /// Creates a remote tier configuration with default "blocks" prefix.
    public static RemoteTierConfig remoteTierConfig(S3Config s3Config, long maxBytes) {
        return new RemoteTierConfig(s3Config, "blocks", maxBytes);
    }
}
