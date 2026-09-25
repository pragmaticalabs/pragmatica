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
package org.pragmatica.consensus.rabia;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.Codec;


/// Persistence interface for Rabia consensus state.
public interface RabiaPersistence<C extends Command> {
    /// Persist an immutable proposal, ballot, or Decision before it becomes externally visible.
    default Result<Unit> append(RabiaProtocolMessage message) {
        return VotingJournalError.UNSUPPORTED.result();
    }

    default Result<List<RabiaProtocolMessage>> loadJournal() {
        return Result.success(List.of());
    }

    default boolean checkpointRequired() {
        return false;
    }

    default Result<Unit> close() {
        return Result.success(Unit.unit());
    }

    default Result<Unit> saveSnapshot(SavedState<C> state) {
        return VotingJournalError.UNSUPPORTED.result();
    }

    default Option<org.pragmatica.lang.Cause> lastBackupFailure() {
        return Option.none();
    }

    static <C extends Command> RabiaPersistence<C> withBackup(RabiaPersistence<C> durable, RabiaPersistence<C> backup) {
        return new BackupRabiaPersistence<>(durable, backup);
    }

    static <C extends Command> Result<RabiaPersistence<C>> durable(Path directory,
                                                                   org.pragmatica.serialization.Serializer serializer,
                                                                   org.pragmatica.serialization.Deserializer deserializer) {
        return DurableRabiaPersistence.open(directory, serializer, deserializer);
    }

    /// Save the current state.
    Result<Unit> save(StateMachine<C> stateMachine, Phase lastCommittedPhase, Collection<Batch<C>> pendingBatches);

    /// Atomically saves application state with voting authority. Unsupported adapters fail
    /// closed once authority changes; retaining only application bytes would reopen old epochs.
    default Result<Unit> save(StateMachine<C> stateMachine,
                              Phase nextSlot,
                              Collection<Batch<C>> pending,
                              VoterAuthority<C> authority) {
        return authority.configuration()
                        .epoch() == 0 && authority.handoff()
                                                  .isEmpty()
               ? save(stateMachine, nextSlot, pending)
               : ReconfigurationError.AUTHORITY_PERSISTENCE_UNSUPPORTED.result();
    }

    default Result<Option<SavedState<C>>> loadVerified() {
        return Result.success(load());
    }

    /// Load the persisted state.
    Option<SavedState<C>> load();

    /// Create a git-backed persistence implementation with default timeout.
    static <C extends Command> RabiaPersistence<C> gitBacked(Path backupDir,
                                                             Option<String> remote,
                                                             Function<byte[], Result<String>> snapshotToToml,
                                                             Function<String, Result<byte[]>> tomlToSnapshot) {
        return new GitBackedPersistence<>(backupDir, remote, snapshotToToml, tomlToSnapshot);
    }

    /// Create a git-backed persistence implementation with configurable timeout.
    static <C extends Command> RabiaPersistence<C> gitBacked(Path backupDir,
                                                             Option<String> remote,
                                                             Function<byte[], Result<String>> snapshotToToml,
                                                             Function<String, Result<byte[]>> tomlToSnapshot,
                                                             TimeSpan gitTimeout) {
        return new GitBackedPersistence<>(backupDir, remote, snapshotToToml, tomlToSnapshot, gitTimeout);
    }

    /// Create an in-memory persistence implementation (for testing or single-session use).
    static <C extends Command> RabiaPersistence<C> inMemory() {
        record inMemory <C extends Command>(AtomicReference<Option<SavedState<C>>> state,
                                            java.util.List<RabiaProtocolMessage> journal) implements RabiaPersistence<C> {
            @Override
            public synchronized Result<Unit> append(RabiaProtocolMessage message) {
                var existing = VotingJournal.existing(journal, message);

                if (existing.filter(value -> !VotingJournal.sameValue(value, message)).isPresent()) {
                    return VotingJournalError.CONFLICT.result();
                }

                if (existing.isEmpty()) {
                    journal.add(message);
                }

                return Result.success(Unit.unit());
            }

            @Override
            public synchronized Result<List<RabiaProtocolMessage>> loadJournal() {
                return Result.success(List.copyOf(journal));
            }

            @Override
            public synchronized boolean checkpointRequired() {
                return journal.size() >= 4096;
            }

            private synchronized void install(SavedState<C> saved) {
                var retained = VotingJournal.retain(journal, saved.lastCommittedPhase(), saved.authority());

                journal.clear();
                journal.addAll(retained);
                state.set(Option.some(saved));
            }

            @Override
            public Result<Unit> save(StateMachine<C> stateMachine,
                                     Phase lastCommittedPhase,
                                     Collection<Batch<C>> pendingBatches) {
                return stateMachine.makeSnapshot()
                                   .map(snapshot -> SavedState.savedState(snapshot,
                                                                          lastCommittedPhase,
                                                                          List.copyOf(pendingBatches)))
                                   .onSuccess(this::install)
                                   .onFailure(_ -> state.set(Option.none()))
                                   .map(_ -> Unit.unit());
            }

            @Override
            public Result<Unit> save(StateMachine<C> machine,
                                     Phase nextSlot,
                                     Collection<Batch<C>> pending,
                                     VoterAuthority<C> authority) {
                return machine.makeSnapshot()
                              .map(snapshot -> new SavedState<>(snapshot,
                                                                nextSlot,
                                                                List.copyOf(pending),
                                                                Option.some(authority)))
                              .onSuccess(this::install)
                              .mapToUnit();
            }

            @Override
            public Option<SavedState<C>> load() {
                return state().get();
            }
        }

        return new inMemory <>(new AtomicReference<>(Option.none()), new java.util.ArrayList<>());
    }

    /// Saved consensus state.
    @Codec
    record SavedState<C extends Command>(byte[] snapshot,
                                         Phase lastCommittedPhase,
                                         List<Batch<C>> pendingBatches,
                                         Option<VoterAuthority<C>> authority) {
        public SavedState {
            snapshot = snapshot.clone();
            pendingBatches = List.copyOf(pendingBatches);
        }

        public SavedState(byte[] snapshot, Phase lastCommittedPhase, List<Batch<C>> pendingBatches) {
            this(snapshot, lastCommittedPhase, pendingBatches, Option.none());
        }

        public SavedState(byte[] snapshot, Phase lastCommittedPhase, Collection<Batch<C>> pendingBatches) {
            this(snapshot, lastCommittedPhase, List.copyOf(pendingBatches));
        }

        public static <C extends Command> SavedState<C> savedState(byte[] snapshot,
                                                                   Phase lastCommittedPhase,
                                                                   Collection<Batch<C>> pendingBatches) {
            return new SavedState<>(snapshot, lastCommittedPhase, pendingBatches);
        }

        public static <C extends Command> SavedState<C> empty() {
            return new SavedState<>(new byte[0], Phase.ZERO, List.of());
        }

        @Override
        public boolean equals(Object o) {
            if (! (o instanceof SavedState<?> other)) {
                return false;
            }

            return Arrays.equals(snapshot, other.snapshot())
                   && lastCommittedPhase.equals(other.lastCommittedPhase())
                   && pendingBatches.equals(other.pendingBatches())
                   && authority.equals(other.authority());
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(snapshot()), lastCommittedPhase(), pendingBatches(), authority());
        }
    }
}
