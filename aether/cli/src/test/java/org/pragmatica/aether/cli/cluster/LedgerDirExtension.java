// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.pragmatica.aether.environment.ClusterName;

/// Gives each test its own ledger directory (so no test writes a real `~/.aether` ledger and two tests never share
/// one) and a recorder over a RUNNING ledger for the cluster the test names. Register with
/// `@RegisterExtension final LedgerDirExtension ledger = new LedgerDirExtension();`.
final class LedgerDirExtension implements BeforeEachCallback, AfterEachCallback {
    private Path root;
    private Function<ClusterName, Path> originalResolver;

    @Override
    @SuppressWarnings("JBCT-EX-01")
    public void beforeEach(ExtensionContext context) throws Exception {
        root = Files.createTempDirectory("apply-ledger-test");
        originalResolver = ApplyState.stateDirResolver;
        ApplyState.stateDirResolver = name -> root.resolve(name.value());
        ApplyLedger.trace = _ -> {};
        ApplyRecorder.stepHook = _ -> {};
    }

    @Override
    @SuppressWarnings("JBCT-EX-01")
    public void afterEach(ExtensionContext context) throws Exception {
        ApplyState.stateDirResolver = originalResolver;
        ApplyRecorder.stepHook = _ -> {};

        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    static final String TEST_ACTION = "AddRole|test||->n";

    /// A recorder over a fresh RUNNING ledger for `clusterName`, written to disk, with the action [#TEST_ACTION] started.
    ApplyRecorder recorder(ClusterName clusterName) {
        return recorder(clusterName, List.of(TEST_ACTION));
    }

    /// The recorder over a RUNNING ledger whose actions are `keys`, PENDING, the first one started.
    ApplyRecorder recorder(ClusterName clusterName, List<String> keys) {
        var base = ApplyStateFixtures.state(clusterName);
        var state = ApplyState.applyState(base.applyId(),
                                          clusterName,
                                          base.configHash(),
                                          base.storedVersion(),
                                          base.startedAt(),
                                          base.phase(),
                                          keys.stream().map(key -> ApplyAction.applyAction(key, 0, ApplyAction.Status.PENDING)).toList(),
                                          List.of(),
                                          List.of(),
                                          List.of(),
                                          List.of());

        ApplyState.save(state).unwrap();

        var recorder = ApplyRecorder.applyRecorder(state);

        recorder.beginAction(keys.getFirst()).unwrap();

        return recorder;
    }

    ApplyState ledger(ClusterName clusterName) {
        return ApplyState.read(clusterName).unwrap().unwrap();
    }
}
