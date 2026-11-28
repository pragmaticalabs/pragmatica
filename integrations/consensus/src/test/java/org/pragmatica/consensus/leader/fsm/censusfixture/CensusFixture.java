/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm.censusfixture;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.leader.fsm.LeaderElectionContext.EpisodeHandle;
import org.pragmatica.lang.Option;
import org.pragmatica.statemachine.Fsm;

/// Planted authority holders for the positive control of `LeaderAuthorityCensusTest` (#3846 r3 section 2.1): each member
/// below is one the census must report, scanned through the same entry point as the leader packages. The NEGATIVE members
/// (reported by nothing) sit beside them, so a census that reports everything does not pass as the control.
public final class CensusFixture {
    private CensusFixture() {}

    public enum FixtureState { ONE }

    /// PLANTED: a record component `AtomicReference<EpisodeHandle>` (CEN-1 on the component's field, CEN-2 on its accessor).
    public record HandleCell(AtomicReference<EpisodeHandle> cell) {}

    /// PLANTED: a component `AtomicReference<Fsm.Entered<FixtureState>>`: exercises another package and propagation, since
    /// `Fsm.Entered` is in A only through its public `lifetime` component.
    public record EnteredCell(AtomicReference<Fsm.Entered<FixtureState>> cell) {}

    /// PLANTED: a zero-argument method returning `EpisodeHandle` (CEN-2).
    public interface Minter {
        EpisodeHandle mint();
    }

    /// PLANTED: a method returning `Option<EpisodeHandle>`: exercises the generic return walk (CEN-2).
    public interface OptionalMinter {
        Option<EpisodeHandle> mint();
    }

    /// PLANTED: an anonymous class capturing an `EpisodeHandle`: exercises the synthetic `val$` field (CEN-1).
    public static Runnable capture(EpisodeHandle handle) {
        return new Runnable() {
            @Override
            public void run() {
                handle.hashCode();
            }
        };
    }

    /// NEGATIVE: a method that returns authority but takes it too is a pass-through, not a source (CEN-2 skips it).
    public interface Echo {
        EpisodeHandle echo(EpisodeHandle handle);
    }

    /// NEGATIVE: a record that mentions no authority.
    public record Plain(String name, Option<String> alias) {}
}
