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

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// #1212 — [ParticipationMarker] held in a single fsynced file. See that interface for WHY this is
/// not part of `RabiaPersistence` and for the scopes of operator deletion it survives.
///
/// State is resolved EAGERLY, in [#create], rather than lazily on first [#resolve]. That is the whole
/// durability ordering the ticket turns on: the marker is written when the node is CONSTRUCTED, which
/// is strictly before it is started, which is strictly before its first `SyncRequest` reaches the
/// wire. A lazily-resolved marker would be read at adoption time — after the node had already begun
/// participating — and could not be trusted at the moment it was read.
record FileBackedParticipationMarker(Path markerFile,
                                     AtomicReference<ParticipationMarker.Participation> state)
        implements ParticipationMarker {
    /// Versioned so an unrecognised token from a future node reads as UNKNOWN (conservative) rather
    /// than being silently misparsed as one of the two known states.
    private static final String TOKEN_NEVER = "aether-participation-v1:never";
    private static final String TOKEN_PARTICIPATED = "aether-participation-v1:participated";

    static ParticipationMarker create(Path markerFile, boolean creationAsserted) {
        return new FileBackedParticipationMarker(markerFile,
                                                 new AtomicReference<>(initialState(markerFile, creationAsserted)));
    }

    @Override
    public Participation resolve() {
        return state.get();
    }

    /// Fail-closed, but only where failing closed BUYS something.
    ///
    /// The only state worth protecting is [Participation#NEVER_PARTICIPATED]: a node holding it can
    /// vote and then, if the write never lands, present itself as new on its next boot. There the
    /// write failure propagates and `RabiaEngine.activate()` refuses to activate.
    ///
    /// [Participation#PARTICIPATED] and [Participation#UNKNOWN] already deny the relaxation, so a
    /// failed write costs nothing and refusing to activate would only trade a non-existent safety
    /// gain for real unavailability — a node on a read-only filesystem could never join at all. The
    /// write is still ATTEMPTED for UNKNOWN, on the chance the medium became writable, but its
    /// failure is deliberately not propagated.
    @Override
    public Result<Unit> recordParticipation() {
        var current = state.get();

        if (current == Participation.PARTICIPATED) {
            return Result.success(Unit.unit());
        }

        var written = write(markerFile, TOKEN_PARTICIPATED).onSuccess(_ -> state.set(Participation.PARTICIPATED));

        return current == Participation.UNKNOWN
               ? Result.success(Unit.unit())
               : written;
    }

    /// An existing marker ALWAYS wins; `creationAsserted` is consulted only when none exists. That is
    /// what makes a stale creation assertion in a config file harmless across restarts.
    private static Participation initialState(Path markerFile, boolean creationAsserted) {
        return read(markerFile).or(() -> writeInitial(markerFile, creationAsserted));
    }

    /// Absent marker. With a creation assertion this node is being created now, so record newness
    /// BEFORE it participates in anything. Without one, absence means WIPED — record participation
    /// immediately, so the node can never LATER be mistaken for new, and report the conservative
    /// answer whether or not that write lands.
    private static Participation writeInitial(Path markerFile, boolean creationAsserted) {
        if (!creationAsserted) {
            write(markerFile, TOKEN_PARTICIPATED);

            return Participation.PARTICIPATED;
        }

        return write(markerFile, TOKEN_NEVER).isSuccess()
               ? Participation.NEVER_PARTICIPATED
               : Participation.UNKNOWN;
    }

    /// `Option.none()` means "no marker on disk" — distinct from a marker that is present but
    /// unreadable or holds an unrecognised token, which resolves to UNKNOWN. Both are conservative;
    /// only the first lets `creationAsserted` speak.
    private static Option<Participation> read(Path markerFile) {
        if (!Files.isRegularFile(markerFile)) {
            return Option.none();
        }

        return Result.lift(Causes::fromThrowable, () -> Files.readString(markerFile, StandardCharsets.UTF_8))
                     .map(FileBackedParticipationMarker::classify)
                     .fold(_ -> Option.some(Participation.UNKNOWN), Option::some);
    }

    private static Participation classify(String content) {
        var token = content.strip();

        if (TOKEN_NEVER.equals(token)) {
            return Participation.NEVER_PARTICIPATED;
        }

        if (TOKEN_PARTICIPATED.equals(token)) {
            return Participation.PARTICIPATED;
        }

        return Participation.UNKNOWN;
    }

    /// Write-temp, fsync, atomic-rename, expressed without `throw` or a `throws` clause so the
    /// failure is a value (JBCT-EX-01). A plain `Files.writeString` would leave the marker vulnerable
    /// to exactly the crash it exists to survive: a torn or unflushed marker after a power loss reads
    /// as an unrecognised token, and while that IS conservative, it would turn every crashed new node
    /// into an amnesiac one and reintroduce the wedge this ticket removes.
    ///
    /// The `finally` cleans the temp file on the failure path and is a harmless no-op on the success
    /// path, where `ATOMIC_MOVE` has already consumed it.
    private static Result<Unit> write(Path markerFile, String token) {
        return Option.option(markerFile.getParent())
                     .toResult(Causes.cause("Participation marker path has no parent directory: " + markerFile))
                     .flatMap(parent -> writeThroughTempFile(parent, markerFile, token));
    }

    private static Result<Unit> writeThroughTempFile(Path parent, Path markerFile, String token) {
        return Result.lift(Causes::fromThrowable, () -> {
            Files.createDirectories(parent);

            var temp = Files.createTempFile(parent, ".participation-", ".tmp");

            try {
                Files.writeString(temp, token, StandardCharsets.UTF_8);

                try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }

                Files.move(temp, markerFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }

            return Unit.unit();
        });
    }
}
