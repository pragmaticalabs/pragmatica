// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class ProducerIncarnationTest {
    @TempDir Path directory;

    @Test void restartAdvancesDurableCounterDespiteRegressedClockMetadata() {
        assertThat(ProducerIncarnation.next(directory).unwrap()).isEqualTo(1L);
        var counter = directory.resolve("producer-incarnation.bin");
        Result.lift(Causes::fromThrowable, () -> Files.setLastModifiedTime(counter, FileTime.fromMillis(1))).unwrap();
        assertThat(ProducerIncarnation.next(directory).unwrap()).isEqualTo(2L);
        Result.lift(Causes::fromThrowable, () -> Files.setLastModifiedTime(counter, FileTime.fromMillis(0))).unwrap();
        assertThat(ProducerIncarnation.next(directory).unwrap()).isEqualTo(3L);
    }

    @Test void missingPublishedCounterCannotRestartKnownIdentityAtZero() {
        assertThat(ProducerIncarnation.next(directory).unwrap()).isEqualTo(1L);
        Result.lift(Causes::fromThrowable, () -> Files.deleteIfExists(directory.resolve("producer-incarnation.bin"))).unwrap();
        assertThat(ProducerIncarnation.next(directory).isFailure()).isTrue();
    }

    @Test void malformedOrMissingChecksumRefusesStartupWithoutReset() {
        var counter = directory.resolve("producer-incarnation.bin");
        Result.lift(Causes::fromThrowable, () -> Files.write(counter, new byte[]{1, 2, 3})).unwrap();
        assertThat(ProducerIncarnation.next(directory).isFailure()).isTrue();
        assertThat(Result.lift(Causes::fromThrowable, () -> Files.readAllBytes(counter)).unwrap()).containsExactly(1, 2, 3);
        Result.lift(Causes::fromThrowable, () -> Files.write(counter, ByteBuffer.allocate(16).putLong(9).putLong(9).array())).unwrap();
        assertThat(ProducerIncarnation.next(directory).isFailure()).isTrue();
    }

    @Test void exhaustedCounterAndUnwritablePathFailClosed() {
        Result.lift(Causes::fromThrowable, () -> Files.write(directory.resolve("producer-incarnation.bin"),
            ByteBuffer.allocate(16).putLong(Long.MAX_VALUE).putLong(~Long.MAX_VALUE).array())).unwrap();
        assertThat(ProducerIncarnation.next(directory).isFailure()).isTrue();
        var regularFile = Result.lift(Causes::fromThrowable, () -> Files.write(directory.resolve("not-a-directory"), new byte[]{0})).unwrap();
        assertThat(ProducerIncarnation.next(regularFile).isFailure()).isTrue();
    }

    @Test void abandonedTemporaryWriteCannotReplacePublishedCounter() {
        assertThat(ProducerIncarnation.next(directory).unwrap()).isEqualTo(1L);
        Result.lift(Causes::fromThrowable, () -> Files.write(directory.resolve("producer-incarnation.next"), new byte[]{7})).unwrap();
        assertThat(ProducerIncarnation.next(directory).unwrap()).isEqualTo(2L);
    }
}
