// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


public interface DnsProvider {
    Promise<Unit> upsertRecord(String hostname, List<String> addresses, DnsRecordType type);
    Promise<Unit> removeRecord(String hostname, DnsRecordType type);
    Promise<List<String>> resolve(String hostname);
}
