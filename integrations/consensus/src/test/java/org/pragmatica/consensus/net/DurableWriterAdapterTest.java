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

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.pragmatica.consensus.net.IdentityLedger.DurableWriter;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/// The production adapter of the identity ledger's durable steps (#2429) is the one place the file system's guarantees are
/// bought: F1 must be the FORCED write, F2 the ATOMIC rename, F3 the directory force, and the ledger's directory must be created
/// with its entry forced. A plain write, a copy, or a plain `createDirectories` would pass every behavioural test on a healthy
/// disk and lose the ledger in a crash, so each call is pinned by name against `FileOps`, which is replaced for the duration.
class DurableWriterAdapterTest {
    private static final Path FILE = Path.of("/ledger/file");
    private static final Path SOURCE = Path.of("/ledger/.tmp");
    private static final Path DIRECTORY = Path.of("/ledger");
    private static final byte[] CONTENT = {1, 2, 3};

    @Test
    void fileOps_writesForced_movesAtomically_andForcesTheDirectory() {
        try (MockedStatic<FileOps> files = Mockito.mockStatic(FileOps.class)) {
            files.when(() -> FileOps.writeBytesForced(FILE, CONTENT)).thenReturn(Result.unitResult());
            files.when(() -> FileOps.moveAtomic(SOURCE, FILE)).thenReturn(Result.success(FILE));
            files.when(() -> FileOps.forceDirectory(DIRECTORY)).thenReturn(Result.unitResult());

            var writer = DurableWriter.fileOps();

            assertThat(writer.writeForced(FILE, CONTENT).isSuccess()).isTrue();
            assertThat(writer.moveAtomic(SOURCE, FILE).isSuccess()).isTrue();
            assertThat(writer.forceDirectory(DIRECTORY).isSuccess()).isTrue();

            files.verify(() -> FileOps.writeBytesForced(FILE, CONTENT));
            files.verify(() -> FileOps.moveAtomic(SOURCE, FILE));
            files.verify(() -> FileOps.forceDirectory(DIRECTORY));
            files.verifyNoMoreInteractions();
        }
    }

    @Test
    void fileOps_reportsTheFailureOfEachStep() {
        var failure = Causes.cause("boom");

        try (MockedStatic<FileOps> files = Mockito.mockStatic(FileOps.class)) {
            files.when(() -> FileOps.writeBytesForced(FILE, CONTENT)).thenReturn(failure.result());
            files.when(() -> FileOps.moveAtomic(SOURCE, FILE)).thenReturn(failure.result());
            files.when(() -> FileOps.forceDirectory(DIRECTORY)).thenReturn(failure.result());

            var writer = DurableWriter.fileOps();

            assertThat(writer.writeForced(FILE, CONTENT).isFailure()).isTrue();
            assertThat(writer.moveAtomic(SOURCE, FILE).isFailure()).isTrue();
            assertThat(writer.forceDirectory(DIRECTORY).isFailure()).isTrue();
        }
    }

    @Test
    void theLedger_createsItsDirectoryWithTheEntryForced() {
        try (MockedStatic<FileOps> files = Mockito.mockStatic(FileOps.class)) {
            files.when(() -> FileOps.readString(any(Path.class))).thenReturn(Causes.cause("missing").result());
            files.when(() -> FileOps.createDirectoriesDurable(any(Path.class))).thenReturn(Result.success(DIRECTORY));
            files.when(() -> FileOps.writeBytesForced(any(Path.class), any(byte[].class))).thenReturn(Result.unitResult());
            files.when(() -> FileOps.moveAtomic(any(Path.class), any(Path.class))).thenReturn(Result.success(FILE));
            files.when(() -> FileOps.forceDirectory(any(Path.class))).thenReturn(Result.unitResult());

            var ledger = IdentityLedger.identityLedger(FILE, DurableWriter.fileOps());

            ledger.open(true);

            files.verify(() -> FileOps.createDirectoriesDurable(DIRECTORY), Mockito.atLeastOnce());
        }
    }
}
