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

package org.pragmatica.consensus.net.quic;

import java.util.Arrays;

import io.netty.util.Version;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1727 — the QUIC lane depends on the quiche build netty bundles. netty 4.2.9 bundled quiche `5ea8d8e`, whose
/// packet-number length omitted RFC 9000 A.2's sign bit (cloudflare/quiche#2180, fixed by `094896852c`): after a run
/// of 128+ lost packets the peer mis-decodes every packet number, fails AEAD and drops each packet silently, so a
/// lane stalls with writes acknowledged and nothing delivered. netty 4.2.10+ bundles the fix; reproduced 6 of 240 on
/// 4.2.9 and 0 of 240 on 4.2.18 in-JVM on Linux. This pins the floor to 4.2.18, the version rc4 was verified on, so
/// a downgrade fails here rather than as a rare lane stall.
class QuicNativeLibraryFloorTest {
    private static final int[] FLOOR = {4, 2, 18};

    @Test
    void nativeQuicLibrary_isAtLeastTheVerifiedFloor() {
        var artifact = Version.identify().get("netty-codec-native-quic");

        assertThat(artifact).as("netty-codec-native-quic must be on the classpath with version metadata").isNotNull();
        assertThat(meetsFloor(artifact.artifactVersion()))
            .as("netty-codec-native-quic %s is below %s: quiche before 094896852c silently drops a lane's packets after a"
                + " large loss burst (cloudflare/quiche#2180, #1727)",
                artifact.artifactVersion(),
                Arrays.toString(FLOOR))
            .isTrue();
    }

    @Test
    void meetsFloor_rejectsTheLastAffectedVersion_andAcceptsTheFloor() {
        assertThat(meetsFloor("4.2.9.Final")).isFalse();
        assertThat(meetsFloor("4.2.17.Final")).isFalse();
        assertThat(meetsFloor("4.2.18.Final")).isTrue();
        assertThat(meetsFloor("4.3.0.Final")).isTrue();
    }

    static boolean meetsFloor(String artifactVersion) {
        var parts = artifactVersion.split("\\.");

        for (int i = 0; i < FLOOR.length; i++) {
            var actual = i < parts.length ? Integer.parseInt(parts[i].replaceAll("\\D.*", "")) : 0;

            if (actual != FLOOR[i]) {
                return actual > FLOOR[i];
            }
        }
        return true;
    }
}
