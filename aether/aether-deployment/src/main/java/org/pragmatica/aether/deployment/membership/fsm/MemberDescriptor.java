// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;


/// Observed network address and immutable instance role/source/version labels. An unknown role is
/// ineligible for core membership until admission supplies an explicit role. Address updates
/// do not authorize role transitions. These labels describe admitted cluster participants;
/// cryptographic role attestation is a separate boundary.
public record MemberDescriptor(Option<NodeAddress> address, String role, String source, String version) {
    public static final MemberDescriptor UNKNOWN = new MemberDescriptor(Option.none(), "", "", "");

    /// A descriptor that does not know the node's version (`""`).
    public MemberDescriptor(Option<NodeAddress> address, String role, String source) {
        this(address, role, source, "");
    }

    public static MemberDescriptor fromNodeInfo(NodeInfo info) {
        return new MemberDescriptor(Option.option(info.resolvedAddress()),
                                    info.labels().getOrDefault(NodeInfo.LABEL_ROLE, ""),
                                    info.labels().getOrDefault(NodeInfo.LABEL_SOURCE, ""),
                                    info.labels().getOrDefault(NodeInfo.LABEL_VERSION, ""));
    }

    public boolean isCore() {
        return isCoreRole(role);
    }

    public static boolean isCoreRole(String role) {
        return "core".equalsIgnoreCase(role);
    }
}
