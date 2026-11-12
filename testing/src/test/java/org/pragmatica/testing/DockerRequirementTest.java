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

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Pins both arms of the Docker guard (#3089) with the environment and the probe injected, so the outcome never depends
/// on whether the host running the test has Docker.
class DockerRequirementTest {
    private static final Map<String, String> REQUIRED = Map.of(DockerRequirement.REQUIRE_VARIABLE, "1");
    private static final Map<String, String> UNSET = Map.of();

    @Test
    void dockerAvailable_requiredAndMissing_failsNamingTheVariable() {
        assertThatThrownBy(() -> DockerRequirement.dockerAvailable(REQUIRED::get, () -> false))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("CI_REQUIRE_DOCKER=1")
            .hasMessageContaining("refusing to skip");
    }

    @Test
    void dockerAvailable_requiredWithTrueSpelling_alsoFails() {
        var env = Map.of(DockerRequirement.REQUIRE_VARIABLE, "TRUE");

        assertThatThrownBy(() -> DockerRequirement.dockerAvailable(env::get, () -> false))
            .isInstanceOf(AssertionError.class);
    }

    @Test
    void dockerAvailable_notRequiredAndMissing_reportsUnavailableSoCallerSkips() {
        assertThat(DockerRequirement.dockerAvailable(UNSET::get, () -> false)).isFalse();
    }

    @Test
    void dockerAvailable_otherValueOfVariable_doesNotRequire() {
        var env = Map.of(DockerRequirement.REQUIRE_VARIABLE, "0");

        assertThat(DockerRequirement.dockerAvailable(env::get, () -> false)).isFalse();
    }

    @Test
    void dockerAvailable_requiredAndPresent_isTrue() {
        assertThat(DockerRequirement.dockerAvailable(REQUIRED::get, () -> true)).isTrue();
    }

    @Test
    void dockerAvailable_notRequiredAndPresent_isTrue() {
        assertThat(DockerRequirement.dockerAvailable(UNSET::get, () -> true)).isTrue();
    }

    @Test
    void dockerAvailable_probesExactlyOnce() {
        var probes = new AtomicInteger();

        DockerRequirement.dockerAvailable(UNSET::get, () -> probes.incrementAndGet() > 0);

        assertThat(probes).hasValue(1);
    }

    @Test
    void condition_dockerPresent_enablesTheClass() {
        assertThat(new DockerCondition(() -> true).evaluateExecutionCondition(null).isDisabled()).isFalse();
    }

    @Test
    void condition_dockerMissingAndNotRequired_disablesTheClass() {
        ConditionEvaluationResult result = new DockerCondition(() -> DockerRequirement.dockerAvailable(UNSET::get, () -> false))
            .evaluateExecutionCondition(null);

        assertThat(result.isDisabled()).isTrue();
    }

    @Test
    void condition_dockerMissingAndRequired_throwsInsteadOfDisabling() {
        var condition = new DockerCondition(() -> DockerRequirement.dockerAvailable(REQUIRED::get, () -> false));

        assertThatThrownBy(() -> condition.evaluateExecutionCondition(null)).isInstanceOf(AssertionError.class);
    }
}
