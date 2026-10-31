// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.regex.Pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.Verify.Is;
import org.pragmatica.lang.utils.Causes;


/// The identity of ONE provider create, and the name the provider gives the server it makes (G1-a): `<cluster>-<nodeId>`, lower
/// case. The node id the intent minted is lower case by construction ([ProvisionContext#mintNodeId], a lower-case ULID under an
/// RFC-1035 cluster name), and the CLUSTER scopes it because a provider project is shared by clusters: bootstrap node ids
/// (`<source>-<role>-<i>`, e.g. `eu-1-core-0`) are unique only within one cluster, so a bare node id made a second cluster's
/// bootstrap into the same project fail with a name held by the first (CTO refinement of DG-1).
///
/// It is a type because the name is the provider-side deduplication key. Hetzner refuses a second server with an existing
/// name, atomically (P-G1 A2, A3), but names are case-SENSITIVE (A4): an upper-case twin of a lower-case name is a distinct
/// server, so a name that is not already lower case would defeat the refusal. The grammar is therefore an RFC-1123 hostname
/// label restricted to lower case: lowercase alphanumerics and hyphens, starting and ending alphanumeric, 1..63 characters.
///
/// An id that does not fit (a hand-named node id with upper case, or a cluster name so long that `<cluster>-aether-<cluster>-node-<ulid>`
/// exceeds 63 characters: more than [#longestClusterNameForMintedIds]) is REFUSED, never truncated or lower-cased on the way: a
/// derived name would no longer be the one the lookup that resolves a create by its id searches for.
///
/// A name is free again once its server is deleted (A5), so an id must never be minted twice. The ULID suffix of a minted id
/// already guarantees it; this type does not.
public record OperationId(String value) {
    /// RFC-1123 label, lower case: the intersection of Hetzner's server-name rule and the label-value rule the same value
    /// also travels in (`aether.op`, `aether-node-id`).
    public static final Pattern PATTERN = Pattern.compile("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?");

    public static Result<OperationId> operationId(String value) {
        return Verify.ensure(value, Is::notNull, OPERATION_ID_INVALID)
                     .flatMap(notNull -> Verify.ensure(notNull, Is::matches, PATTERN, OPERATION_ID_INVALID))
                     .map(OperationId::new);
    }

    /// The operation id of the create of `nodeId` in `cluster`: `<cluster>-<nodeId>`, which must fit the grammar. The node id itself
    /// stays the `aether-node-id` label; this is the name and the `aether.op` label.
    public static Result<OperationId> forNode(ClusterName cluster, String nodeId) {
        return operationId(cluster.value() + "-" + nodeId);
    }

    /// The longest cluster name whose MINTED node ids ([ProvisionContext#mintNodeId], `aether-<cluster>-node-<ulid>`) still yield a
    /// fitting operation id (`<cluster>-<nodeId>`): the config-time limit for a provider that names servers by it.
    public static int longestClusterNameForMintedIds() {
        var overheadOfOneCharacterCluster = ProvisionContext.mintNodeId(ClusterName.maybeClusterName("a")).length();

        return (MAX_LENGTH - 1 - (overheadOfOneCharacterCluster - 1)) / 2;
    }

    /// Hetzner's server-name limit, and the label-value limit the same value also travels under.
    public static final int MAX_LENGTH = 63;

    private static final Cause OPERATION_ID_INVALID = Causes.cause("An operation id must be a lower-case RFC-1123 label: lowercase letters, digits or hyphens, starting and ending with a "
                                                                  + "letter or digit, at most 63 characters. It is the provider-side server name, and provider names are "
                                                                  + "case-sensitive, so an id outside this grammar cannot deduplicate a create.");

    @Override
    public String toString() {
        return value;
    }
}
