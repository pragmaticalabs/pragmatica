// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


public record ProvisioningContext(List<TypeToken<?>> typeTokens,
                                  Option<Fn1<?, ?>> keyExtractor,
                                  Map<Class<?>, Object> extensions) {
    private static final Fn1<Cause, String> MISSING_EXTENSION = Causes.forOneValue("Context does not contain %s");

    public static ProvisioningContext provisioningContext() {
        return new ProvisioningContext(List.of(), none(), Map.of());
    }

    public ProvisioningContext withTypeToken(TypeToken<?> token) {
        var tokens = new ArrayList<>(typeTokens);

        tokens.add(token);

        return new ProvisioningContext(List.copyOf(tokens), keyExtractor, extensions);
    }

    public ProvisioningContext withKeyExtractor(Fn1<?, ?> extractor) {
        return new ProvisioningContext(typeTokens, some(extractor), extensions);
    }

    @SuppressWarnings("unchecked")
    public <T> Result<T> extension(Class<T> type) {
        return option((T) extensions.get(type)).toResult(MISSING_EXTENSION.apply(type.getSimpleName()));
    }

    /// Whether the CALLER already supplied an extension of this type. Runtime enrichment consults
    /// this so a node-wide default never displaces a value the slice deliberately provided (#526).
    public boolean hasExtension(Class<?> type) {
        return extensions.containsKey(type);
    }

    public <T> ProvisioningContext withExtension(Class<T> type, T value) {
        var newExtensions = new HashMap<>(extensions);

        newExtensions.put(type, value);

        return new ProvisioningContext(typeTokens, keyExtractor, Map.copyOf(newExtensions));
    }
}
