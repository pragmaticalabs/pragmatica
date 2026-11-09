// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.invoke.InvocationMessage_InvokeRequestCodec;
import org.pragmatica.aether.invoke.LegacyInvokeRequest;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// #2157 A4-2 (CTO ruling): `InvokeRequest` gained `caller`, and a record is a positional concatenation, so the new shape
/// cannot read the bytes a node of the previous build sends during a rolling upgrade (it failed with an untyped
/// `IndexOutOfBoundsException`). The change is therefore ADDITIVE, as #1155 was: the new shape has its own tag (644), and tag
/// 642 keeps decoding the old shape, as a request with no caller (fail-closed identity: the callee's `SecurityContext`
/// parameter gets the anonymous context). Encoding always writes the new tag.
class InvokeRequestWireCompatibilityTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final NodeId SENDER = new NodeId("node-a");
    private static final Artifact TARGET = Artifact.artifact("org.example:leaf-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("whoAmI").unwrap();
    private static final byte[] PAYLOAD = {1, 2, 3};
    private static final SecurityContext ALICE = SecurityContext.securityContext(Principal.principal("user:alice").unwrap(),
                                                                                 Set.of(Role.role("auditor").unwrap()),
                                                                                 Map.of("tenant", "t-1"),
                                                                                 AuthorizationRole.VIEWER);
    /// Tag 642 (VLQ 8205), then the ten components of the shape rc5 shipped: the bytes the base build (`ae9f78d49a`, the
    /// release tip this change branched from) wrote for this request through `NodeCodecs`, captured by running it.
    private static final String OLD_BYTES = "82051511066e6f64652d611106636f72722d3111057265712d31c001c301110b6f72672e6578616d706c65c201110a6c6561662d736c696365"
                                            + "c4010d000000010d000000000d00000000110067110677686f416d490803010203050d000000020d0000000106";
    /// The same ten components under the new tag 644 (VLQ 8405), then `caller` = none (tag 1).
    private static final String NEW_BYTES_NO_CALLER = "8405" + OLD_BYTES.substring(4) + "01";

    private static InvokeRequest request(Option<SecurityContext> caller) {
        return InvokeRequest.invokeRequest(SENDER, "corr-1", "req-1", TARGET, METHOD, PAYLOAD, true, 2, 1, false, caller);
    }

    private static LegacyInvokeRequest oldShape() {
        return new LegacyInvokeRequest(SENDER, "corr-1", "req-1", TARGET, METHOD, PAYLOAD, true, 2, 1, false);
    }

    @Test
    void theTagsAreAssigned_theOldTagKeepsTheOldShape_theCurrentShapeTakesANewOne() {
        assertThat(LegacyInvokeRequest.TAG).isEqualTo(642);
        assertThat(InvocationMessage_InvokeRequestCodec.TAG).isEqualTo(644);
    }

    @Test
    void anOldSendersRequest_decodesAsTheCurrentTypeWithNoCaller_neverAnException() {
        var decoded = (InvokeRequest) CODEC.decode(HexFormat.of().parseHex(OLD_BYTES));

        assertThat(decoded.caller().isEmpty()).as("an old sender states no caller: fail-closed identity").isTrue();
        assertThat(decoded).usingRecursiveComparison().isEqualTo(request(Option.none()));
    }

    @Test
    void theOldShapeEncodesToTheBytesTheBaseBuildWrote() {
        assertThat(HexFormat.of().formatHex(CODEC.encode(oldShape()))).isEqualTo(OLD_BYTES);
    }

    /// The old bytes are not only what this test's own writer emits: the generated codec of the current shape, an independent
    /// producer, writes the same ten components and then one more.
    @Test
    void aCurrentRequest_alwaysEncodesUnderTheNewTag_andTheOldBodyIsItsPrefix() {
        assertThat(HexFormat.of().formatHex(CODEC.encode(request(Option.none())))).isEqualTo(NEW_BYTES_NO_CALLER);
    }

    /// The caller crosses exactly as the edge validated it: principal, roles, claims and authorization role.
    @Test
    void aCurrentRequestWithACaller_roundTripsTheCallerExactly() {
        var bytes = CODEC.encode(request(Option.some(ALICE)));
        var decoded = (InvokeRequest) CODEC.decode(bytes);

        assertThat(HexFormat.of().formatHex(bytes)).startsWith("8405");
        assertThat(decoded.caller()).isEqualTo(Option.some(ALICE));
        assertThat(decoded.caller().map(SecurityContext::claims)).isEqualTo(Option.some(Map.of("tenant", "t-1")));
    }
}
