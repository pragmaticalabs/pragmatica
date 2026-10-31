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


/// The identity of ONE provider create, and the name the provider gives the server it makes (G1-a, DG-1): the node id the
/// intent minted, which is lower case by construction ([ProvisionContext#mintNodeId], a lower-case ULID under an RFC-1035
/// cluster name).
///
/// It is a type because the name is the provider-side deduplication key. Hetzner refuses a second server with an existing
/// name, atomically (P-G1 A2, A3), but names are case-SENSITIVE (A4): an upper-case twin of a lower-case name is a distinct
/// server, so a name that is not already lower case would defeat the refusal. The grammar is therefore an RFC-1123 hostname
/// label restricted to lower case: lowercase alphanumerics and hyphens, starting and ending alphanumeric, 1..63 characters.
///
/// A node id that does not fit (a hand-named id with upper case, or a cluster name longer than 24 characters, which makes
/// `aether-<cluster>-node-<ulid>` exceed 63) is REFUSED, never truncated or lower-cased on the way: a derived name would no
/// longer be the node's id, and the lookup that resolves a create by that id would miss it.
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

    private static final Cause OPERATION_ID_INVALID = Causes.cause("An operation id must be a lower-case RFC-1123 label: lowercase letters, digits or hyphens, starting and ending with a "
                                                                  + "letter or digit, at most 63 characters. It is the provider-side server name, and provider names are "
                                                                  + "case-sensitive, so an id outside this grammar cannot deduplicate a create.");

    @Override
    public String toString() {
        return value;
    }
}
