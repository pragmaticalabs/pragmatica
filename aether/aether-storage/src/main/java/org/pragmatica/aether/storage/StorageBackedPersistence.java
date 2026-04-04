/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
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
package org.pragmatica.aether.storage;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.rabia.Batch;
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.consensus.rabia.RabiaPersistence;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.ContentStore;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;

import static org.pragmatica.consensus.rabia.RabiaPersistence.SavedState.savedState;


/// RabiaPersistence implementation backed by ContentStore.
/// Stores consensus state snapshots as content-addressed blocks.
/// Each snapshot is stored as: [8 bytes phase (long BE)] + [snapshot bytes].
/// Faster than Git-backed persistence, no external tooling needed.
public final class StorageBackedPersistence<C extends Command> implements RabiaPersistence<C> {
    private static final int PHASE_HEADER_SIZE = Long.BYTES;

    private final ContentStore contentStore;
    private final String snapshotName;

    private StorageBackedPersistence(ContentStore contentStore, String snapshotName) {
        this.contentStore = contentStore;
        this.snapshotName = snapshotName;
    }

    public static <C extends Command> StorageBackedPersistence<C> storageBackedPersistence(ContentStore contentStore,
                                                                                           String snapshotName) {
        return new StorageBackedPersistence<>(contentStore, snapshotName);
    }

    public static <C extends Command> StorageBackedPersistence<C> storageBackedPersistence(ContentStore contentStore) {
        return storageBackedPersistence(contentStore, "rabia/snapshot/latest");
    }

    @Override public Result<Unit> save(StateMachine<C> stateMachine,
                                       Phase lastCommittedPhase,
                                       Collection<Batch<C>> pendingBatches) {
        return stateMachine.makeSnapshot().map(snapshot -> encodeSnapshot(snapshot, lastCommittedPhase))
                                        .flatMap(this::storeEncoded);
    }

    @Override public Option<SavedState<C>> load() {
        return contentStore.get(snapshotName).await()
                               .fold(_ -> Option.none(),
                                     opt -> opt.flatMap(this::decodeState));
    }

    private Option<SavedState<C>> decodeState(byte[] encoded) {
        return decodeSnapshot(encoded);
    }

    private Result<Unit> storeEncoded(byte[] encoded) {
        return contentStore.put(snapshotName, encoded).await()
                               .mapToUnit();
    }

    static byte[] encodeSnapshot(byte[] snapshot, Phase phase) {
        var buffer = ByteBuffer.allocate(PHASE_HEADER_SIZE + snapshot.length);
        buffer.putLong(phase.value());
        buffer.put(snapshot);
        return buffer.array();
    }

    static <C extends Command> Option<SavedState<C>> decodeSnapshot(byte[] encoded) {
        if (encoded.length <PHASE_HEADER_SIZE) {return Option.none();}
        var buffer = ByteBuffer.wrap(encoded);
        var phaseValue = buffer.getLong();
        var snapshot = new byte[encoded.length - PHASE_HEADER_SIZE];
        buffer.get(snapshot);
        return Option.some(savedState(snapshot, Phase.phase(phaseValue), List.of()));
    }
}
