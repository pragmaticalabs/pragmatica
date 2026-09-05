// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StorageEncryptionConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #783 fix (2026-09-04): the `content` storage instance used to be provisioned via a separate,
/// keyring-less `StorageFactory.defaultContentStorage` call, entirely outside the config/keyring-aware
/// `createAll` path -- so `AetherNode.assembleNode` logged a boot-time WARN (added by #830) naming the
/// gap whenever a node-wide keyring was configured. `content` is now synthesized through `createAll`
/// exactly like `artifacts` (see `StorageFactory.defaultContentConfig`), so it IS covered
/// (`encrypted = keyring.isPresent()` unless an explicit `[storage.content]` section overrides it) and
/// the WARN is retired along with the gap it named. This pins the WARN's ABSENCE through the REAL boot
/// path (`AetherNode.aetherNode`, not an extracted helper), unlike `AetherNodeStorageEncryptionBootTest`,
/// whose scope is deliberately the FAST-FAIL paths that return before the keyring is even resolved.
/// Red-before: re-adding the retired WARN call at its old site (`resourceProviderSetup.spiProvider()
/// .onPresent(...)` in `AetherNode.assembleNode`) must turn `assembleNode_doesNotWarnOnContentStorage_
/// whenKeyringConfigured` red.
///
/// Unlike `AetherNodeStorageEncryptionBootTest#minimalConfig`, whose `coreNodes = List.of()` is legal
/// only because `AetherNodeConfig#validate` skips its "at least one core node" check under
/// `managementPort = MANAGEMENT_DISABLED` and execution never runs past that check, `minimalConfig`
/// here must satisfy `TopologyObserver`'s unconditional self-in-coreNodes requirement AND a QUIC
/// config capable of both server and client contexts, since the encryption config and
/// `SecretsProvider` are made to actually RESOLVE and execution runs past the fast-fail tests'
/// short-circuit into real assembly: real port binds, real component wiring, and the
/// `resourceProviderSetup.spiProvider().onPresent(...)` block where the WARN used to fire.
///
/// Log-capture strategy follows `DelegatedStorageAdapterTest` / `ClusterTopologyManagerCasLossLoggingTest`:
/// a programmatic log4j2 appender on `AetherNode`'s own logger, asserting on captured WARN messages.
class AetherNodeContentStorageWarnBootTest {
    private static final String LOGGER_NAME = AetherNode.class.getName();
    private static final String SECRET_PATH = "path/to/k1";
    private static final String VALID_AES256_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String CONTENT_STORAGE_WARN_FRAGMENT = "'content' storage instance is NOT covered (#783)";

    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;
    private AetherNode node;

    @BeforeEach
    void setUp() {
        appender = CapturingAppender.create("AetherNodeContentStorageWarnCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        var configuration = ctx.getConfiguration();
        loggerConfig = getOrCreateLoggerConfig(configuration);
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.WARN, null);
        loggerConfig.setLevel(Level.WARN);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        // AetherNode.createResourceProviderFacade sets these as process-wide static singletons
        // when a configProvider is present (required here to reach the SPI/content-storage branch
        // where the WARN fires) -- clear them so this test's fixture never bleeds into another test
        // class sharing the same surefire fork.
        ConfigService.clear();
        ResourceProvider.clear();

        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void assembleNode_doesNotWarnOnContentStorage_whenKeyringConfigured() {
        SecretsProvider provider = path -> Promise.success(Map.of(SECRET_PATH, VALID_AES256_KEY).get(path));
        var encryption = Option.some(StorageEncryptionConfig.storageEncryptionConfig(Map.of("k1", "${secrets:" + SECRET_PATH + "}"),
                                                                                       "k1",
                                                                                       false));

        node = AetherNode.aetherNode(minimalConfig(environmentWith(Option.some(provider)), encryption), () -> {})
                          .onFailure(cause -> fail("boot must succeed: the configured keyring resolves cleanly - " + cause.message()))
                          .unwrap();

        assertThat(appender.capturedWarns())
                .as("#783: `content` is now synthesized through the same config/keyring-aware `createAll` "
                    + "path as every other instance, so a configured keyring covers it too -- there is no "
                    + "more coverage gap left to warn about")
                .noneMatch(msg -> msg.contains(CONTENT_STORAGE_WARN_FRAGMENT));
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void assembleNode_staysSilentOnContentStorage_whenNoKeyringConfigured() {
        node = AetherNode.aetherNode(minimalConfig(Option.none(), Option.none()), () -> {})
                          .onFailure(cause -> fail("boot must succeed with no storage encryption configured at all - "
                                                    + cause.message()))
                          .unwrap();

        assertThat(appender.capturedWarns())
                .as("with no node-wide keyring there was never anything to warn about, before or after #783")
                .noneMatch(msg -> msg.contains(CONTENT_STORAGE_WARN_FRAGMENT));
    }

    /// Unlike `AetherNodeStorageEncryptionBootTest#minimalConfig` (fast-fail only, `coreNodes =
    /// List.of()` is legal there because execution never reaches `TopologyObserver`), this config is
    /// meant to actually RESOLVE and reach a successful boot, so it must satisfy checks that sit past
    /// `AetherNodeConfig#validate`:
    /// - `TopologyObserver` enforces self-node membership in `coreNodes` unconditionally (no
    ///   `MANAGEMENT_DISABLED` exemption at that layer) -- so `coreNodes` carries a `NodeInfo` for
    ///   `self`, mirroring `EmberCluster`'s own single-node bootstrap pattern.
    /// - Real assembly opens both a QUIC server AND a QUIC client context (for peer/cluster
    ///   communication); `TlsConfig.selfSignedServer()` is server-only and `QuicSslContextFactory`
    ///   rejects it for the client side. `TlsConfig.selfSignedMutual()` carries both an identity and
    ///   an (insecure, dev-only) trust-all anchor, satisfying both.
    /// - The retired WARN was nested inside `resourceProviderSetup.spiProvider().onPresent(...)` in
    ///   `AetherNode.assembleNode`, because `content`'s `StorageInstance` is ONLY provisioned via
    ///   `registerRuntimeExtensions` in that same branch (confirmed: it has no other call site).
    ///   `spiProvider` is populated only when `config.configProvider()` is non-empty
    ///   (`createResourceProviderFacade`), so this config supplies a minimal empty
    ///   `ConfigurationProvider` -- any content works, only presence matters, so this test still
    ///   exercises the branch the WARN used to live in. That path also sets
    ///   `ConfigService`/`ResourceProvider` process-wide static singletons, hence the explicit
    ///   `.clear()` calls in `tearDown`.
    private static AetherNodeConfig minimalConfig(Option<EnvironmentIntegration> environment,
                                                   Option<StorageEncryptionConfig> storageEncryption) {
        var self = NodeId.nodeId("content-storage-warn-boot-test").unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());

        return AetherNodeConfig.builder()
                                .self(self)
                                .coreNodes(List.of(selfInfo))
                                .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                                .sliceConfig(SliceConfig.sliceConfig())
                                .artifactRepo(DHTConfig.FULL)
                                .coreMax(1)
                                .appHttp(AppHttpConfig.appHttpConfig())
                                .tls(Option.none())
                                .quicTls(TlsConfig.selfSignedMutual())
                                .certificateProvider(Option.none())
                                .configProvider(Option.some(ConfigurationProvider.builder().build()))
                                .environment(environment)
                                .build()
                                .withStorageEncryption(storageEncryption);
    }

    /// Ephemeral free port for the self node's cluster address. Small open/close race is acceptable
    /// for a single test process.
    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Option<EnvironmentIntegration> environmentWith(Option<SecretsProvider> secrets) {
        return Option.some(EnvironmentIntegration.environmentIntegration(Option.none(), secrets, Option.none()));
    }

    private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
        var existing = configuration.getLoggerConfig(LOGGER_NAME);
        if (LOGGER_NAME.equals(existing.getName())) {
            return existing;
        }
        var fresh = new LoggerConfig(LOGGER_NAME, Level.WARN, false);
        configuration.addLogger(LOGGER_NAME, fresh);
        return fresh;
    }

    /// In-memory log4j2 appender capturing WARN-and-above messages for assertions.
    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            var layout = PatternLayout.createDefaultLayout();
            return new CapturingAppender(name, layout);
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> capturedWarns() {
            return messages.stream().collect(Collectors.toUnmodifiableList());
        }
    }
}
