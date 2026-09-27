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

import org.pragmatica.lang.Contract;


/// The narrow port through which a lower module reaches the cluster event log (#1574).
///
/// Every component receives its sink per instance, and nothing binds a static one. Ember runs several
/// nodes in one JVM, so a process-wide binding would attribute one node's warnings to another. The node
/// assembly binds each component to its own node's aggregator. [#logOnly] is the default for a
/// component that was never wired, in which case the log line written by
/// [OperatorWarnings#raise] is the whole report.
@FunctionalInterface
public interface OperatorWarningSink {
    @Contract
    void accept(OperatorWarning warning);

    /// Emits nothing. [OperatorWarnings#raise] has already logged the warning.
    static OperatorWarningSink logOnly() {
        return OperatorWarningSink::ignore;
    }

    @Contract
    private static void ignore(OperatorWarning warning) {
    // intentionally empty — the log line is the report when no event log is wired
    }
}
