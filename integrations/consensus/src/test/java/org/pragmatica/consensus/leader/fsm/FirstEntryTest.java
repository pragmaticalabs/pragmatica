/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.utils.OwnerLifetime.ownerLifetime;


/// #3846 round 1 (g01/n5): the guard `!lifetime.isEnded()` of [FirstEntry#admits] closes a window that no deterministic
/// test of the leader FSM reaches. The serial executor runs one task at a time and runs it to completion
/// (`SerialExecutor` class doc), every leader dispatch is confined to it (`LeaderElectionContext#dispatchConfined`), and
/// [FirstEntry#admits] runs inside one such task: no transition can end the entry between the read of
/// `Fsm#entered` and the check. The guard is therefore pinned here, directly, on the one value it reads: a lifetime
/// the slot is bound to and that has ended is not admitted.
class FirstEntryTest {
    @Test
    void admits_aBoundEntryThatHasEnded_isRefused() {
        var first = FirstEntry.firstEntry();
        var entry = ownerLifetime();

        assertThat(first.admits(Option.some(entry))).as("instrument: the first read binds the slot and admits a running entry")
                  .isTrue();
        entry.end();
        assertThat(first.admits(Option.some(entry))).as("a bound entry that ended was admitted").isFalse();
    }

    @Test
    void admits_anEntryThatIsNotTheBoundOne_isRefused() {
        var first = FirstEntry.firstEntry();
        var bound = ownerLifetime();

        first.admits(Option.some(bound));

        assertThat(first.admits(Option.some(ownerLifetime()))).as("a running entry other than the bound one was admitted")
                  .isFalse();
    }

    @Test
    void admits_noEntry_isRefusedAndBindsNothing() {
        var first = FirstEntry.firstEntry();

        assertThat(first.admits(Option.none())).as("an absent entry was admitted").isFalse();
        assertThat(first.admits(Option.some(ownerLifetime()))).as("the absent read bound the slot to nothing, so the first entry still binds")
                  .isTrue();
    }

    @Test
    void exited_beforeAnyEntryBound_spendsTheSlot() {
        var first = FirstEntry.firstEntry();

        first.exited();

        assertThat(first.admits(Option.some(ownerLifetime()))).as("a spent slot bound to a later entry").isFalse();
    }
}
