// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.lang.Option.none;


/// The storage declaration of a stream (#3334): its `[retention]`, `[truncation]` and `[hot]` sections and its
/// `expected_ingress`, each optional. `expectedIngressBytesPerSecond` is a PLAN INPUT ONLY, for the whole stream: the
/// engine never enforces it and never sizes anything from it.
///
/// `storage` is the stream's storage mode; absent = [StreamStorage#PERSISTENT]. It is the LAST component, so an encoding with
/// `storage = none` is the first four components followed by exactly one `none` byte. A value carrying `some(UNKNOWN)` was decoded
/// from a newer build's mode and is never written back (`StreamPartitionManager.publishStreamConfig` refuses it).
///
/// Carried by [StreamConfig#declaration]. The parser sets the first four components and no mode; nothing reads `storage` yet.
@Codec
public record StreamDeclaration(Option<RetentionDeclaration> retention,
                                Option<TruncationDeclaration> truncation,
                                Option<HotWindowDeclaration> hot,
                                Option<Long> expectedIngressBytesPerSecond,
                                Option<StreamStorage> storage) {
    public static StreamDeclaration streamDeclaration(Option<RetentionDeclaration> retention,
                                                      Option<TruncationDeclaration> truncation,
                                                      Option<HotWindowDeclaration> hot,
                                                      Option<Long> expectedIngressBytesPerSecond) {
        return streamDeclaration(retention, truncation, hot, expectedIngressBytesPerSecond, none());
    }

    public static StreamDeclaration streamDeclaration(Option<RetentionDeclaration> retention,
                                                      Option<TruncationDeclaration> truncation,
                                                      Option<HotWindowDeclaration> hot,
                                                      Option<Long> expectedIngressBytesPerSecond,
                                                      Option<StreamStorage> storage) {
        return new StreamDeclaration(retention, truncation, hot, expectedIngressBytesPerSecond, storage);
    }
}
