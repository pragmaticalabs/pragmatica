package org.pragmatica.aether.deployment.schema;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.schema.FailureClassification.*;

class SchemaOrchestratorServiceTest {

    @Nested
    class FailureClassificationTests {

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
