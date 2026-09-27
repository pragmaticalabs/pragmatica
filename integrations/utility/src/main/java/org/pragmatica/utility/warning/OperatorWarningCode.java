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
package org.pragmatica.utility.warning;

/// The single catalogue of operator-warning codes (#1574).
///
/// Operators filter the cluster event log on `code`, so the kebab string is part of the contract.
/// Because call sites name a constant rather than typing a string, a typo cannot silently start a
/// new code. Each constant also fixes its subsystem and level, so two sites cannot report the same
/// condition under different severities. `OperatorWarningCodeTest` refuses duplicate or non-kebab
/// codes.
///
/// To add a code, append a constant here. Never rename the string of a shipped code, because
/// operator filters depend on it.
public enum OperatorWarningCode {
    /// SWIM's #336 co-confirmation kill-gate is holding the death of a long-healthy peer. This node
    /// alone judged the peer FAULTY, with no transport corroboration.
    SWIM_KILL_GATE_HELD("swim-kill-gate-held", "membership", WarningLevel.WARNING),
    /// A worker has lost the core, and its core-absence fence is dissolving it locally.
    CORE_ABSENCE_FENCE("core-absence-fence", "worker-isolation", WarningLevel.CRITICAL),
    /// A replica failed to fsync an applied batch and is withholding its ack. The owner's durability
    /// barrier will not count this copy.
    REPLICA_FSYNC_FAILED("replica-fsync-failed", "stream-replication", WarningLevel.WARNING);
    private final String code;
    private final String subsystem;
    private final WarningLevel level;
    OperatorWarningCode(String code, String subsystem, WarningLevel level) {
        this.code = code;
        this.subsystem = subsystem;
        this.level = level;
    }
    /// The stable kebab-case identifier that operators filter on.
    public String code() {
        return code;
    }
    public String subsystem() {
        return subsystem;
    }
    public WarningLevel level() {
        return level;
    }
}
