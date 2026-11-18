// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;


/// A stream's `[truncation]` section as declared (#3334). `groupIdleExpiryMs` is meaningful only under
/// [TruncationPolicy#UNTIL_CONSUMED]. Every key is optional; an absent key is `none`, and the defaults and refusals
/// are applied by the stream parser, not here.
@Codec
public record TruncationDeclaration(Option<TruncationPolicy> policy, Option<Long> groupIdleExpiryMs) {
    public static TruncationDeclaration truncationDeclaration(Option<TruncationPolicy> policy,
                                                              Option<Long> groupIdleExpiryMs) {
        return new TruncationDeclaration(policy, groupIdleExpiryMs);
    }
}
