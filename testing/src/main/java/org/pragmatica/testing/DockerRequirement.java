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

import org.testcontainers.DockerClientFactory;


/// The single Docker precondition of every live-database / testcontainers test (#3089).
///
/// Unset [#REQUIRE_PROPERTY]: a missing Docker daemon makes the caller SKIP (`assumeTrue(dockerAvailable())`), which
/// keeps a developer machine green (#466). `-Daether.requireDocker=true` (the switch CI passes, #2591): a missing daemon
/// is a FAILURE that names the property, because a class-level assumption aborts class setup and surefire then writes `tests="0" skipped="0"`,
/// which no gate counts. Every Docker guard in the repository goes through here; a build-time check
/// (`DockerGuardBypassTest`) fails when one does not.
///
/// [#HIDE_PROPERTY] pretends no daemon is reachable, so the require/skip decision can be exercised on a machine that has
/// Docker (the CI control step runs a live-database test with it set).
///
/// The testcontainers dependency is `provided`: every user of this class already has it at test scope.
public final class DockerRequirement {
    public static final String REQUIRE_PROPERTY = "aether.requireDocker";
    public static final String HIDE_PROPERTY = "aether.hideDocker";

    private DockerRequirement() {}

    /// Whether a Docker daemon is reachable; throws [AssertionError] when it is not and [#REQUIRE_PROPERTY] demands it.
    public static boolean dockerAvailable() {
        return dockerAvailable(DockerRequirement::isRequired, DockerRequirement::probe);
    }

    /// The decision with its inputs injected (the require switch and the availability probe).
    static boolean dockerAvailable(BooleanSupplier required, BooleanSupplier probe) {
        var available = probe.getAsBoolean();

        if (!available && required.getAsBoolean()) {
            throw new AssertionError("Docker is unavailable but -D" + REQUIRE_PROPERTY
                                    + "=true requires it: refusing to skip");
        }

        return available;
    }

    private static boolean isRequired() {
        return Boolean.getBoolean(REQUIRE_PROPERTY);
    }

    private static boolean probe() {
        if (Boolean.getBoolean(HIDE_PROPERTY)) {
            return false;
        }

        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
