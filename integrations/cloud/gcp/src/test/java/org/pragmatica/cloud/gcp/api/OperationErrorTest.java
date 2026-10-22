/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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
package org.pragmatica.cloud.gcp.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #1535 item 6: the text an operation's own error reports.
class OperationErrorTest {
    @Test
    void failure_joinsEveryDetailInOrder() {
        var operation = new Operation("op-1",
                                      "DONE",
                                      "",
                                      "insert",
                                      new Operation.OperationError(List.of(new Operation.Detail("QUOTA_EXCEEDED", "cpus"),
                                                                           new Operation.Detail("RESOURCE_NOT_FOUND", "image"))));

        assertThat(operation.failure().or("<none>")).isEqualTo("QUOTA_EXCEEDED: cpus; RESOURCE_NOT_FOUND: image");
    }

    @Test
    void failure_errorWithoutDetails_stillReportsAFailure() {
        var operation = new Operation("op-2", "DONE", "", "insert", new Operation.OperationError(List.of()));

        assertThat(operation.failure().or("<none>")).isEqualTo("operation reported an error without details");
    }

    @Test
    void failure_noError_reportsNothing() {
        assertThat(new Operation("op-3", "DONE", "", "insert").failure().isEmpty()).isTrue();
    }
}
