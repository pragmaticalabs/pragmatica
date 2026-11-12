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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Pins both arms of the Docker guard (#3089) with the require switch and the probe injected, so the outcome never depends
/// on whether the host running the test has Docker.
class DockerRequirementTest {
    private static final BooleanSupplier REQUIRED = () -> true;
    private static final BooleanSupplier UNSET = () -> false;

    @Test
    void dockerAvailable_requiredAndMissing_failsNamingTheProperty() {
        assertThatThrownBy(() -> DockerRequirement.dockerAvailable(REQUIRED, () -> false))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("-Daether.requireDocker=true")
            .hasMessageContaining("refusing to skip");
    }

    @Test
    void dockerAvailable_notRequiredAndMissing_reportsUnavailableSoCallerSkips() {
        assertThat(DockerRequirement.dockerAvailable(UNSET, () -> false)).isFalse();
    }

    @Test
    void dockerAvailable_requiredAndPresent_isTrue() {
        assertThat(DockerRequirement.dockerAvailable(REQUIRED, () -> true)).isTrue();
    }

    @Test
    void dockerAvailable_notRequiredAndPresent_isTrue() {
        assertThat(DockerRequirement.dockerAvailable(UNSET, () -> true)).isTrue();
    }

    @Test
    void dockerAvailable_probesExactlyOnce() {
        var probes = new AtomicInteger();

        DockerRequirement.dockerAvailable(UNSET, () -> probes.incrementAndGet() > 0);

        assertThat(probes).hasValue(1);
    }

    @Test
    void condition_dockerPresent_enablesTheClass() {
        assertThat(new DockerCondition(() -> true).evaluateExecutionCondition(null).isDisabled()).isFalse();
    }

    @Test
    void condition_dockerMissingAndNotRequired_disablesTheClass() {
        ConditionEvaluationResult result = new DockerCondition(() -> DockerRequirement.dockerAvailable(UNSET, () -> false))
            .evaluateExecutionCondition(null);

        assertThat(result.isDisabled()).isTrue();
    }

    @Test
    void condition_dockerMissingAndRequired_throwsInsteadOfDisabling() {
        var condition = new DockerCondition(() -> DockerRequirement.dockerAvailable(REQUIRED, () -> false));

        assertThatThrownBy(() -> condition.evaluateExecutionCondition(null)).isInstanceOf(AssertionError.class);
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void dockerAvailable_publicEntryReadsBothSystemProperties() {
        var previousRequire = System.getProperty(DockerRequirement.REQUIRE_PROPERTY);
        var previousHide = System.getProperty(DockerRequirement.HIDE_PROPERTY);

        try {
            System.setProperty(DockerRequirement.HIDE_PROPERTY, "true");
            System.clearProperty(DockerRequirement.REQUIRE_PROPERTY);
            assertThat(DockerRequirement.dockerAvailable()).isFalse();

            System.setProperty(DockerRequirement.REQUIRE_PROPERTY, "true");
            assertThatThrownBy(DockerRequirement::dockerAvailable).isInstanceOf(AssertionError.class)
                                                                  .hasMessageContaining("refusing to skip");
        } finally {
            restore(DockerRequirement.REQUIRE_PROPERTY, previousRequire);
            restore(DockerRequirement.HIDE_PROPERTY, previousHide);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
