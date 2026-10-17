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

/// One condition an operator needs to see (#1574).
///
/// `subject` names the thing the warning is about, such as a peer, a `stream[partition]` or `core`.
/// Together with `code` it forms the throttle key. `message` is the text already written to the log,
/// and it becomes the event summary.
public record OperatorWarning(OperatorWarningCode code, String subject, String message) {
    public static OperatorWarning operatorWarning(OperatorWarningCode code, String subject, String message) {
        return new OperatorWarning(code, subject, message);
    }
}
