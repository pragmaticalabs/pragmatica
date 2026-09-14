// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemdUnitTemplateTest {

    @Nested
    class DefaultGeneration {
        @Test
        void generateDefault_runsAsTheUserTheCloudInitLaunchAlreadyUsed() {
            var unit = SystemdUnitTemplate.generateDefault();

            assertTrue(unit.contains("User=root"), "Should have User=root");
            assertTrue(unit.contains("Group=root"), "Should have Group=root");
        }

        @Test
        void generateDefault_launchesViaTheLauncherScript_andReadsTheEnvFile() {
            var unit = SystemdUnitTemplate.generateDefault();

            assertTrue(unit.contains("ExecStart=/opt/aether/run-node.sh"), "Should launch the node launcher");
            assertTrue(unit.contains("EnvironmentFile=-/etc/aether/node.env"),
                       "Should read the node env file, tolerating its absence");
        }

        @Test
        void generateDefault_containsSystemdDirectives() {
            var unit = SystemdUnitTemplate.generateDefault();

            assertTrue(unit.contains("[Unit]"), "Should have [Unit] section");
            assertTrue(unit.contains("[Service]"), "Should have [Service] section");
            assertTrue(unit.contains("[Install]"), "Should have [Install] section");
            assertTrue(unit.contains("After=network-online.target"), "Should wait for network");
            assertTrue(unit.contains("WantedBy=multi-user.target"), "Should be multi-user target");
        }
    }

    /// #1021 — `Restart=no` is the load-bearing line of this unit and the one a future reader is most
    /// likely to "fix". These assertions exist to refuse that change, so they check for the absence of
    /// every restarting policy as well as the presence of the right one: asserting only
    /// `contains("Restart=no")` would still pass on a unit that ALSO carried `Restart=on-failure`, and
    /// systemd takes the last directive.
    ///
    /// The reason is not a preference. `aether/docs/operators/deployment-recovery.md` §1 mandates
    /// `Restart=no` for systemd by name and calls it "required for cluster correctness"; §2.1 records
    /// the multi-hour Hetzner chaos-test stall that a restarting policy produced — runtime respawns the
    /// node, the same-id rejoin is rejected, the container respawn-loops, and CTM never sees the
    /// failure because the restart beats its detector.
    @Nested
    class RestartPolicyIsPinned {
        @Test
        void generateDefault_declaresRestartNo_andNoRestartingPolicy() {
            assertRestartIsDisabled(SystemdUnitTemplate.generateDefault());
        }

        @Test
        void generate_customParams_stillDeclaresRestartNo() {
            assertRestartIsDisabled(SystemdUnitTemplate.generate("/usr/local/aether/run.sh",
                                                                 "/etc/aether/other.env",
                                                                 "deploy",
                                                                 "deploy"));
        }

        private void assertRestartIsDisabled(String unit) {
            assertTrue(unit.contains("Restart=no"),
                       () -> "Aether uses terminal-removal membership: a crashed node must NOT restart under "
                             + "the same identity, recovery is a new-ULID replacement via CTM auto-heal. See "
                             + "aether/docs/operators/deployment-recovery.md. Got:\n" + unit);

            for (var forbidden : new String[]{"Restart=always",
                                              "Restart=on-failure",
                                              "Restart=on-abnormal",
                                              "Restart=on-abort",
                                              "Restart=on-success",
                                              "Restart=on-watchdog"}) {
                assertFalse(unit.contains(forbidden),
                            () -> "A restarting policy (" + forbidden + ") resurrects a terminally-removed NodeId "
                                  + "and re-creates the incident in deployment-recovery.md §2.1. Got:\n" + unit);
            }

            assertFalse(unit.contains("RestartSec"),
                        () -> "RestartSec only has meaning alongside a restarting policy; its presence means one "
                              + "was reintroduced. Got:\n" + unit);
        }
    }

    @Nested
    class CustomGeneration {
        @Test
        void generate_customParams_allApplied() {
            var unit = SystemdUnitTemplate.generate("/usr/local/aether/run.sh",
                                                    "/etc/aether/other.env",
                                                    "deploy",
                                                    "deploy");

            assertTrue(unit.contains("User=deploy"), "Should have custom user");
            assertTrue(unit.contains("Group=deploy"), "Should have custom group");
            assertTrue(unit.contains("ExecStart=/usr/local/aether/run.sh"), "Should have custom launcher");
            assertTrue(unit.contains("EnvironmentFile=-/etc/aether/other.env"), "Should have custom env file");
        }
    }
}
