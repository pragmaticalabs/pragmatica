// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.lang.Result;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.pragmatica.lang.Result.success;


/// Registers all system-namespace streams into a [StreamRegistry] at cluster bootstrap.
///
/// The framework holds an implicit producer reference to each system stream for the cluster's
/// lifetime (spec §6.1, §8.1). Registration is idempotent: streams already present are skipped
/// so bootstrap survives restarts where the registry state is preserved.
///
/// Invoked unconditionally from the node startup path; per the event-stream-namespaces spec
/// (RC1-mandatory) there is no feature flag gating system-stream registration.
public final class SystemStreamBootstrap {
    private final StreamRegistry registry;
    private final RetentionPolicy defaultRetention;
    private final Clock clock;

    public SystemStreamBootstrap(StreamRegistry registry, RetentionPolicy defaultRetention, Clock clock) {
        this.registry = registry;
        this.defaultRetention = defaultRetention;
        this.clock = clock;
    }

    public SystemStreamBootstrap(StreamRegistry registry) {
        this(registry, RetentionPolicy.retentionPolicy(), Clock.systemUTC());
    }

    /// Register every address in [SystemStreams.ALL]. Addresses already present are treated as
    /// idempotent no-ops, not errors. Short-circuits on the first registration failure.
    public Result<List<StreamRegistryEntry>> bootstrap() {
        var accumulated = new ArrayList<StreamRegistryEntry>(SystemStreams.ALL.size());
        for (var address : SystemStreams.ALL) {
            var next = ensureRegistered(address);
            if (next.isFailure()) {
                return next.fold(Result::failure, _ -> success(List.of()));
            }
            next.onSuccess(accumulated::add);
        }
        return success(List.copyOf(accumulated));
    }

    private Result<StreamRegistryEntry> ensureRegistered(StreamAddress address) {
        return registry.lookup(address)
                       .fold(() -> registerFramework(address), Result::success);
    }

    private Result<StreamRegistryEntry> registerFramework(StreamAddress address) {
        var entry = StreamRegistryEntry.framework(address, defaultRetention, clock.instant());
        return registry.register(entry);
    }
}
