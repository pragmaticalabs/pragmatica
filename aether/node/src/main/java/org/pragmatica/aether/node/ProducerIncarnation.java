// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Durable producer-process ordering, independent of UTC time and SWIM refutation counters.
/// The caller must retain this per-node directory or use a fresh node identity after its loss.
final class ProducerIncarnation {
    private static final String COUNTER = "producer-incarnation.bin";
    private static final int RECORD_BYTES = Long.BYTES * 2;

    enum Error implements Cause {
        BUSY,
        CORRUPT,
        EXHAUSTED;
        @Override
        public String message() {
            return "Producer incarnation allocation refused: " + name();
        }
    }

    private ProducerIncarnation() {}

    static Result<Long> next(Path directory) {
        return prepareDirectory(directory).flatMap(_ -> allocateLocked(directory));
    }

    private static Result<Unit> prepareDirectory(Path directory) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               Files.createDirectories(directory);
                               // Newly created path components must survive loss of power as well as the counter rename.
                               for (var ancestor = directory.toAbsolutePath(); ancestor != null; ancestor = ancestor.getParent()) {
                               try (var channel = FileChannel.open(ancestor, StandardOpenOption.READ)) {
                               channel.force(true);
                           }
                           }

                               return Unit.unit();
                           });
    }

    private static Result<Long> allocateLocked(Path directory) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               boolean freshIdentity = Files.notExists(directory.resolve("producer-incarnation.lock"));

                               try (var channel = FileChannel.open(directory.resolve("producer-incarnation.lock"),
                                                                   StandardOpenOption.CREATE,
                                                                   StandardOpenOption.WRITE)) {
                               var lock = channel.tryLock();

                               if (lock == null) {
                               return Error.BUSY.<Long> result();
                           }

                               try (lock) {
                               return read(directory.resolve(COUNTER),
                                           freshIdentity).flatMap(value -> advance(directory, value));
                           }
                           }
                           })
                     .flatMap(result -> result);
    }

    private static Result<Long> read(Path counter, boolean freshIdentity) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               if (Files.notExists(counter)) {
                               return freshIdentity
                                      ? Result.success(0L)
                                      : Error.CORRUPT.<Long> result();
                           }

                               if (Files.size(counter) != RECORD_BYTES) {
                               return Error.CORRUPT.<Long> result();
                           }

                               var record = ByteBuffer.wrap(Files.readAllBytes(counter));
                               long value = record.getLong();

                               return value > 0 && record.getLong() == ~value
                                      ? Result.success(value)
                                      : Error.CORRUPT.<Long> result();
                           })
                     .flatMap(result -> result);
    }

    private static Result<Long> advance(Path directory, long previous) {
        return previous == Long.MAX_VALUE
               ? Error.EXHAUSTED.result()
               : persist(directory, previous + 1);
    }

    private static Result<Long> persist(Path directory, long next) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               var temporary = directory.resolve("producer-incarnation.next");
                               var record = ByteBuffer.allocate(RECORD_BYTES)
                                                      .putLong(next)
                                                      .putLong(~next)
                                                      .flip();

                               try (var channel = FileChannel.open(temporary,
                                                                   StandardOpenOption.CREATE,
                                                                   StandardOpenOption.TRUNCATE_EXISTING,
                                                                   StandardOpenOption.WRITE)) {
                               while (record.hasRemaining()) {
                               channel.write(record);
                           }

                               channel.force(true);
                           }

                               Files.move(temporary,
                                          directory.resolve(COUNTER),
                                          StandardCopyOption.ATOMIC_MOVE,
                                          StandardCopyOption.REPLACE_EXISTING);
                               try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                               channel.force(true);
                           }

                               return next;
                           });
    }
}
