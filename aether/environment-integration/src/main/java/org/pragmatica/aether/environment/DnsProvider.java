// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


public interface DnsProvider {
    Promise<Unit> upsertRecord(String hostname, List<String> addresses, DnsRecordType type);
    Promise<Unit> removeRecord(String hostname, DnsRecordType type);
    Promise<List<String>> resolve(String hostname);
}
