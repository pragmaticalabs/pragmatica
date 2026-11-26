// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.pragmatica.aether.deployment.cluster.FencePort;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

/// A [FencePort] whose witnesses the test settles by hand (#3754 lane (b2) tests). Each `fenced` call is recorded and answers an
/// unresolved promise; [#accept] runs the recorded effect, as the CCL port does when the commit is accepted, and answers the effect's
/// outcome; [#refuse] fails the witness without running the effect.
final class ScriptedFencePort implements FencePort {
    private record Submitted<T>(Supplier<Promise<T>> effect, Promise<T> outcome) {
        static <T> Submitted<T> submitted(Supplier<Promise<T>> effect) {
            return new Submitted<>(effect, Promise.promise());
        }
    }

    private final List<Submitted<?>> submitted = new CopyOnWriteArrayList<>();

    static ScriptedFencePort scriptedFencePort() {
        return new ScriptedFencePort();
    }

    @Override
    public <T> Promise<T> fenced(List<KVCommand.ReadWitness<AetherKey>> guards, Supplier<Promise<T>> effect) {
        var entry = Submitted.submitted(effect);

        submitted.add(entry);

        return entry.outcome();
    }

    /// How many witnesses were submitted.
    int submitCount() {
        return submitted.size();
    }

    /// The commit of witness `index` is accepted: its effect runs on this thread and its outcome settles the witness.
    void accept(int index) {
        accept(submitted.get(index));
    }

    private static <T> void accept(Submitted<T> entry) {
        entry.effect()
             .get()
             .onResult(entry.outcome()::resolve);
    }

    /// The commit of witness `index` is refused with `cause`; its effect never runs.
    void refuse(int index, Cause cause) {
        submitted.get(index)
                 .outcome()
                 .fail(cause);
    }
}
