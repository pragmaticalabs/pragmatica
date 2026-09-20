/*
 *  Copyright (c) 2023-2025 Sergiy Yevtushenko.
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
 *
 */

package org.pragmatica.lang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Functions.ThrowingRunnable;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #1311: `Result.lift` mapped EVERY Throwable through the exception mapper, so a StackOverflowError or an
/// OutOfMemoryError inside a lifted call became a plausible-looking failed Result and nothing else. Through
/// `Promise.lift*` that failed Result reached `resolve()` with no guard ever seeing it. Both overloads now
/// rethrow a VirtualMachineError; ordinary exceptions are still the mapper's (control below). The errors are
/// raised by the JVM itself — a real recursion and an unaddressable array — not constructed by hand.
@Timeout(30)
class ResultLiftVirtualMachineErrorTest {
    @Test
    void lift_supplier_rethrowsStackOverflowErrorInsteadOfMappingIt() {
        assertThatThrownBy(() -> Result.lift(Causes::fromThrowable, () -> recurseForever(0)))
            .isInstanceOf(StackOverflowError.class);
    }

    @Test
    void lift_runnable_rethrowsOutOfMemoryErrorInsteadOfMappingIt() {
        assertThatThrownBy(() -> Result.lift(Causes::fromThrowable, () -> unaddressableArray().hashCode()))
            .isInstanceOf(OutOfMemoryError.class);
    }

    /// Control: the mapper still owns ordinary exceptions, in both overloads.
    @Test
    void lift_ordinaryException_isStillMapped() {
        var fromSupplier = Result.lift(Causes::fromThrowable, () -> {
            throw new IllegalStateException("supplier-control");
        });
        var fromRunnable = Result.lift(Causes::fromThrowable, (ThrowingRunnable) () -> {
            throw new IllegalStateException("runnable-control");
        });

        assertThat(fromSupplier).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) fromSupplier).cause().message()).contains("supplier-control");
        assertThat(fromRunnable).isInstanceOf(Result.Failure.class);
        assertThat(((Result.Failure<?>) fromRunnable).cause().message()).contains("runnable-control");
    }

    private static int recurseForever(int depth) {
        return recurseForever(depth + 1) + 1;
    }

    private static long[] unaddressableArray() {
        return new long[Integer.MAX_VALUE];
    }
}
