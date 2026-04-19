/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.consensus.topology;

import org.pragmatica.lang.Option;

/// Pull-style adapter that exposes the latest cluster-generation membership view to
/// consensus-layer consumers that otherwise cannot depend on `aether/slice` snapshot
/// types.
///
/// Implementations (e.g. `NodeSnapshotCache` in `aether/node`) decide when a snapshot
/// is "current" — on the consensus side we only read `currentMembershipView()` and
/// `observedRabiaTerm()` under the assumption that the returned view, if present, is
/// internally consistent with the reported term.
///
/// The `Option.none()` return from `currentMembershipView()` is the signal to fall back
/// to legacy in-memory state — either because no snapshot has ever been received, the
/// snapshot could not be decoded, or the source is deliberately a no-op (e.g. tests
/// and legacy code paths).
///
/// See `aether/docs/specs/cluster-generation-spec.md` §7.2.
public interface GenerationSnapshotSource {
    /// Latest membership view projected from the cached snapshot, if one is available.
    Option<MembershipView> currentMembershipView();

    /// Rabia term observed by the source's most recent ping acceptance. Returns `0`
    /// when no ping has ever been processed.
    long observedRabiaTerm();

    /// Observed cluster-generation epoch composed from [#observedRabiaTerm()] with a
    /// zero local counter. Publishers stamp outbound KV values with this epoch so that
    /// downstream watchers can detect stale-fence anomalies without taking a hard
    /// dependency on snapshot types.
    ///
    /// Default implementation returns `(observedRabiaTerm(), 0L)`. Implementations that
    /// track a richer epoch internally may override.
    default long observedEpochRabiaTerm() {
        return observedRabiaTerm();
    }

    /// No-op source used where snapshot wiring is not yet available (legacy factory
    /// overloads, unit tests that focus on non-snapshot behaviour).
    static GenerationSnapshotSource noop() {
        return NoopGenerationSnapshotSource.INSTANCE;
    }
}

final class NoopGenerationSnapshotSource implements GenerationSnapshotSource {
    static final NoopGenerationSnapshotSource INSTANCE = new NoopGenerationSnapshotSource();

    private NoopGenerationSnapshotSource() {}

    @Override public Option<MembershipView> currentMembershipView() {
        return Option.none();
    }

    @Override public long observedRabiaTerm() {
        return 0L;
    }
}
