// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;


/// #1543 F — the version a node runs, as THIS node knows it. A node that joined after bootstrap is in the topology with the labels of its
/// handshake; a bootstrap peer is in the topology only as configured (no version), and its version arrives with its handshake into the
/// membership descriptor, next to role and source. The topology label wins when present; `""` means unknown.
public sealed interface AdvertisedVersion {
    static String of(NodeId id, TopologyManager topology, Option<MembershipFsm> membership) {
        return topology.get(id)
                       .flatMap(info -> Option.option(info.labels().get(NodeInfo.LABEL_VERSION)))
                       .filter(version -> !version.isBlank())
                       .orElse(() -> membership.flatMap(fsm -> fsm.memberDescriptor(id))
                                               .map(MemberDescriptor::version)
                                               .filter(version -> !version.isBlank()))
                       .or("");
    }

    record unused() implements AdvertisedVersion {}
}
