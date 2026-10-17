// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.parser.ast.common;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;


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
                           parts.stream().map(Identifier::normalized).toList());
    }

    @Override
    public String toString() {
        return String.join(".",
                           parts.stream().map(Identifier::toString).toList());
    }
}
