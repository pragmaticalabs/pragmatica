// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.resource.db.async;

import java.util.List;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.lang.Result;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;

/**
 * #769: provisioning must log the resolved effective connection target (post override
 * precedence), not the raw discrete config — otherwise "the log claims the override was
 * applied" is not actually checkable from the log.
 */
class DatasourceProvisioningLoggingTest {

    private static final String PROVIDER_LOGGER = "org.pragmatica.aether.resource.db.DatasourceConnectionProviderInstance";

    @Test
    void connector_withAsyncUrlOverride_logsTheOverrideHostNotTheDiscreteHost() {
        var config = databaseConnectorConfigBuilder()
                .withName("db")
                .withHost("discrete-host")
                .withPort(5432)
                .withDatabase("discrete-db")
                .withAsyncUrl("postgresql://override-host:6543/override-db")
                .build()
                .unwrap();

        var provider = DatasourceConnectionProvider.datasourceConnectionProvider((section, configClass) -> Result.success(config));

        var lines = captureProviderLogLines(() -> {
            var outcome = provider.connector("db").await();
            assertThat(outcome.isSuccess()).isTrue();
        });

        assertThat(lines).anySatisfy(line -> assertThat(line).contains("override-host")
                                                              .contains("6543")
                                                              .contains("override-db")
                                                              .doesNotContain("discrete-host"));
    }

    private static List<String> captureProviderLogLines(Runnable action) {
        try (var capture = LogCapture.capture(PROVIDER_LOGGER, Level.TRACE, true)) {
            action.run();
            return capture.messages();
        }
    }
}
