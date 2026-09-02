// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.aether.config.ConfigKeyLive;


/// `role` is #693: parsed and stored in every `RoleSubTable`, but nothing reads this accessor — the
/// map key it's grouped under (`SourceProfile.roles(): Map<NodeRole, RoleSubTable>`) carries the role
/// identity for every real consumer instead. `@ConfigKeyLive`-suppressed rather than deleted: #693 owns
/// the fix, not #519's dead-surface guard.
public record RoleSubTable(@ConfigKeyLive("#693: parsed but never read — SourceProfile.roles() map key carries role instead") NodeRole role,
                           Option<Integer> count,
                           Option<List<String>> hosts,
                           Option<String> instanceType,
                           Option<String> image,
                           String runtimeRef) {
    public static RoleSubTable roleSubTable(NodeRole role,
                                            Option<Integer> count,
                                            Option<List<String>> hosts,
                                            Option<String> instanceType,
                                            Option<String> image,
                                            String runtimeRef) {
        return new RoleSubTable(role, count, hosts, instanceType, image, runtimeRef);
    }

    /// #459 — backward-compatible factory for callers that predate the `[source.<provider>.<role>]
    /// image` field (the VM boot image / snapshot id, distinct from a `[runtime]` container image).
    /// Defaults `image` to absent, so the provider's loud hardcoded default applies. Callers that
    /// carry a spec-level image use the 6-arg factory.
    public static RoleSubTable roleSubTable(NodeRole role,
                                            Option<Integer> count,
                                            Option<List<String>> hosts,
                                            Option<String> instanceType,
                                            String runtimeRef) {
        return roleSubTable(role, count, hosts, instanceType, Option.empty(), runtimeRef);
    }
}
