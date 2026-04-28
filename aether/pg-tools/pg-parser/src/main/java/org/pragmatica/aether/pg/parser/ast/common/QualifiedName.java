// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.parser.ast.common;

import org.pragmatica.lang.Option;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;

import java.util.List;


public record QualifiedName(SourceSpan span, List<Identifier> parts) {
    public Identifier name() {
        return parts.getLast();
    }

    public Option<Identifier> schema() {
        return parts.size() > 1
              ? Option.present(parts.getFirst())
              : Option.empty();
    }

    public String normalized() {
        return String.join(".",
                           parts.stream().map(Identifier::normalized)
                                       .toList());
    }

    @Override public String toString() {
        return String.join(".",
                           parts.stream().map(Identifier::toString)
                                       .toList());
    }
}
