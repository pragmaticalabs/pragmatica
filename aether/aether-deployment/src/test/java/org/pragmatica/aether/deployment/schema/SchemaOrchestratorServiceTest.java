// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.deployment.schema;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.schema.FailureClassification.*;

class SchemaOrchestratorServiceTest {

    @Nested
    class FailureClassificationTests {

        /// #1931: a configured repository that could not answer now ends resolution as `ArtifactUnavailable` (no fall-through to
        /// the next source on a transient failure). It must be TRANSIENT so the existing backoff retries it; unclassified it was
        /// permanently FAILED with a manual retry.
        @Test
        void classifyFailure_artifactUnavailable_isTransient() {
            var cause = new org.pragmatica.aether.slice.SliceLoadingFailure.Intermittent.ArtifactUnavailable(
                "org.example:app:1.0.0", java.util.List.of("repository #0 unavailable: connect timed out"));

            assertThat(SchemaOrchestratorServiceInstance.classifyFailure(cause)).isEqualTo(TRANSIENT);
        }

        @Test
        void classifyFailure_transient_forDatasourceUnreachable() {
            var cause = SchemaError.DatasourceUnreachable.datasourceUnreachable("mydb", "timeout");

            assertThat(SchemaOrchestratorServiceInstance.classifyFailure(cause)).isEqualTo(TRANSIENT);
        }

        @Test
        void classifyFailure_transient_forLockAcquisitionFailed() {
            var cause = SchemaError.LockAcquisitionFailed.lockAcquisitionFailed("mydb");

            assertThat(SchemaOrchestratorServiceInstance.classifyFailure(cause)).isEqualTo(TRANSIENT);
        }

        @Test
        void classifyFailure_permanent_forMigrationFailed() {
            var cause = SchemaError.MigrationFailed.migrationFailed("mydb", 3, "syntax error");

            assertThat(SchemaOrchestratorServiceInstance.classifyFailure(cause)).isEqualTo(PERMANENT);
        }

        @Test
        void classifyFailure_permanent_forChecksumMismatch() {
            var cause = SchemaError.ChecksumMismatch.checksumMismatch("mydb", 2, 100L, 200L);

            assertThat(SchemaOrchestratorServiceInstance.classifyFailure(cause)).isEqualTo(PERMANENT);
        }

        @Test
        void classifyFailure_unknown_forGenericCause() {
            var cause = Causes.cause("Something unexpected");

            assertThat(SchemaOrchestratorServiceInstance.classifyFailure(cause)).isEqualTo(UNKNOWN);
        }
    }

    @Nested
    class BackoffCalculationTests {

        @Test
        void calculateBackoff_firstAttempt_returnsBaseTimesThree() {
            assertThat(SchemaOrchestratorServiceInstance.calculateBackoff(1)).isEqualTo(15_000L);
        }

        @Test
        void calculateBackoff_secondAttempt_returnsBaseTimesNine() {
            assertThat(SchemaOrchestratorServiceInstance.calculateBackoff(2)).isEqualTo(45_000L);
        }

        @Test
        void calculateBackoff_zeroAttempt_returnsBase() {
            assertThat(SchemaOrchestratorServiceInstance.calculateBackoff(0)).isEqualTo(5_000L);
        }
    }
}
