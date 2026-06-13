// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.jooq;

import java.util.Set;


public record JooqXmlConfig(String catalogName,
                            String defaultSchemaName,
                            Set<String> includedSchemas,
                            String dialect,
                            boolean emitEnums,
                            boolean emitIndexes,
                            boolean emitCheckConstraints,
                            boolean emitComments,
                            boolean sortElements,
                            boolean prettyPrint) {
    public static JooqXmlConfig jooqXmlConfig() {
        return new JooqXmlConfig("", "public", Set.of("public"), "POSTGRES", true, true, true, true, true, true);
    }

    public JooqXmlConfig withDefaultSchemaName(String name) {
        return new JooqXmlConfig(catalogName,
                                 name,
                                 includedSchemas,
                                 dialect,
                                 emitEnums,
                                 emitIndexes,
                                 emitCheckConstraints,
                                 emitComments,
                                 sortElements,
                                 prettyPrint);
    }

    public JooqXmlConfig withIncludedSchemas(Set<String> schemas) {
        return new JooqXmlConfig(catalogName,
                                 defaultSchemaName,
                                 schemas,
                                 dialect,
                                 emitEnums,
                                 emitIndexes,
                                 emitCheckConstraints,
                                 emitComments,
                                 sortElements,
                                 prettyPrint);
    }
}
