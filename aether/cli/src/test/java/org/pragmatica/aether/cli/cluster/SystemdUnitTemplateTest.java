package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemdUnitTemplateTest {

    @Nested
    class DefaultGeneration {
        @Test
        void generateDefault_containsCorrectUser() {
            var unit = SystemdUnitTemplate.generateDefault();
            assertTrue(unit.contains("User=aether"), "Should have User=aether");
            assertTrue(unit.contains("Group=aether"), "Should have Group=aether");
        }

        @Test
        void generateDefault_containsCorrectPaths() {
            var unit = SystemdUnitTemplate.generateDefault();
            assertTrue(unit.contains("/opt/aether/aether-node.jar"), "Should have jar path");
            assertTrue(unit.contains("--config=/opt/aether/config/aether.toml"), "Should have config path");
        }

        @Test
        void generateDefault_containsJvmArgs() {
            var unit = SystemdUnitTemplate.generateDefault();
            assertTrue(unit.contains("-Xmx4g"), "Should have heap size");
            assertTrue(unit.contains("-XX:+UseZGC"), "Should have ZGC");
            assertTrue(unit.contains("-XX:+ZGenerational"), "Should have ZGenerational");
        }

        @Test
        void generateDefault_containsSystemdDirectives() {
            var unit = SystemdUnitTemplate.generateDefault();
            assertTrue(unit.contains("[Unit]"), "Should have [Unit] section");
            assertTrue(unit.contains("[Service]"), "Should have [Service] section");
            assertTrue(unit.contains("[Install]"), "Should have [Install] section");
            assertTrue(unit.contains("After=network-online.target"), "Should wait for network");
            assertTrue(unit.contains("Restart=on-failure"), "Should restart on failure");
            assertTrue(unit.contains("WantedBy=multi-user.target"), "Should be multi-user target");
        }
    }

    @Nested
    class CustomGeneration {
        @Test
        void generate_customParams_allApplied() {
            var unit = SystemdUnitTemplate.generate(
                "-Xmx8g -XX:+UseG1GC",
                "/usr/local/aether/node.jar",
                "/etc/aether/config.toml",
                "deploy",
                "deploy"
            );
            assertTrue(unit.contains("User=deploy"), "Should have custom user");
            assertTrue(unit.contains("Group=deploy"), "Should have custom group");
            assertTrue(unit.contains("/usr/local/aether/node.jar"), "Should have custom jar path");
            assertTrue(unit.contains("--config=/etc/aether/config.toml"), "Should have custom config path");
            assertTrue(unit.contains("-Xmx8g -XX:+UseG1GC"), "Should have custom JVM args");
        }
    }
}
