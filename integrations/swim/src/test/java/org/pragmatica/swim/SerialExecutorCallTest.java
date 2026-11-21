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
package org.pragmatica.swim;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class SerialExecutorCallTest {
    @Test
    void call_fromOutsideATask_returnsTheActionValue() {
        var executor = SerialExecutor.serialExecutor();

        assertThat(executor.call(() -> 7)).isEqualTo(Result.success(7));
    }

    @Test
    void call_fromInsideATask_failsWithTypedCauseInsteadOfRunningInline() {
        var executor = SerialExecutor.serialExecutor();
        var innerRan = new AtomicReference<>(false);
        var inner = new AtomicReference<Result<Integer>>();

        executor.execute(() -> inner.set(executor.call(() -> markRan(innerRan))));
        assertThat(innerRan.get()).isFalse();
        assertThat(inner.get().isFailure()).isTrue();
        inner.get().onFailure(cause -> assertThat(cause).isSameAs(SwimError.General.CALL_FROM_INSIDE_TASK));
    }

    private static int markRan(AtomicReference<Boolean> flag) {
        flag.set(true);

        return 1;
    }
}
