// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.storage;

import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.cluster.ClusterHttpClient;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import static org.pragmatica.aether.management.route.ManagementRoute.STORAGE_RETENTION;


/// #634-3/4 — the tri-floor retention view: per stream partition, the WAL's live counters (size,
/// truncation watermark, fsync latency), the in-memory ring tail, the sealed bound, the earliest
/// retained segment, the entity checkpoint floor, and the joint invariant verdict. LOCAL route: the
/// floors describe the node you ask (checkpoints come from replicated KV, so those agree everywhere);
/// a `violated: true` row means that node cannot rebuild the partition from its checkpoint — the same
/// condition a fold would refuse on, surfaced before the refusal is the first symptom.
@Command(name = "retention", description = "Show per-partition WAL + retention floors and the tri-floor invariant verdict")
@SuppressWarnings("JBCT-RET-01")
class StorageRetentionCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private StorageCommand parent;

    @Override
    public Integer call() {
        return ClusterHttpClient.fetch(STORAGE_RETENTION).fold(StorageCliHelper::onFailure,
                                                               json -> OutputFormatter.printQuery(json,
                                                                                                  parent.outputOptions()));
    }
}
