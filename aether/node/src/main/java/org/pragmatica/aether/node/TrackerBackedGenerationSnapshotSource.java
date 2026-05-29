// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.membership.MembershipPhase;
import org.pragmatica.swim.membership.MembershipTracker;

import java.util.concurrent.atomic.AtomicReference;

/// Membership-unification P2-b adapter: exposes the SWIM-fed [`MembershipTracker`] as the
/// live [`MembershipView`] read by the consensus `TopologyObserver` for quorum, while
/// delegating the Rabia term/epoch to the underlying KV-projected source.
///
/// The tracker is published lazily through a forward-ref because it is constructed later in
/// `AetherNode.assembleNode` (after `swimHealthDetector`) than the `GenerationSnapshotSource`
/// handed to consensus. Until the tracker has been quorate at least once
/// (`phase() != COLD_BOOT`) the adapter defers to the delegate's KV-projected view, so
/// cold-start formation is driven by the proven QUIC-count path and then hands off to the
/// tracker once it carries a real view.
///
/// Living in `aether/node` (not `consensus`) keeps `integrations/consensus` free of any
/// `swim` dependency: consensus consumes the `MembershipView` interface; this adapter binds
/// it to the swim-side implementation.
public final class TrackerBackedGenerationSnapshotSource implements GenerationSnapshotSource {
    private final GenerationSnapshotSource delegate;
    private final AtomicReference<MembershipTracker> trackerRef;

    private TrackerBackedGenerationSnapshotSource(GenerationSnapshotSource delegate,
                                                  AtomicReference<MembershipTracker> trackerRef) {
        this.delegate = delegate;
        this.trackerRef = trackerRef;
    }

    public static TrackerBackedGenerationSnapshotSource trackerBacked(GenerationSnapshotSource delegate,
                                                                      AtomicReference<MembershipTracker> trackerRef) {
        return new TrackerBackedGenerationSnapshotSource(delegate, trackerRef);
    }

    @Override
    public Option<MembershipView> currentMembershipView() {
        return Option.option(trackerRef.get())
                     .filter(tracker -> tracker.phase() != MembershipPhase.COLD_BOOT)
                     .fold(delegate::currentMembershipView,
                           tracker -> Option.some(tracker));
    }

    @Override
    public long observedRabiaTerm() {
        return delegate.observedRabiaTerm();
    }

    @Override
    public long observedEpochRabiaTerm() {
        return delegate.observedEpochRabiaTerm();
    }
}
