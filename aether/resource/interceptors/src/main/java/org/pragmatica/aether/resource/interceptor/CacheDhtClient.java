// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.dht.DHTClient;


/// The DHT client of the CACHE namespace — the committed `[cache]` replication (#1777 track 1), RF 1 / CF 1 by default.
/// A distinct extension type from the bare `DHTClient`, which is the cluster's replicated DHT at the committed
/// `[replication]` factors: a namespace takes the cache's lower replication only by asking for it by name. Idempotency
/// must NOT (owner/CTO ruling Q4) — a lost dedup record re-executes a call — so it asks for `DHTClient`.
public record CacheDhtClient(DHTClient client) {}
