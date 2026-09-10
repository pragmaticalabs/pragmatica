// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;

/// #980 — the cluster secret must never appear in `AetherNodeConfig`'s rendering.
///
/// **Why this became load-bearing with #980 rather than before it.** The generated record
/// `toString()` printed the secret in plaintext all along, but until this ticket that secret bought
/// transport compromise and not the management API. `BootstrapAdminKeyLeg` now derives the cluster's
/// ADMIN credential from it, so any line that dumps this config — a debug log, an exception message, a
/// test failure report, a transcript — now leaks an admin credential. The change that raised the
/// severity is the change that handles it.
///
/// **This class is an instrument, so it is calibrated in both directions.** An assertion that some
/// string is ABSENT is satisfied by a rendering that contains nothing at all, by a sentinel that never
/// reached the object, or by a typo in the sentinel — all of which read as green. So
/// [#toString_redactsTheSecret_andTheSentinelIsGenuinelyPresent] first proves the sentinel IS in the
/// config and IS detectable by the very check used against the rendering, and only then asserts the
/// rendering omits it. Without that first half the test would pass against the leak it exists to
/// prevent.
class AetherNodeConfigRedactionTest {
    /// Distinctive on purpose: no substring of it occurs in any other field's rendering, so a hit is
    /// unambiguous and a miss cannot be a coincidence.
    private static final String SENTINEL_SECRET = "SENTINEL-CLUSTER-SECRET-q7x2v9-DO-NOT-LOG";

    /// THE pin, with its control in the same test so the two cannot drift apart.
    @Test
    void toString_redactsTheSecret_andTheSentinelIsGenuinelyPresent() {
        var config = minimalConfig().withClusterSecret(Option.some(SENTINEL_SECRET));

        // Control 1 — the sentinel really is in the object. If the stamp silently dropped it, the
        // absence assertion below would pass while proving nothing.
        assertThat(config.clusterSecret().unwrap())
            .describedAs("precondition: the sentinel must actually be stamped onto the config")
            .isEqualTo(SENTINEL_SECRET);

        // Control 2 — the CHECK can detect the sentinel when it is present. This is the un-redacted
        // rendering of the same field, formed the way the generated toString would have; `contains`
        // finds it here, so a miss below is a real absence and not a broken predicate.
        assertThat("clusterSecret=" + config.clusterSecret())
            .describedAs("control: the containment check must be able to SEE the secret, or its "
                         + "absence below is meaningless")
            .contains(SENTINEL_SECRET);

        // THE ASSERTION.
        assertThat(config.toString())
            .describedAs("AetherNodeConfig renders the ADMIN-equivalent cluster secret in plaintext; "
                         + "any log line, exception message or test dump of this config leaks it")
            .doesNotContain(SENTINEL_SECRET);
    }

    /// Redaction must not become silence: an operator debugging a boot needs to know whether a secret
    /// was resolved at all. Presence is rendered, the value is not.
    @Test
    void toString_stillShowsWhetherASecretIsPresent() {
        assertThat(minimalConfig().withClusterSecret(Option.some(SENTINEL_SECRET)).toString())
            .contains("clusterSecret=Some(<redacted>)");
        assertThat(minimalConfig().withClusterSecret(Option.none()).toString())
            .contains("clusterSecret=None");
    }

    /// The rest of the rendering must survive — a `toString` that redacted the secret by dropping 32
    /// useful fields would pass the assertion above and destroy the diagnostic.
    @Test
    void toString_stillRendersTheOtherComponents() {
        var rendered = minimalConfig().withClusterSecret(Option.some(SENTINEL_SECRET)).toString();

        assertThat(rendered).startsWith("AetherNodeConfig[topology=");
        assertThat(rendered).contains("managementPort=", "sliceConfig=", "appHttp=", "alerts=", "clusterName=");
        assertThat(rendered).endsWith("]");
    }

    /// Drift tripwire. The generated `toString` covered new record components automatically; the
    /// hand-written override does not, so a component added later would silently vanish from the
    /// rendering with every test still green. This fails the moment the count changes, with the
    /// instruction attached.
    @Test
    void toString_componentCount_matchesThisOverride() {
        assertThat(AetherNodeConfig.class.getRecordComponents())
            .describedAs("AetherNodeConfig's component count changed. The hand-written toString() "
                         + "override in AetherNodeConfig does NOT pick up new components "
                         + "automatically — add the new one to it (redacting it if it carries a "
                         + "secret), then update this expected count.")
            .hasSize(33);
    }

    /// Same shape as `AetherNodeStorageEncryptionBootTest#minimalConfig` — nothing here is on the path
    /// under test; only the rendering is.
    private static AetherNodeConfig minimalConfig() {
        return AetherNodeConfig.builder()
                               .self(NodeId.nodeId("redaction-test").unwrap())
                               .coreNodes(List.of())
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.FULL)
                               .coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig())
                               .tls(Option.none())
                               .quicTls(TlsConfig.selfSignedServer())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.none())
                               .build();
    }
}
