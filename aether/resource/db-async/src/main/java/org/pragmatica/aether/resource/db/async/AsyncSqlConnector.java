// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.util.function.Consumer;

import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Promise;
import org.pragmatica.postgres.net.Listening;


public interface AsyncSqlConnector extends SqlConnector {
    Promise<Listening> subscribe(String channel, Consumer<String> onNotification);
}
