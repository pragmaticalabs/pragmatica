// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.management.route.ManagementRoute;

import static org.assertj.core.api.Assertions.assertThat;

/// Spec event-stream-namespaces §6.1/§12.2: writes to `system:*` streams over the HTTP surface are
/// rejected with 405 Method Not Allowed regardless of caller role, independent of the role/auth
/// pipeline. The compile-time SPI split already blocks app code; this exercises the HTTP-path guard
/// ([ManagementServerImpl#isSystemStreamWriteOverHttp]) that backs that 405 response.
///
/// Paths are built via [ManagementRoute#assemble] wherever the test isn't itself exercising
/// malformed/adversarial input, so a route-shape change (e.g. the catalog-form reshape planned for
/// `CONSUMER_GROUP_JOIN`/`CONSUMER_GROUP_LEAVE`) breaks these tests loudly at the assembly call
/// rather than silently testing a path shape no real route registers.
class SystemStreamWriteGateTest {

    @Nested
    class Rejected {

        @Test
        void catalogForm_publish_toSystemNamespace_isGated() {
            var path = ManagementRoute.STREAMS_PUBLISH.assemble("system", "cluster-events", "1.0.0").unwrap();

            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp("POST", path)).isTrue();
        }

        @Test
        void catalogForm_delete_systemNamespace_isGated() {
            var path = ManagementRoute.STREAMS_DELETE.assemble("system", "cluster-events", "1.0.0").unwrap();

            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp("DELETE", path)).isTrue();
        }

        @Test
        void catalogForm_groupCreate_onSystemStream_isGated() {
            var path = ManagementRoute.STREAMS_GROUP_CREATE.assemble("system", "cluster-events", "1.0.0").unwrap();

            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp("POST", path)).isTrue();
        }

        @Test
        void catalogForm_groupDelete_onSystemStream_isGated() {
            var path = ManagementRoute.STREAMS_GROUP_DELETE.assemble("system", "cluster-events", "1.0.0", "my-group").unwrap();

            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp("DELETE", path)).isTrue();
        }

        /// Adversarial evasion pin: [RouteMatcher] URL-decodes param values (not literal/spacer
        /// tokens) before handing them to [ManagementRoute#match]'s [org.pragmatica.aether.management.route.MatchedRoute]
        /// — the exact same decoded value the real `StreamApiRoutes` handler would receive for this
        /// request. Percent-encoding "system" in the namespace segment therefore still resolves to
        /// the literal `system` namespace here, same as it would for the real dispatch.
        @Test
        void catalogForm_percentEncodedSystemNamespace_isGated() {
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp(
                    "POST", "/api/v1/streams/%73ystem/cluster-events/1.0.0/publish")).isTrue();
        }

        /// Condition: a route match whose params fail to resolve to a [org.pragmatica.aether.slice.resource.ResourceAddress]
        /// fails closed (denied), not passed through. A syntactically invalid version is the
        /// simplest way to force that resolution failure while still matching the route shape.
        @Test
        void catalogForm_malformedVersion_failsClosed_isGated() {
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp(
                    "POST", "/api/v1/streams/system/cluster-events/not-a-version/publish")).isTrue();
        }

        /// `Namespace`'s charset is lowercase-only (`[a-z0-9][a-z0-9._-]{0,127}`), so a case-variant
        /// segment fails `ResourceAddress` construction outright rather than being recognized as the
        /// `system` namespace. That failed resolution fails closed (see the malformed-version test
        /// above) — still gated, just via a different mechanism than literal case-insensitive
        /// matching would have been.
        @Test
        void catalogForm_caseVariantNamespace_failsAddressResolution_isGatedFailClosed() {
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp(
                    "POST", "/api/v1/streams/System/cluster-events/1.0.0/publish")).isTrue();
        }
    }

    @Nested
    class Allowed {

        @Test
        void catalogForm_publish_appNamespace_isNotGated() {
            var path = ManagementRoute.STREAMS_PUBLISH.assemble("com.example.app", "orders", "1.0.0").unwrap();

            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp("POST", path)).isFalse();
        }

        @Test
        void catalogForm_namespaceContainingSystemSubstring_isNotGated() {
            // "systemic" must not be mistaken for the reserved "system" namespace.
            var path = ManagementRoute.STREAMS_PUBLISH.assemble("systemic", "orders", "1.0.0").unwrap();

            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp("POST", path)).isFalse();
        }

        @Test
        void read_isNotGated() {
            // GET reads of system streams are allowed; the gate only covers the identity-bearing
            // write routes in STREAM_IDENTITY_WRITE_ROUTES, all of which are POST/DELETE.
            var path = ManagementRoute.STREAMS_EVENTS.assemble("system", "cluster-events", "1.0.0").unwrap();

            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp("GET", path)).isFalse();
        }

        @Test
        void nonStreamPath_isNotGated() {
            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp("POST", "/api/v1/deploy")).isFalse();
        }

        /// [ManagementRoute#STREAM_CREATE] is deliberately excluded from the gate: it's an
        /// idempotent create-if-absent (`StreamRoutes.createStreamWithConfig`) — a name collision
        /// with a framework stream returns `{"exists"}` and never mutates it, so no write ever
        /// actually reaches the framework stream regardless of what name the body carries.
        @Test
        void streamCreate_neverGated_isIdempotentSafe() {
            var path = ManagementRoute.STREAM_CREATE.assemble().unwrap();

            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp("POST", path)).isFalse();
        }

        /// Known, currently open gap — not a false negative in this gate's design, a scope boundary:
        /// [ManagementRoute#CONSUMER_GROUP_JOIN]/[ManagementRoute#CONSUMER_GROUP_LEAVE] carry their
        /// target stream name in the request body (`JoinGroupRequest`/`LeaveGroupRequest`), not the
        /// path, so this path-only gate cannot see it regardless of body content. Closes once these
        /// routes gain path-resolvable identity per the catalog-form reshape
        /// (management-api-versioning-spec.md §3.3).
        @Test
        void consumerGroupJoin_neverGated_bodyCarriedIdentityOutOfScope() {
            var path = ManagementRoute.CONSUMER_GROUP_JOIN.assemble().unwrap();

            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp("POST", path)).isFalse();
        }

        @Test
        void consumerGroupLeave_neverGated_bodyCarriedIdentityOutOfScope() {
            var path = ManagementRoute.CONSUMER_GROUP_LEAVE.assemble().unwrap();

            assertThat(ManagementServerImpl.isSystemStreamWriteOverHttp("POST", path)).isFalse();
        }
    }
}
