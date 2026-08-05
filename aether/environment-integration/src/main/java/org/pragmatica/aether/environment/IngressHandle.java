// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

/// Provider-opaque identity of the ingress resource backing an [ComputeProvider#openIngress] call
/// (spec REQ-5.1.8.4). Carries the id the caller must feed back into the create path so the rule is
/// in force *before* the instance exists.
///
/// Why an id at all: on Hetzner a server created without an explicit firewall association accepts
/// ALL inbound traffic (cluster-bootstrap-spec §6.2). Applying a firewall *after* create therefore
/// leaves a window in which the node is up and fully open. Returning the id lets bootstrap pass it
/// to server-create, so no such window exists.
///
/// [#providerResourceId] is deliberately a `String` and deliberately opaque: it is a Hetzner
/// firewall id today, and an AWS security-group id or GCP firewall name tomorrow (#463). Callers
/// thread it through provider config; they never parse it.
///
/// One handle identifies one provider resource, which may carry MANY rules — a `"tcp+udp"` entry
/// expands to two rules (REQ-5.1.8.1) on a single firewall, so repeated `openIngress` calls for the
/// same source return the SAME handle.
public record IngressHandle(String providerResourceId) {
    public static IngressHandle ingressHandle(String providerResourceId) {
        return new IngressHandle(providerResourceId);
    }
}
