// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Option;


/// The stream and consumer keys that parse but have no runtime effect, as ONE table (#2201 E-02, spec 3.2 item 9). The deploy
/// validator and the activation binder both judge a declaration by it, so a key the validator refuses cannot bind silently on
/// the path a node provisions a stream from (a configuration pushed through the KV layer reaches activation without passing
/// the validator). Each key is refused, not made effective.
public sealed interface InertKeyRules {
    /// One inert key of a declaration: its name, the value declared, and why it has no effect.
    record InertKey(String key, String value, String message) {}

    /// The stream keys of `config` that have no runtime effect: `encryption_key_id`, `compression` unless `none`, and
    /// `auto_offset_reset` unless `earliest`.
    static List<InertKey> inertStreamKeys(StreamConfig config) {
        return Stream.of(config.encryptionKeyId().map(InertKeyRules::inertEncryption),
                         inertCompression(config),
                         inertOffsetReset(config))
                     .flatMap(Option::stream)
                     .toList();
    }

    /// `consistency = "strong"`: no write path honours it (#1262).
    static Option<InertKey> unsupportedConsistency(StreamConfig config) {
        return config.consistencyMode() == ConsistencyMode.STRONG
               ? Option.some(new InertKey("consistency", "strong", strongConsistencyMessage("consistency")))
               : Option.none();
    }

    /// Why a STRONG declaration under `key` is refused, the same text on both paths.
    static String strongConsistencyMessage(String key) {
        return key
             + " 'strong' cannot be honoured — the consensus publish path is not wired in "
             + "this release, so every write to the stream would be refused (#1262). Remove the "
             + "key or set it to 'eventual'.";
    }

    /// The consumer keys of `config` that differ from the consumer default: per-consumer configuration never reaches the
    /// runtime consumer, so any non-default value would be ignored.
    static List<InertKey> inertConsumerKeys(ConsumerConfig config) {
        var defaults = ConsumerConfig.consumerConfig(config.groupId());

        return Stream.of(differs("batch_size",
                                 config.maxBatchSize(),
                                 defaults.maxBatchSize()),
                         differs("processing",
                                 lower(config.processingMode()),
                                 lower(defaults.processingMode())),
                         differs("on_failure",
                                 lower(config.errorStrategy()),
                                 lower(defaults.errorStrategy())),
                         differs("checkpoint_interval",
                                 config.checkpointInterval().toString(),
                                 defaults.checkpointInterval().toString()),
                         differs("max_retries",
                                 config.maxRetries(),
                                 defaults.maxRetries()),
                         differs("dead_letter",
                                 config.deadLetterStream(),
                                 defaults.deadLetterStream()),
                         differs("read_preference",
                                 lower(config.readPreference()),
                                 lower(defaults.readPreference())))
                     .flatMap(Option::stream)
                     .toList();
    }

    private static InertKey inertEncryption(String keyId) {
        return new InertKey("encryption_key_id",
                            keyId,
                            "encryption_key_id '" + keyId
                           + "' has no runtime effect — every stream is written through a single, shared "
                           + "segment sink with no encryptor wired to it (tracked as #253: BlockEncryptor has "
                           + "no production key source). Remove the key; it does not protect this stream's "
                           + "data at rest.");
    }

    private static Option<InertKey> inertCompression(StreamConfig config) {
        return config.compression() == StreamCompression.NONE
               ? Option.none()
               : Option.some(new InertKey("compression",
                                          lower(config.compression()),
                                          "compression '" + config.compression()
                                         + "' has no runtime effect — segments are always written uncompressed "
                                         + "regardless of this setting. Remove the key or set it to 'none'; stream "
                                         + "compression is not supported in 1.0 (descoped in #677)."));
    }

    private static Option<InertKey> inertOffsetReset(StreamConfig config) {
        return "earliest".equalsIgnoreCase(config.autoOffsetReset())
               ? Option.none()
               : Option.some(new InertKey("auto_offset_reset",
                                          config.autoOffsetReset(),
                                          "auto_offset_reset '" + config.autoOffsetReset()
                                         + "' has no runtime effect — a never-committed consumer always starts at offset 0 "
                                         + "(earliest) per the #478 ruling, permanently, not as a gap to be closed later. "
                                         + "Remove the key or set it to 'earliest', its only honest value."));
    }

    private static Option<InertKey> differs(String key, Object value, Object defaultValue) {
        return value.equals(defaultValue)
               ? Option.none()
               : Option.some(new InertKey(key, String.valueOf(value), consumerMessage(key, String.valueOf(value))));
    }

    private static String lower(Enum<?> value) {
        return value.toString()
                    .toLowerCase();
    }

    private static String consumerMessage(String key, String value) {
        return key
             + " = '" + value
             + "' has no runtime effect — per-consumer TOML config (`[streams.X.consumers.Y]`) is "
             + "parsed but never reaches the runtime consumer (StreamConfigParser#parseConsumers has "
             + "no production caller, per #576); every declarative consumer runs with hardcoded "
             + "defaults regardless of this key. Remove it: per-consumer tuning is not supported "
             + "in 1.0 (descoped in #677; post-GA wiring is its own epic).";
    }

    record unused() implements InertKeyRules {}
}
