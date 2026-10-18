// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.example.database;

import org.pragmatica.aether.resource.db.Sql;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;


/// Construction itself queries the migrated table, pinning startup ordering in the database proof.
@Slice
public interface SchemaProbe {
    Promise<Integer> count();

    static Promise<SchemaProbe> schemaProbe(@Sql SqlConnector connector) {
        return connector.queryOne("SELECT COUNT(*) AS total FROM products",
                                  row -> row.getInt("total"))
                        .map(count -> () -> Promise.success(count));
    }
}
