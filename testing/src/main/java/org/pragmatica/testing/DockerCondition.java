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

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;


/// Class-level Docker precondition behind [RequiresDocker]: enabled when a daemon is reachable, otherwise disabled
/// (skip) - or, under `-Daether.requireDocker=true`, an error that fails the class (see [DockerRequirement]). It replaces
/// the testcontainers extension's own skip switch, which skips regardless of the property.
public final class DockerCondition implements ExecutionCondition {
    private final BooleanSupplier dockerAvailable;

    public DockerCondition() {
        this(DockerRequirement::dockerAvailable);
    }

    DockerCondition(BooleanSupplier dockerAvailable) {
        this.dockerAvailable = dockerAvailable;
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return dockerAvailable.getAsBoolean()
               ? ConditionEvaluationResult.enabled("Docker is available")
               : ConditionEvaluationResult.disabled("Docker is not available");
    }
}
