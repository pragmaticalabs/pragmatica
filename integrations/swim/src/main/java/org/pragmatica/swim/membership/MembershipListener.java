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

package org.pragmatica.swim.membership;

import org.pragmatica.lang.Contract;

/// Push-channel listener for stable membership transitions produced by
/// [`MembershipTracker`]. Invoked exactly once per stable transition, on the
/// sample-tick thread, after the tracker's internal set has been updated.
///
/// Side-effecting by contract — the callback typically routes a reconcile / placement
/// signal — hence [`Contract`] on the single method.
@FunctionalInterface
public interface MembershipListener {
    @Contract
    void onMembershipChange(MembershipChange change);

    /// No-op listener for tracker instances constructed without a downstream consumer
    /// (e.g. read-only `members()` / quorum probes in tests).
    MembershipListener NOOP = _ -> {};
}
