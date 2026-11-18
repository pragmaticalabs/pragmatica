// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;


/// The storage declaration of a stream (#3334): its `[retention]`, `[truncation]` and `[hot]` sections and its
/// `expected_ingress`, each optional. `expectedIngressBytesPerSecond` is a PLAN INPUT ONLY, for the whole stream: the
/// engine never enforces it and never sizes anything from it.
///
/// Carried by [StreamConfig#declaration]. Nothing parses or enforces a declaration yet; every config built today carries
/// `none`.
@Codec
public record StreamDeclaration(Option<RetentionDeclaration> retention,
                                Option<TruncationDeclaration> truncation,
                                Option<HotWindowDeclaration> hot,
                                Option<Long> expectedIngressBytesPerSecond) {
    public static StreamDeclaration streamDeclaration(Option<RetentionDeclaration> retention,
                                                      Option<TruncationDeclaration> truncation,
                                                      Option<HotWindowDeclaration> hot,
                                                      Option<Long> expectedIngressBytesPerSecond) {
        return new StreamDeclaration(retention, truncation, hot, expectedIngressBytesPerSecond);
    }
}
