// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.hetzner.HetznerComputeProvider;
import org.pragmatica.aether.environment.hetzner.HetznerEnvironmentConfig;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.cloud.hetzner.HetznerConfig.hetznerConfig;

/// THE PIN for the CLI logging branch: the line that records what was actually sent to Hetzner is
/// ABSENT on a default invocation and PRESENT once the `-v` ladder is applied.
///
/// The asserted line is emitted by production code in
/// `org.pragmatica.aether.environment.hetzner.HetznerComputeProvider#logCreateRequest`, reached by
/// driving the real provider. It is deliberately NOT a line this test emits: the CLI's own logger
/// could be raised while every provider stayed dark, and that configuration would look identical in
/// a test that asserts on a self-emitted line while fixing nothing for the operator reading a 422.
///
/// Three properties make the absent half worth something, because a line that never appears under
/// any configuration also reads as absent:
///
///   1. The SAME run asserts a REAL WARN line from the SAME provider logger
///      (`logProvisionFailureRollbackGap`). If the appender were detached, the binding were still
///      slf4j-nop, the logger were renamed, or the provision had refused before reaching
///      `createAndConfirm`, that assertion fails — so the absent half cannot pass vacuously.
///   2. The level is NOT set by this test. `setUp` loads the SHIPPED `log4j2.xml` via
///      [ShippedLogging#activate()], so "default" means what the jar ships, not what the fixture
///      asserted. This is a deliberate divergence from the house log-capture pattern
///      (`BootstrapAdminKeyLegFallbackWarnTest`), which sets the level on the logger it captures —
///      doing that here would overwrite the one value under test. Loading it BY URI is also not
///      optional: `test-logging`'s `log4j2-test.xml` outranks `log4j2.xml` on every test classpath,
///      and under it the default is INFO, which makes the absent half fail for the right reason and
///      would have made a weaker version of this test pass while measuring the wrong config.
///   3. The capture appender is attached to the ROOT logger config with a `null` level, so it adds
///        no filtering of its own: what it sees is exactly what `org.pragmatica`'s configured level
///        lets through, by additivity.
///
/// The out-of-JVM half of the evidence — that the shaded jar finds this configuration at all, and
/// that `aether-node.jar` emits where the CLI jar did not — is a measured run recorded in the
/// branch report; it cannot live here because the fat jar does not exist at test phase.
class CliLoggingPinTest {
    /// Fragment of the real INFO line. Matches
    /// `HetznerComputeProvider#logCreateRequest`'s message template.
    private static final String CREATE_REQUEST_FRAGMENT = "Hetzner provision: creating server";

    /// Fragment of the real WARN line from the same class, used as the in-run positive control.
    private static final String PROVISION_FAILED_FRAGMENT = "Hetzner provision failed";

    /// The provider package the brief requires the pin to be asserted from: raising only
    /// `org.pragmatica.aether.cli` would fix nothing.
    private static final String PROVIDER_PACKAGE = "org.pragmatica.aether.environment";

    private static final Cause CLIENT_UNUSED =
        Causes.cause("stub HetznerClient: no Hetzner API call is expected before the create-request line is logged");

    private static final HetznerEnvironmentConfig CONFIG =
        HetznerEnvironmentConfig.hetznerEnvironmentConfig(hetznerConfig("test-token"),
                                                          "cx22",
                                                          "ubuntu-24.04",
                                                          "fsn1",
                                                          List.of(1L, 2L),
                                                          List.of(10L),
                                                          List.of(5L),
                                                          "#!/bin/bash\necho hello")
                                .unwrap()
                                .withDiscovery(clusterName("test-cluster").unwrap());

    private CapturingAppender appender;
    private LoggerConfig rootConfig;

    @BeforeEach
    void setUp() {
        var shipped = ShippedLogging.activate();

        appender = CapturingAppender.create("CliLoggingPinCapture");
        appender.start();
        rootConfig = shipped.getRootLogger();
        rootConfig.addAppender(appender, null, null);
        ((LoggerContext) LogManager.getContext(false)).updateLoggers();
    }

    @AfterEach
    void tearDown() {
        rootConfig.removeAppender(appender.getName());
        appender.stop();
        ShippedLogging.restoreTestDefault();
    }

    /// The absent half, with its control in the same run.
    @Test
    void logCreateRequest_absent_atShippedDefaultVerbosity() {
        provisionAgainstStubClient();

        assertThat(appender.captured())
            .describedAs("positive control: the provision MUST have reached createAndConfirm and the "
                         + "provider's WARN line MUST have reached this appender, otherwise the absence "
                         + "below says nothing about the log level")
            .anyMatch(line -> line.contains(PROVISION_FAILED_FRAGMENT));

        assertThat(appender.captured())
            .describedAs("the create-request line is INFO and the shipped default for org.pragmatica is "
                         + "WARN, so it must not appear without -v")
            .noneMatch(line -> line.contains(CREATE_REQUEST_FRAGMENT));
    }

    /// The present half. One `-v` is enough: the line is INFO.
    @Test
    void logCreateRequest_present_atFirstVerbosityRung() {
        Verbosity.verbosity(1).apply();

        provisionAgainstStubClient();

        assertThat(appender.capturedAt(Level.INFO))
            .describedAs("-v must surface the line that records what was actually sent to Hetzner")
            .anyMatch(line -> line.contains(CREATE_REQUEST_FRAGMENT));
    }

    /// The ladder must move the PROVIDER's logger, not just the CLI's. Asserted on the logger name
    /// carried by the captured event, so a ladder scoped to `org.pragmatica.aether.cli` fails here.
    @Test
    void logCreateRequest_emittedFromProviderPackage_notFromTheCli() {
        Verbosity.verbosity(1).apply();

        provisionAgainstStubClient();

        assertThat(appender.capturedLoggerNamesContaining(CREATE_REQUEST_FRAGMENT))
            .describedAs("the pinned line must come from the environment provider package")
            .isNotEmpty()
            .allMatch(loggerName -> loggerName.startsWith(PROVIDER_PACKAGE));
    }

    /// Drive the real provider far enough to emit the create-request line.
    ///
    /// `buildCreateRequest` makes no Hetzner call when the config carries ssh-key and firewall ids
    /// (both are populated in [#CONFIG]), and `logCreateRequest` runs on its success — before
    /// `client::createServer`. The provision therefore fails at the stub, which is expected and
    /// ignored: the subject of these tests is the log line, not the outcome.
    private void provisionAgainstStubClient() {
        HetznerComputeProvider.hetznerComputeProvider(stubClient(), CONFIG)
                              .unwrap()
                              .provision(InstanceType.ON_DEMAND)
                              .await();
    }

    /// Every method fails. A proxy rather than thirty hand-written stubs, so that an unexpected API
    /// call surfaces as a failed promise instead of silently returning null.
    private static HetznerClient stubClient() {
        return (HetznerClient) Proxy.newProxyInstance(HetznerClient.class.getClassLoader(),
                                                      new Class<?>[] {HetznerClient.class},
                                                      (proxy, method, args) -> CLIENT_UNUSED.promise());
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<String> captured() {
            return events.stream().map(event -> event.getMessage().getFormattedMessage()).toList();
        }

        List<String> capturedAt(Level level) {
            return events.stream()
                         .filter(event -> event.getLevel().equals(level))
                         .map(event -> event.getMessage().getFormattedMessage())
                         .toList();
        }

        List<String> capturedLoggerNamesContaining(String fragment) {
            return events.stream()
                         .filter(event -> event.getMessage().getFormattedMessage().contains(fragment))
                         .map(LogEvent::getLoggerName)
                         .toList();
        }
    }
}
