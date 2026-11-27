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
 *
 */
package org.pragmatica.testing;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import org.pragmatica.lang.Contract;


/// RED STUB (#3386): the defect itself, one bare park, returning early under a set flag and clearing nothing. Replaced by the real helper.
public final class Pause {
    private Pause() {}

    @Contract
    public static void pause(Duration span) {
        LockSupport.parkNanos(span.toNanos());
    }

    @Contract
    public static void pause(Duration span, LongSupplier clock, LongConsumer park) {
        park.accept(span.toNanos());
    }
}
