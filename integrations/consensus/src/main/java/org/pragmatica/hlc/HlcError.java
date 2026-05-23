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

package org.pragmatica.hlc;

import org.pragmatica.lang.Cause;

/// Error causes for HLC operations.
public sealed interface HlcError extends Cause {
    record ClockDriftExceeded(long remoteMicros, long localMicros, long maxDrift) implements HlcError {
        @Override
        public String message() {
            return "Clock drift exceeded: remote=" + remoteMicros + " local=" + localMicros
                   + " drift=" + (remoteMicros - localMicros) + " max=" + maxDrift;
        }
    }
}
