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
package org.pragmatica.consensus.leader.fsm;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.OwnerLifetime;

import static org.pragmatica.lang.utils.OwnerLifetime.ownerLifetime;


/// #3846: the episode of a state record that arms its timers in its factory, before the [org.pragmatica.statemachine.Fsm]
/// holds the entry (`Electing.fresh`, `ReElecting.fresh`). The record exists, and so does this slot, at arm time; the
/// entry does not. GUARANTEE: a completion of such a timer acts only in the FIRST entry of its record, and never when no
/// entry of the record is current. MECHANISM: the slot binds, inside the serial task that reads it, to the lifetime of
/// the entry that is current at that moment, if that entry is of the owner and nothing has bound the slot yet; a
/// completion of any other lifetime is dropped. [#exited()] spends an unbound slot, so a record that is left before
/// any timer ran and entered again (a saved record, input 3) can never adopt a timer of its first entry. The factory
/// path keeps its eager arming because the record's timers are scheduled before a lost CAS could be known (`onCasLost`
/// cancels them).
///
/// @param bound the lifetime of the first entry; empty until the first completion reads it
record FirstEntry(AtomicReference<OwnerLifetime> bound) {
    private static final OwnerLifetime SPENT = spent();

    static FirstEntry firstEntry() {
        return new FirstEntry(new AtomicReference<>());
    }

    /// True when `entry` is the first entry of the owner and still running. An absent entry is never live.
    boolean admits(Option<OwnerLifetime> entry) {
        return entry.map(this::boundTo)
                    .or(false);
    }

    /// The record is being left: an unbound slot can never bind to a later entry.
    @Contract
    void exited() {
        bound.compareAndSet(null, SPENT);
    }

    private boolean boundTo(OwnerLifetime lifetime) {
        bound.compareAndSet(null, lifetime);

        return bound.get() == lifetime && !lifetime.isEnded();
    }

    private static OwnerLifetime spent() {
        var lifetime = ownerLifetime();

        lifetime.end();

        return lifetime;
    }
}
