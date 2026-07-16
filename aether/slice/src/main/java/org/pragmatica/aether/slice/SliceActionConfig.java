// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.aether.slice.repository.maven.LocalRepository.localRepository;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


@SuppressWarnings("JBCT-SEQ-01")
public record SliceActionConfig(TimeSpan loadingTimeout,
                                TimeSpan activatingTimeout,
                                TimeSpan deactivatingTimeout,
                                TimeSpan unloadingTimeout,
                                TimeSpan startStopTimeout,
                                List<Repository> repositories,
                                Option<Path> frameworkJarsPath) {
    public static SliceActionConfig sliceActionConfig() {
        return new SliceActionConfig(timeSpan(2).minutes(),
                                     timeSpan(1).minutes(),
                                     timeSpan(30).seconds(),
                                     timeSpan(2).minutes(),
                                     timeSpan(5).seconds(),
                                     List.of(localRepository()),
                                     Option.empty());
    }

    public static SliceActionConfig sliceActionConfig(Path frameworkJarsPath) {
        return new SliceActionConfig(timeSpan(2).minutes(),
                                     timeSpan(1).minutes(),
                                     timeSpan(30).seconds(),
                                     timeSpan(2).minutes(),
                                     timeSpan(5).seconds(),
                                     List.of(localRepository()),
                                     option(frameworkJarsPath));
    }

    public Result<TimeSpan> timeoutFor(SliceState state) {
        return switch (state) {
            case SliceState.LOADING -> success(loadingTimeout);
            case SliceState.ACTIVATING -> success(activatingTimeout);
            case SliceState.DEACTIVATING -> success(deactivatingTimeout);
            case SliceState.UNLOADING -> success(unloadingTimeout);
            default -> NO_TIMEOUT_CONFIGURED.apply(state).result();
        };
    }

    private static final Fn1<Cause, SliceState> NO_TIMEOUT_CONFIGURED = Causes.forOneValue("No timeout configured for state: %s");
}
