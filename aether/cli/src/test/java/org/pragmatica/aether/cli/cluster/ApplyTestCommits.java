// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;

/// Stand-ins for the cluster's config commit ([ApplyCommit#toCluster]) in tests that exercise the orchestrator without a cluster.
final class ApplyTestCommits {
    private ApplyTestCommits() {}

    /// A commit the cluster accepts.
    static Fn1<Result<String>, Long> ok() {
        return _ -> Result.success("{}");
    }

    /// A commit that records the version it was fenced by, then is accepted.
    static Fn1<Result<String>, Long> recording(List<Long> versions) {
        return version -> {
            versions.add(version);

            return Result.success("{}");
        };
    }

    /// A commit that fails with `cause`.
    static Fn1<Result<String>, Long> failing(Cause cause) {
        return _ -> cause.result();
    }

    static List<Long> versions() {
        return new ArrayList<>();
    }
}
