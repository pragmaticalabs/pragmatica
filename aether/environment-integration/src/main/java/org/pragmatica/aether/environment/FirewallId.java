// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.Verify.Is;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;


/// The PROVIDER'S own identifier for an ingress resource Aether created — a Hetzner firewall id, an AWS
/// security-group id (`sg-0abc…`), an Azure NSG ARM path, a GCP firewall rule name.
///
/// **Opaque on purpose, and validated only as non-blank.** Unlike [ClusterName] and [SourceName] there is
/// no common denominator to parse to: each provider mints these itself in its own shape, and inventing a
/// grammar would reject ids the providers legitimately issue. The invariant that IS worth holding is that
/// an id exists — a blank one reaches a delete call that silently matches nothing, so the resource leaks
/// while the ledger records it as reclaimed.
///
/// It exists as a type mainly to stop transposition. [CreatedResource.CloudFirewall] records four
/// same-shaped values side by side — provider, id, source, name — and before these types any two could be
/// swapped and still compile. This one was previously a `long`, which acted as an accidental type
/// separator; widening it to `String` for AWS/Azure/GCP removed that accident, and this restores it
/// deliberately.
public record FirewallId(String value) {
    public static Result<FirewallId> firewallId(String value) {
        return Verify.ensure(value, Is::notNull, FIREWALL_ID_INVALID)
                     .flatMap(notNull -> Verify.ensure(notNull, Is::notBlank, FIREWALL_ID_INVALID))
                     .map(FirewallId::new);
    }

    /// The numeric form, for providers whose API takes a number — Hetzner's `deleteFirewall(long)`.
    ///
    /// This lives on the type so the conversion has ONE home and one failure story. A non-numeric id
    /// under a numeric-id provider means the ledger was written by something else, and the caller must
    /// REFUSE rather than substitute a guess: deleting a guessed id destroys whatever resource happens to
    /// hold it, which on a shared cloud account is somebody else's firewall.
    public Result<Long> asNumeric() {
        return Number.parseLong(value).mapError(_ -> new NotNumeric(value));
    }

    public record NotNumeric(String raw) implements Cause {
        @Override
        public String message() {
            return "Firewall id '" + raw
                 + "' is not numeric, but the provider recording it uses numeric ids. "
                 + "Refusing to guess an id — reclaim the resource manually and remove the entry from "
                 + "bootstrap-state.json.";
        }
    }

    private static final Cause FIREWALL_ID_INVALID = Causes.cause("Firewall id must not be blank — a blank id reaches a delete call that matches nothing, "
                                                                 + "so the resource leaks as a paid orphan while the cleanup ledger records it as reclaimed.");

    @Override
    public String toString() {
        return value;
    }
}
