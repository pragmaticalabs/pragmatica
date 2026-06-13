/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pragmatica.consensus.topology;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationSnapshotSourceTest {
    @Nested
    class Noop {
        @Test
        void currentMembershipView_returnsNone() {
            assertThat(GenerationSnapshotSource.noop().currentMembershipView().isEmpty()).isTrue();
        }

        @Test
        void observedRabiaTerm_returnsZero() {
            assertThat(GenerationSnapshotSource.noop().observedRabiaTerm()).isZero();
        }

        @Test
        void observedEpochRabiaTerm_returnsZero() {
            assertThat(GenerationSnapshotSource.noop().observedEpochRabiaTerm()).isZero();
        }
    }

    @Nested
    class DefaultObservedEpochRabiaTerm {
        @Test
        void defaultImplementation_mirrorsObservedRabiaTerm() {
            GenerationSnapshotSource source = new GenerationSnapshotSource() {
                @Override public Option<MembershipView> currentMembershipView() {
                    return Option.none();
                }

                @Override public long observedRabiaTerm() {
                    return 42L;
                }
            };

            assertThat(source.observedEpochRabiaTerm()).isEqualTo(42L);
        }

        @Test
        void overridden_canDivergeFromObservedRabiaTerm() {
            GenerationSnapshotSource source = new GenerationSnapshotSource() {
                @Override public Option<MembershipView> currentMembershipView() {
                    return Option.none();
                }

                @Override public long observedRabiaTerm() {
                    return 10L;
                }

                @Override public long observedEpochRabiaTerm() {
                    return 100L;
                }
            };

            assertThat(source.observedRabiaTerm()).isEqualTo(10L);
            assertThat(source.observedEpochRabiaTerm()).isEqualTo(100L);
        }
    }
}
