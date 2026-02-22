package org.pragmatica.aether.update;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class VersionRoutingTest {

    @Nested
    class FactoryValidation {
        @Test
        void versionRouting_validWeights_succeeds() {
            var result = VersionRouting.versionRouting(1, 3);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().newWeight()).isEqualTo(1);
            assertThat(result.unwrap().oldWeight()).isEqualTo(3);
        }

        @Test
        void versionRouting_zeroNewWeight_succeeds() {
            var result = VersionRouting.versionRouting(0, 1);

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void versionRouting_zeroOldWeight_succeeds() {
            var result = VersionRouting.versionRouting(1, 0);

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void versionRouting_bothZero_fails() {
            var result = VersionRouting.versionRouting(0, 0);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("positive"));
        }

        @Test
        void versionRouting_negativeNewWeight_fails() {
            var result = VersionRouting.versionRouting(-1, 3);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("non-negative"));
        }

        @Test
        void versionRouting_negativeOldWeight_fails() {
            var result = VersionRouting.versionRouting(1, -1);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("non-negative"));
        }
    }

    @Nested
    class StringParsing {
        @Test
        void versionRouting_validString_parsesCorrectly() {
            var result = VersionRouting.versionRouting("1:3");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().newWeight()).isEqualTo(1);
            assertThat(result.unwrap().oldWeight()).isEqualTo(3);
        }

        @Test
        void versionRouting_stringWithSpaces_parsesCorrectly() {
            var result = VersionRouting.versionRouting(" 1 : 3 ");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap().newWeight()).isEqualTo(1);
            assertThat(result.unwrap().oldWeight()).isEqualTo(3);
        }

        @Test
        void versionRouting_invalidFormat_fails() {
            var result = VersionRouting.versionRouting("invalid");

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("Invalid ratio format"));
        }

        @Test
        void versionRouting_tooManyParts_fails() {
            var result = VersionRouting.versionRouting("1:2:3");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void versionRouting_nonNumeric_fails() {
            var result = VersionRouting.versionRouting("a:b");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void versionRouting_parsedNegative_fails() {
            var result = VersionRouting.versionRouting("-1:3");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void versionRouting_parsedBothZero_fails() {
            var result = VersionRouting.versionRouting("0:0");

            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class RoutingFlags {
        @Test
        void isAllOld_zeroNewWeight_returnsTrue() {
            assertThat(VersionRouting.ALL_OLD.isAllOld()).isTrue();
        }

        @Test
        void isAllOld_nonZeroNewWeight_returnsFalse() {
            var routing = VersionRouting.versionRouting(1, 3).unwrap();

            assertThat(routing.isAllOld()).isFalse();
        }

        @Test
        void isAllNew_zeroOldWeight_returnsTrue() {
            assertThat(VersionRouting.ALL_NEW.isAllNew()).isTrue();
        }

        @Test
        void isAllNew_nonZeroOldWeight_returnsFalse() {
            var routing = VersionRouting.versionRouting(1, 3).unwrap();

            assertThat(routing.isAllNew()).isFalse();
        }

        @Test
        void isAllOld_allNew_areExclusive() {
            assertThat(VersionRouting.ALL_OLD.isAllNew()).isFalse();
            assertThat(VersionRouting.ALL_NEW.isAllOld()).isFalse();
        }
    }

    @Nested
    class WeightCalculations {
        @Test
        void totalWeight_sumOfBothWeights() {
            var routing = VersionRouting.versionRouting(1, 3).unwrap();

            assertThat(routing.totalWeight()).isEqualTo(4);
        }

        @Test
        void newVersionPercentage_oneToThree_is25Percent() {
            var routing = VersionRouting.versionRouting(1, 3).unwrap();

            assertThat(routing.newVersionPercentage()).isCloseTo(25.0, offset(0.01));
        }

        @Test
        void newVersionPercentage_oneToOne_is50Percent() {
            var routing = VersionRouting.versionRouting(1, 1).unwrap();

            assertThat(routing.newVersionPercentage()).isCloseTo(50.0, offset(0.01));
        }

        @Test
        void newVersionPercentage_threeToOne_is75Percent() {
            var routing = VersionRouting.versionRouting(3, 1).unwrap();

            assertThat(routing.newVersionPercentage()).isCloseTo(75.0, offset(0.01));
        }

        @Test
        void newVersionPercentage_allNew_is100Percent() {
            assertThat(VersionRouting.ALL_NEW.newVersionPercentage()).isCloseTo(100.0, offset(0.01));
        }

        @Test
        void newVersionPercentage_allOld_isZeroPercent() {
            assertThat(VersionRouting.ALL_OLD.newVersionPercentage()).isCloseTo(0.0, offset(0.01));
        }
    }

    @Nested
    class InstanceScaling {
        @Test
        void scaleToInstances_allOld_usesAllOldInstances() {
            var result = VersionRouting.ALL_OLD.scaleToInstances(3, 9);

            assertThat(result.isPresent()).isTrue();
            assertThat(result.unwrap()[0]).isZero();
            assertThat(result.unwrap()[1]).isEqualTo(9);
        }

        @Test
        void scaleToInstances_allNew_usesAllNewInstances() {
            var result = VersionRouting.ALL_NEW.scaleToInstances(3, 9);

            assertThat(result.isPresent()).isTrue();
            assertThat(result.unwrap()[0]).isEqualTo(3);
            assertThat(result.unwrap()[1]).isZero();
        }

        @Test
        void scaleToInstances_oneToThree_scalesCorrectly() {
            var routing = VersionRouting.versionRouting(1, 3).unwrap();

            var result = routing.scaleToInstances(3, 9);

            assertThat(result.isPresent()).isTrue();
            // Scale factor = min(3/1, 9/3) = min(3, 3) = 3
            assertThat(result.unwrap()[0]).isEqualTo(3);
            assertThat(result.unwrap()[1]).isEqualTo(9);
        }

        @Test
        void scaleToInstances_limitedByNewInstances() {
            var routing = VersionRouting.versionRouting(1, 3).unwrap();

            var result = routing.scaleToInstances(2, 9);

            assertThat(result.isPresent()).isTrue();
            // Scale factor = min(2/1, 9/3) = min(2, 3) = 2
            assertThat(result.unwrap()[0]).isEqualTo(2);
            assertThat(result.unwrap()[1]).isEqualTo(6);
        }

        @Test
        void scaleToInstances_limitedByOldInstances() {
            var routing = VersionRouting.versionRouting(1, 3).unwrap();

            var result = routing.scaleToInstances(5, 6);

            assertThat(result.isPresent()).isTrue();
            // Scale factor = min(5/1, 6/3) = min(5, 2) = 2
            assertThat(result.unwrap()[0]).isEqualTo(2);
            assertThat(result.unwrap()[1]).isEqualTo(6);
        }

        @Test
        void scaleToInstances_insufficientInstances_returnsNone() {
            var routing = VersionRouting.versionRouting(2, 3).unwrap();

            // Need at least 2 new and 3 old; only have 1 new
            var result = routing.scaleToInstances(1, 9);

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void scaleToInstances_insufficientOldInstances_returnsNone() {
            var routing = VersionRouting.versionRouting(1, 3).unwrap();

            // Need at least 3 old; only have 2
            var result = routing.scaleToInstances(5, 2);

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void scaleToInstances_exactFit_works() {
            var routing = VersionRouting.versionRouting(1, 1).unwrap();

            var result = routing.scaleToInstances(1, 1);

            assertThat(result.isPresent()).isTrue();
            assertThat(result.unwrap()[0]).isEqualTo(1);
            assertThat(result.unwrap()[1]).isEqualTo(1);
        }
    }

    @Nested
    class ToStringFormat {
        @Test
        void toString_standardRatio_formatsAsNewColonOld() {
            var routing = VersionRouting.versionRouting(1, 3).unwrap();

            assertThat(routing.toString()).isEqualTo("1:3");
        }

        @Test
        void toString_allNew_formatsCorrectly() {
            assertThat(VersionRouting.ALL_NEW.toString()).isEqualTo("1:0");
        }

        @Test
        void toString_allOld_formatsCorrectly() {
            assertThat(VersionRouting.ALL_OLD.toString()).isEqualTo("0:1");
        }
    }

    @Nested
    class StaticConstants {
        @Test
        void allOld_hasCorrectWeights() {
            assertThat(VersionRouting.ALL_OLD.newWeight()).isZero();
            assertThat(VersionRouting.ALL_OLD.oldWeight()).isEqualTo(1);
        }

        @Test
        void allNew_hasCorrectWeights() {
            assertThat(VersionRouting.ALL_NEW.newWeight()).isEqualTo(1);
            assertThat(VersionRouting.ALL_NEW.oldWeight()).isZero();
        }
    }
}
