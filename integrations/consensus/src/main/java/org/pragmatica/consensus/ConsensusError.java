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

package org.pragmatica.consensus;

import org.pragmatica.lang.Cause;

/// Error types for consensus operations.
public sealed interface ConsensusError extends Cause {
    record CommandBatchIsEmpty() implements ConsensusError {
        @Override
        public String message() {
            return "Command batch is empty";
        }
    }

    record NodeInactive(NodeId nodeId) implements ConsensusError {
        @Override
        public String message() {
            return "Node " + nodeId.id() + " is inactive";
        }
    }

    record NodeIsObserver(NodeId nodeId) implements ConsensusError {
        @Override
        public String message() {
            return "Node " + nodeId.id() + " is in observer mode";
        }
    }

    record SnapshotFailed(String reason) implements ConsensusError {
        @Override
        public String message() {
            return "Snapshot failed: " + reason;
        }
    }

    record RestoreFailed(String reason) implements ConsensusError {
        @Override
        public String message() {
            return "Restore failed: " + reason;
        }
    }

    record BackpressureExceeded(int pending, int limit) implements ConsensusError {
        @Override
        public String message() {
            return "Backpressure exceeded: " + pending + " pending batches (limit: " + limit + ")";
        }
    }

    /// Returned when [org.pragmatica.consensus.rabia.RabiaEngine#apply] is invoked while
    /// the engine is in `Paused` state (quorum currently unavailable). The engine retains
    /// all in-memory state and will resume automatically once quorum returns; callers may
    /// retry the submission then.
    record QuorumPaused(NodeId nodeId) implements ConsensusError {
        @Override
        public String message() {
            return "Node " + nodeId.id() + " is paused: quorum unavailable";
        }
    }
}
