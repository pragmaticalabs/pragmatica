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
package org.pragmatica.consensus.net;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;


/// The node-local IDENTITY LEDGER (#2429): the [IdentityFacts] a core has acted on, kept on its own disk so that they
/// survive the loss of every holder's memory at once. The KV store is memory-only and backup is off by default, so a
/// fact kept only there disappears in exactly the event the ledger exists for.
///
/// **Write-ahead.** A fact is durable only after [#record] completes its promise, and every caller continues only then
/// (a retirement is announced and its refusal answered only after that). One write is three steps, each a separate
/// call on the [DurableWriter] so their order is pinned by a test:
///
/// 1. F1: the full new content goes to a same-directory temp file and is forced to the device;
/// 2. F2: the temp file replaces the ledger in one atomic rename;
/// 3. F3: the directory is forced, so the entry naming the file survives too.
///
/// The file is rewritten whole (one fact per NodeId ever bound, about 60 bytes each). A single writer thread
/// coalesces concurrent requests: a request is completed by the first write that started after it was queued.
/// [#record] never blocks its caller, so a stalled disk costs new bindings, never the traffic of peers already bound.
///
/// **Loss.** The content is read once, by [#open]. A file that is missing or does not parse is NOT an empty ledger: it
/// is [Origin#WIPED] (or [Origin#CREATED] when this node is being created now), and the caller decides what that
/// allows. Not covered, and said so: a local writer editing the file (same trust domain), and the loss of every
/// core's disk at once (nothing is left to protect: the cluster that forms is new).
public interface IdentityLedger {
    /// The three durable steps of one write. The production seam is [FileOps]; a test substitutes a recorder.
    interface DurableWriter {
        /// F1: write `content` to `file` and force it to the device.
        Result<Unit> writeForced(Path file, byte[] content);
        /// F2: replace `target` with `source` in one atomic rename.
        Result<Unit> moveAtomic(Path source, Path target);
        /// F3: force `directory`, so the entries it holds survive a crash.
        Result<Unit> forceDirectory(Path directory);

        static DurableWriter fileOps() {
            return new DurableWriter() {
                @Override
                public Result<Unit> writeForced(Path file, byte[] content) {
                    return FileOps.writeBytesForced(file, content);
                }

                @Override
                public Result<Unit> moveAtomic(Path source, Path target) {
                    return FileOps.moveAtomic(source, target).mapToUnit();
                }

                @Override
                public Result<Unit> forceDirectory(Path directory) {
                    return FileOps.forceDirectory(directory);
                }
            };
        }
    }

    /// How [#open] found the ledger.
    enum Origin {
        /// The file parsed: its facts are loaded.
        LOADED,
        /// No file, and this node is being created now: there is nothing to have lost.
        CREATED,
        /// No file (or one that does not parse) on a node that is not being created: facts may have been lost.
        WIPED
    }

    /// What [#open] found. `unwritable` is present when the ledger directory or the file cannot be written.
    record Loading(IdentityFacts facts, Origin origin, Option<Cause> unwritable) {}

    /// The failure of one ledger write, carrying what the file system reported.
    @SuppressWarnings("JBCT-CAUSE-04")
    record WriteFailed(Cause origin, String message) implements Cause, Cause.Wrapped {
        static final Fn1<WriteFailed, Cause> FACTORY = Causes.forOneValue("Identity ledger write failed",
                                                                          WriteFailed::new);
    }

    Path file();
    /// The offers a WIPED node has been handed and not yet acted on, kept beside the ledger (#2429, ID-1c).
    OfferJournal offers();
    /// Read the file and settle the state this node starts in. `createdNow` is the caller's statement that this node is
    /// being created now (its ledger directory did not exist), which turns a missing file into [Origin#CREATED]. A
    /// CREATED ledger is written empty at once, so a later restart finds a file and does not mistake itself for a
    /// wiped node; a WIPED ledger is NOT written, so the state survives a restart until a merge completes.
    Loading open(boolean createdNow);
    /// Every fact this ledger holds, durable or not yet.
    IdentityFacts facts();
    /// Union `delta` into the ledger and complete once the result is durable (F1-F3). Completes at once when `delta`
    /// adds nothing and the file already holds the ledger. Never blocks the caller.
    Promise<Unit> record(IdentityFacts delta);
    /// Whether the last write (or the load) left the file current.
    boolean durable();
    /// Register a listener told when durability is lost (the cause) and when it returns (empty). Called on the writer
    /// thread, at a transition only.
    Unit onDurabilityChange(Consumer<Option<Cause>> listener);

    static IdentityLedger identityLedger(Path file, DurableWriter writer) {
        return new FileLedger(file,
                              OfferJournal.offerJournal(file.resolveSibling(file.getFileName() + ".offers"),
                                                        writer),
                              writer,
                              new AtomicReference<>(IdentityFacts.identityFacts()),
                              new AtomicReference<>(IdentityFacts.identityFacts()),
                              new AtomicBoolean(),
                              new AtomicBoolean(true),
                              new AtomicBoolean(),
                              new ConcurrentLinkedQueue<>(),
                              new ConcurrentLinkedQueue<>());
    }

    record FileLedger(Path file,
                      OfferJournal offers,
                      DurableWriter writer,
                      AtomicReference<IdentityFacts> state,
                      AtomicReference<IdentityFacts> written,
                      AtomicBoolean fileCurrent,
                      AtomicBoolean durableFlag,
                      AtomicBoolean draining,
                      ConcurrentLinkedQueue<Promise<Unit>> queue,
                      ConcurrentLinkedQueue<Consumer<Option<Cause>>> listeners) implements IdentityLedger {
        @Override
        public Loading open(boolean createdNow) {
            var loaded = read();

            loaded.onPresent(this::adopt);

            return loaded.map(this::loadedLedger)
                         .or(() -> settleMissing(createdNow));
        }

        /// A ledger that parsed can still sit on a directory that is no longer writable (a volume remounted read-only before
        /// a restart): writability is probed on every boot, and the durability flag starts from that probe.
        private Loading loadedLedger(IdentityFacts facts) {
            var cause = causeOf(probeWritable());

            durableFlag.set(cause.isEmpty());

            return new Loading(facts, Origin.LOADED, cause);
        }

        private Loading settleMissing(boolean createdNow) {
            var cause = causeOf(createdNow
                                ? writeCurrent()
                                : probeWritable());

            durableFlag.set(createdNow && cause.isEmpty());

            return new Loading(IdentityFacts.identityFacts(),
                               createdNow
                               ? Origin.CREATED
                               : Origin.WIPED,
                               cause);
        }

        private static Option<Cause> causeOf(Result<Unit> outcome) {
            return outcome.fold(Option::<Cause> some, _ -> Option.<Cause> none());
        }

        /// A WIPED ledger must stay unwritten until a merge completes, so writability is probed with a throwaway file.
        private Result<Unit> probeWritable() {
            var directory = file.toAbsolutePath().getParent();
            var probe = directory.resolve(".identity-ledger-probe-" + System.nanoTime() + ".tmp");

            return FileOps.createDirectoriesDurable(directory)
                          .flatMap(_ -> writer.writeForced(probe, new byte[0]))
                          .onSuccess(_ -> removeTemp(probe))
                          .onFailure(_ -> removeTemp(probe));
        }

        private Option<IdentityFacts> read() {
            return FileOps.readString(file)
                          .flatMap(IdentityFacts::parse)
                          .option();
        }

        private Unit adopt(IdentityFacts facts) {
            state.set(facts);
            written.set(facts);
            fileCurrent.set(true);

            return Unit.unit();
        }

        @Override
        public IdentityFacts facts() {
            return state.get();
        }

        @Override
        public Promise<Unit> record(IdentityFacts delta) {
            state.updateAndGet(current -> current.merge(delta));
            if (fileCurrent.get() && written.get().covers(delta)) {
                return Promise.success(Unit.unit());
            }

            var pending = Promise.<Unit> promise();

            queue.add(pending);
            startDrain();

            return pending;
        }

        private Unit startDrain() {
            if (draining.compareAndSet(false, true)) {
                Thread.ofPlatform().daemon().name("identity-ledger-writer").start(this::drain);
            }

            return Unit.unit();
        }

        @Contract
        private void drain() {
            do {
                writeBatch();
                draining.set(false);
            } while (!queue.isEmpty() && draining.compareAndSet(false, true));
        }

        private Unit writeBatch() {
            var batch = new ArrayList<Promise<Unit>>();

            for (var next = queue.poll(); next != null; next = queue.poll()) {
                batch.add(next);
            }

            if (!batch.isEmpty()) {
                var result = writeCurrent().onSuccess(_ -> markDurable())
                                         .onFailure(this::markNotDurable)
                                         .mapError(WriteFailed.FACTORY);

                batch.forEach(pending -> pending.resolve(result));
            }

            return Unit.unit();
        }

        private Result<Unit> writeCurrent() {
            var snapshot = state.get();
            var content = snapshot.render().getBytes(StandardCharsets.UTF_8);
            var directory = file.toAbsolutePath().getParent();
            var temp = directory.resolve(".identity-ledger-" + System.nanoTime() + ".tmp");

            return FileOps.createDirectoriesDurable(directory)
                          .flatMap(_ -> writer.writeForced(temp, content))
                          .flatMap(_ -> writer.moveAtomic(temp, file))
                          .flatMap(_ -> writer.forceDirectory(directory))
                          .onSuccess(_ -> published(snapshot))
                          .onFailure(_ -> removeTemp(temp));
        }

        /// FER: a temp file left behind is garbage the next write never reads, so a failed delete is not worth a failure.
        @Contract
        private void removeTemp(Path temp) {
            FileOps.deleteIfExists(temp);
        }

        private Unit published(IdentityFacts snapshot) {
            written.set(snapshot);
            fileCurrent.set(true);

            return Unit.unit();
        }

        private Unit markDurable() {
            return durableFlag.getAndSet(true)
                   ? Unit.unit()
                   : notifyListeners(Option.none());
        }

        private Unit markNotDurable(Cause cause) {
            return durableFlag.getAndSet(false)
                   ? notifyListeners(Option.some(cause))
                   : Unit.unit();
        }

        private Unit notifyListeners(Option<Cause> change) {
            listeners.forEach(listener -> listener.accept(change));

            return Unit.unit();
        }

        @Override
        public boolean durable() {
            return durableFlag.get();
        }

        @Override
        public Unit onDurabilityChange(Consumer<Option<Cause>> listener) {
            listeners.add(listener);

            return Unit.unit();
        }
    }
}
