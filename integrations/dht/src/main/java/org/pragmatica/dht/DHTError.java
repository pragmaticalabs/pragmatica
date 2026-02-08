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

package org.pragmatica.dht;

import org.pragmatica.lang.Cause;

/// Error causes for distributed DHT operations.
public sealed interface DHTError extends Cause {
    DHTError NO_AVAILABLE_NODES = new NoAvailableNodes();
    DHTError OPERATION_TIMEOUT = new OperationTimeout();
    DHTError MIGRATION_IN_PROGRESS = new MigrationInProgress();

    static DHTError quorumNotReached(int required, int achieved) {
        return new QuorumNotReached(required, achieved);
    }

    record QuorumNotReached(int required, int achieved) implements DHTError {
        @Override
        public String message() {
            return "Quorum not reached: required " + required + ", achieved " + achieved;
        }
    }

    record NoAvailableNodes() implements DHTError {
        @Override
        public String message() {
            return "No available nodes for key";
        }
    }

    record OperationTimeout() implements DHTError {
        @Override
        public String message() {
            return "DHT operation timed out";
        }
    }

    record MigrationInProgress() implements DHTError {
        @Override
        public String message() {
            return "Operation rejected: migration in progress";
        }
    }
}
