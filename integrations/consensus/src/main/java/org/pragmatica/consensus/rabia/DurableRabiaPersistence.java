package org.pragmatica.consensus.rabia;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.zip.CRC32C;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.Deserializer;


/// Local write-ahead boundary. No git, remote store, or network operation is on this path.
final class DurableRabiaPersistence<C extends Command> implements RabiaPersistence<C> {
    private static final int MAGIC = 0x52414231;
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 16;
    private static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;
    private static final int MAX_CHECKPOINT_BYTES = 512 * 1024 * 1024;
    private static final long CHECKPOINT_BYTES = 32L * 1024 * 1024;
    private static final long MAX_JOURNAL_BYTES = 256L * 1024 * 1024;
    private static final int CHECKPOINT_RECORDS = 4096;
    private static final int MAX_RETAINED_RECORDS = 65_536;

    private final Path directory;
    private final Path journalFile;
    private final Path checkpointFile;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final List<RabiaProtocolMessage> entries = new ArrayList<>();
    private Option<SavedState<C>> state = Option.none();
    private long sequence;
    private long journalBytes;
    private int appendedRecords;
    private Option<FileChannel> lockChannel = Option.none();
    private Option<org.pragmatica.lang.Cause> writeFailure = Option.none();
    private boolean closed;

    private Result<Unit> acquireLock() {
        return Result.lift(Causes::fromThrowable,
                           () -> FileChannel.open(directory.resolve("owner.lock"),
                                                  StandardOpenOption.CREATE,
                                                  StandardOpenOption.WRITE))
                     .onSuccess(channel -> lockChannel = Option.some(channel))
                     .flatMap(channel -> Result.lift(VotingJournalError.IN_USE,
                                                     () -> {
                                                         return channel.tryLock();
                                                     }))
                     .flatMap(lock -> Option.option(lock).toResult(VotingJournalError.IN_USE))
                     .mapToUnit();
    }

    @Override
    public synchronized Result<Unit> close() {
        closed = true;
        var channel = lockChannel;

        lockChannel = Option.none();

        return channel.fold(() -> Result.success(Unit.unit()),
                            value -> Result.lift(Causes::fromThrowable,
                                                 () -> {
                                                     value.close();

                                                     return Unit.unit();
                                                 }));
    }

    private Result<Unit> writable() {
        if (closed) {
            return VotingJournalError.CLOSED.result();
        }

        return writeFailure.fold(() -> Result.success(Unit.unit()),
                                 org.pragmatica.lang.Cause::result);
    }

    private DurableRabiaPersistence(Path directory, Serializer serializer, Deserializer deserializer) {
        this.directory = directory;
        this.journalFile = directory.resolve("voting.wal");
        this.checkpointFile = directory.resolve("checkpoint.bin");
        this.serializer = serializer.canonical();
        this.deserializer = deserializer;
    }

    static <C extends Command> Result<RabiaPersistence<C>> open(Path directory,
                                                                Serializer serializer,
                                                                Deserializer deserializer) {
        var persistence = new DurableRabiaPersistence<C>(directory, serializer, deserializer);

        return Result.lift(Causes::fromThrowable,
                           () -> Files.createDirectories(directory))
                     .flatMap(_ -> persistence.acquireLock())
                     .flatMap(_ -> persistence.readCheckpoint())
                     .flatMap(_ -> persistence.readJournal())
                     .onFailure(_ -> persistence.close())
                     .map(_ -> persistence);
    }

    @Override
    public synchronized Result<Unit> append(RabiaProtocolMessage message) {
        if (writable().isFailure()) {
            return writable();
        }

        if (!VotingJournal.supported(message)) {
            return VotingJournalError.CORRUPT.result();
        }

        var existing = VotingJournal.existing(entries, message);

        if (existing.isPresent()) {
            return existing.filter(value -> VotingJournal.sameValue(value, message))
                           .toResult(VotingJournalError.CONFLICT)
                           .mapToUnit();
        }

        if (entries.size() >= MAX_RETAINED_RECORDS || sequence == Long.MAX_VALUE) {
            return VotingJournalError.CAPACITY.result();
        }

        return encodeFrame(new VotingJournalRecord<>(sequence + 1, message),
                           MAX_RECORD_BYTES).filter(VotingJournalError.CAPACITY,
                                                    bytes -> journalBytes + bytes.length <= MAX_JOURNAL_BYTES)
                          .flatMap(bytes -> appendBytes(bytes).onSuccess(_ -> {
                                                                             sequence++;
                                                                             journalBytes += bytes.length;
                                                                             appendedRecords++;
                                                                             entries.add(message);
                                                                         }))
                          .onFailure(cause -> writeFailure = Option.some(cause));
    }

    @Override
    public synchronized Result<List<RabiaProtocolMessage>> loadJournal() {
        return Result.success(List.copyOf(entries));
    }

    @Override
    public synchronized boolean checkpointRequired() {
        return journalBytes >= CHECKPOINT_BYTES || appendedRecords >= CHECKPOINT_RECORDS;
    }

    @Override
    public synchronized Option<SavedState<C>> load() {
        return state;
    }

    @Override
    public synchronized Result<Option<SavedState<C>>> loadVerified() {
        return Result.success(state);
    }

    @Override
    public synchronized Result<Unit> save(StateMachine<C> machine, Phase frontier, Collection<Batch<C>> pending) {
        return saveCheckpoint(machine, frontier, pending, Option.none());
    }

    @Override
    public synchronized Result<Unit> save(StateMachine<C> machine,
                                          Phase frontier,
                                          Collection<Batch<C>> pending,
                                          VoterAuthority<C> authority) {
        return saveCheckpoint(machine, frontier, pending, Option.some(authority));
    }

    private Result<Unit> saveCheckpoint(StateMachine<C> machine,
                                        Phase frontier,
                                        Collection<Batch<C>> pending,
                                        Option<VoterAuthority<C>> authority) {
        if (writable().isFailure()) {
            return writable();
        }

        return machine.makeSnapshot()
                      .map(snapshot -> new SavedState<>(snapshot,
                                                        frontier,
                                                        List.copyOf(pending),
                                                        authority))
                      .flatMap(saved -> {
                                   var retained = VotingJournal.retain(entries, frontier, authority);
                                   var checkpoint = new VotingJournalCheckpoint<>(sequence, saved, retained);

                                   return encodeFrame(checkpoint, MAX_CHECKPOINT_BYTES).flatMap(bytes -> atomicReplace(checkpointFile,
                                                                                                                       bytes))
                                                     .onSuccess(_ -> {
                                                                    state = Option.some(saved);
                                                                    entries.clear();
                                                                    entries.addAll(retained);
                                                                })
                                                     // Crash between replacements leaves an older WAL covered by the checkpoint.
                                                     .flatMap(_ -> atomicReplace(journalFile, new byte[0]))
                                                     .onSuccess(_ -> {
                                   journalBytes = 0;
                                   appendedRecords = 0;
                               });
                               })
                      .onFailure(cause -> writeFailure = Option.some(cause));
    }

    private Result<Unit> appendBytes(byte[] bytes) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               var existed = Files.exists(journalFile);

                               try (var channel = FileChannel.open(journalFile,
                                                                   StandardOpenOption.CREATE,
                                                                   StandardOpenOption.WRITE,
                                                                   StandardOpenOption.APPEND)) {
                               var buffer = ByteBuffer.wrap(bytes);

                               while (buffer.hasRemaining()) {
                               channel.write(buffer);
                           }

                               channel.force(true);
                           }

                               return existed;
                           })
                     .flatMap(existed -> existed
                                         ? Result.success(Unit.unit())
                                         : forceDirectory());
    }

    private Result<Unit> atomicReplace(Path destination, byte[] bytes) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               var temporary = destination.resolveSibling(destination.getFileName() + ".tmp");

                               try (var channel = FileChannel.open(temporary,
                                                                   StandardOpenOption.CREATE,
                                                                   StandardOpenOption.WRITE,
                                                                   StandardOpenOption.TRUNCATE_EXISTING)) {
                               var buffer = ByteBuffer.wrap(bytes);

                               while (buffer.hasRemaining()) {
                               channel.write(buffer);
                           }

                               channel.force(true);
                           }

                               Files.move(temporary,
                                          destination,
                                          StandardCopyOption.ATOMIC_MOVE,
                                          StandardCopyOption.REPLACE_EXISTING);

                               return Unit.unit();
                           })
                     .flatMap(_ -> forceDirectory());
    }

    private Result<Unit> forceDirectory() {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                               channel.force(true);
                           }

                               return Unit.unit();
                           });
    }

    private Result<byte[]> encodeFrame(Object value, int maximum) {
        return Result.lift(Causes::fromThrowable,
                           () -> serializer.encode(value))
                     .filter(VotingJournalError.CAPACITY, payload -> payload.length <= maximum)
                     .map(payload -> {
                              var frame = ByteBuffer.allocate(HEADER_BYTES + payload.length);

                              frame.putInt(MAGIC)
                                   .putInt(VERSION)
                                   .putInt(payload.length)
                                   .putInt(checksum(payload))
                                   .put(payload);

                              return frame.array();
                          });
    }

    private static int checksum(byte[] payload) {
        var checksum = new CRC32C();

        checksum.update(payload, 0, payload.length);

        return (int) checksum.getValue();
    }

    private Result<List<Object>> decodeFrames(byte[] bytes, int maximum) {
        var buffer = ByteBuffer.wrap(bytes);
        var decoded = new ArrayList<Object>();

        while (buffer.hasRemaining()) {
            if (buffer.remaining() < HEADER_BYTES) {
                return VotingJournalError.TORN_TAIL.result();
            }

            var magic = buffer.getInt();
            var version = buffer.getInt();
            var length = buffer.getInt();
            var expectedChecksum = buffer.getInt();

            if (magic != MAGIC || version != VERSION || length < 0 || length > maximum) {
                return VotingJournalError.CORRUPT.result();
            }

            if (buffer.remaining() < length) {
                return VotingJournalError.TORN_TAIL.result();
            }

            var payload = new byte[length];

            buffer.get(payload);
            if (checksum(payload) != expectedChecksum) {
                return VotingJournalError.CORRUPT.result();
            }

            var result = Result.lift(VotingJournalError.CORRUPT, () -> deserializer.<Object> decode(payload));
            var accepted = result.fold(_ -> false, decoded::add);

            if (!accepted) {
                return VotingJournalError.CORRUPT.result();
            }
        }

        return Result.success(List.copyOf(decoded));
    }

    private Result<Unit> readCheckpoint() {
        if (!Files.exists(checkpointFile)) {
            return Result.success(Unit.unit());
        }

        return readBounded(checkpointFile, MAX_CHECKPOINT_BYTES + HEADER_BYTES).flatMap(bytes -> decodeFrames(bytes,
                                                                                                              MAX_CHECKPOINT_BYTES))
                          .flatMap(frames -> Result.lift(VotingJournalError.CORRUPT,
                                                         () -> installCheckpoint(frames)).flatMap(result -> result));
    }

    @SuppressWarnings("unchecked")
    private Result<Unit> installCheckpoint(List<Object> frames) {
        if (frames.size() != 1 || !(frames.getFirst() instanceof VotingJournalCheckpoint<?> checkpoint) || checkpoint.sequence() < 0 || checkpoint.state()
                                                                                                                                                  .lastCommittedPhase()
                                                                                                                                                  .value() < 0 || checkpoint.retained()
                                                                                                                                                                            .size() > MAX_RETAINED_RECORDS || checkpoint.retained()
                                                                                                                                                                                                                        .stream()
                                                                                                                                                                                                                        .anyMatch(message -> !VotingJournal.supported(message))) {
            return VotingJournalError.CORRUPT.result();
        }

        for (var message : checkpoint.retained()) {
            var previous = VotingJournal.existing(entries, message);

            if (previous.isPresent() || VotingJournal.phase(message).compareTo(checkpoint.state().lastCommittedPhase()) < 0) {
                return VotingJournalError.CORRUPT.result();
            }

            entries.add(message);
        }

        sequence = checkpoint.sequence();
        state = Option.some((SavedState<C>) checkpoint.state());

        return Result.success(Unit.unit());
    }

    private Result<Unit> readJournal() {
        if (!Files.exists(journalFile)) {
            return Result.success(Unit.unit());
        }

        return readBounded(journalFile, MAX_JOURNAL_BYTES).onSuccess(bytes -> journalBytes = bytes.length)
                          .flatMap(bytes -> decodeFrames(bytes, MAX_RECORD_BYTES))
                          .flatMap(this::installJournal);
    }

    private Result<Unit> installJournal(List<Object> frames) {
        long previous = -1;

        for (var frame : frames) {
            if (! (frame instanceof VotingJournalRecord<?> record) || record.sequence() <= 0 || !VotingJournal.supported(record.message()) || (previous >= 0 && record.sequence() != previous + 1)) {
                return VotingJournalError.CORRUPT.result();
            }

            previous = record.sequence();
            if (record.sequence() <= sequence) {
                continue;
            }

            if (record.sequence() != sequence + 1) {
                return VotingJournalError.GAP.result();
            }

            var existing = VotingJournal.existing(entries, record.message());

            if (existing.filter(value -> !VotingJournal.sameValue(value, record.message())).isPresent()) {
                return VotingJournalError.CONFLICT.result();
            }

            if (existing.isEmpty()) {
                if (entries.size() >= MAX_RETAINED_RECORDS) {
                    return VotingJournalError.CAPACITY.result();
                }

                entries.add(record.message());
            }

            sequence = record.sequence();
            appendedRecords++;
        }

        return Result.success(Unit.unit());
    }

    private Result<byte[]> readBounded(Path path, long maximum) {
        return Result.lift(Causes::fromThrowable,
                           () -> Files.size(path))
                     .filter(VotingJournalError.CAPACITY, size -> size <= maximum)
                     .flatMap(_ -> Result.lift(Causes::fromThrowable,
                                               () -> Files.readAllBytes(path)));
    }
}
