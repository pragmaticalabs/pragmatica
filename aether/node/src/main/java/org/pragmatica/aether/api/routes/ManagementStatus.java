// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.routing.ParameterError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// The authority that turns a CAUSE into a management-API HTTP status (the funnel behind
/// [ProblemResponses#writeProblem], which every management route and `MavenProtocolRoutes` write failures through).
///
/// Two other cause-to-status mappers remain in the management plane, both documented tables for failure classes that
/// are not route errors: `ManagementServer.resolveSecurityErrorStatus` (authentication and authorisation denials:
/// 401/403) and `ManagementServer.managementForwardFailureStatus` (a forwarded request that could not be delivered:
/// `ForwardError.status()`, else 503). `MavenProtocolRoutes` keeps its own 504 (timeout) and 503 plus `Retry-After`
/// (tier not admitted) and sends every other cause through this funnel.
///
/// A cause is classified by exactly one of these rules, in this order:
///
/// 1. **It states its own status** ([HttpStatusAware]): the typed error families (deploy, schema, scale, community,
///    cluster config, `ManagementServerError`, ...) carry their statuses with their variants, because the families
///    live in modules that cannot see the management plane.
/// 2. **A routing-layer [ParameterError]**: the caller got a path or query value wrong, 400 (#1921).
/// 3. **A [StreamError]**: the compiler-total [StreamErrorStatus] switch. A stream refusal that reaches the funnel
///    without the `StreamErrorStatus.typed` wrapper therefore keeps its class status instead of falling to 500.
/// 4. **A composite** ([Causes.CompositeCause], what `Result.all` hands back): the one status every member agrees on;
///    members that disagree stay 500, the conservative answer for a mixed failure.
/// 5. **The central table below**: request-validation and state-conflict causes of the management plane that are plain
///    `Cause` enums or records private to a route file (they were all answered 500 until they were listed here).
///    Keyed by class name, and by `Class#CONSTANT` for enums, so one constant can differ from its siblings.
///
/// A cause matching none of the rules is **unclassified**: it still answers 500, but no longer silently -- it is logged
/// once per class at WARN, naming the type, so the next one is found by its first occurrence and not by a client's bug
/// report. `ManagementStatusCoverageTest` iterates every concrete `Cause` class of the management plane and fails
/// when one is unclassified or its status changes, which is what keeps a NEW cause type from reaching the fallback.
///
/// What this does NOT cover: an ad-hoc `Causes.cause("text")` instance has no type to classify, so it can only be
/// caught at runtime (the WARN above), not by a test that iterates types.
public final class ManagementStatus {
    private static final Logger LOG = LoggerFactory.getLogger(ManagementStatus.class);
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> WARNINGS = new ConcurrentHashMap<>();
    private static final String API = "org.pragmatica.aether.api.";
    private static final String ROUTES = API + "routes.";
    private static final HttpStatus BAD_REQUEST = HttpStatus.BAD_REQUEST;
    private static final HttpStatus FORBIDDEN = HttpStatus.FORBIDDEN;
    private static final HttpStatus CONFLICT = HttpStatus.CONFLICT;
    private static final HttpStatus UNAVAILABLE = HttpStatus.SERVICE_UNAVAILABLE;

    /// Status of the plain-`Cause` types of the management plane. Dev-mode gates answer 403 (the feature exists and
    /// is switched off), malformed or missing request fields 400, a state the request cannot be applied to 409, and a
    /// subsystem that is not available on this node 503. Statuses are judgments made when the table was written (#569
    /// precedent: a caller's mistake is 4xx), pinned by `ManagementStatusCoverageTest`.
    private static final Map<String, HttpStatus> CENTRAL = Stream.of(Listing.listing(ROUTES + "AlertRoutes$AlertError",
                                                                                     BAD_REQUEST,
                                                                                     "MISSING_FIELDS",
                                                                                     "METRIC_REQUIRED"),
                                                                     Listing.listing(API + "AlertManager$InjectionError",
                                                                                     BAD_REQUEST,
                                                                                     "NAME_REQUIRED",
                                                                                     "MESSAGE_REQUIRED",
                                                                                     "INVALID_SEVERITY"),
                                                                     Listing.listing(ROUTES
                                                                                    + "ObservabilityRoutes$ObservabilityError",
                                                                                     BAD_REQUEST,
                                                                                     "MISSING_FIELDS",
                                                                                     "INVALID_DEPTH",
                                                                                     "REQUEST_ID_REQUIRED",
                                                                                     "KEY_REQUIRED",
                                                                                     "CONFIG_MISSING_FIELDS",
                                                                                     "CONFIG_KEY_REQUIRED"),
                                                                     Listing.listing(ROUTES
                                                                                    + "MetricsRoutes$BackfillError",
                                                                                     BAD_REQUEST,
                                                                                     "MISSING_BODY",
                                                                                     "MISSING_METRIC",
                                                                                     "INVALID_WINDOW",
                                                                                     "INVALID_INTERVAL"),
                                                                     Listing.listing(ROUTES
                                                                                    + "MetricsRoutes$BackfillError",
                                                                                     FORBIDDEN,
                                                                                     "DEV_MODE_DISABLED"),
                                                                     Listing.listing(ROUTES + "DhtRoutes$DhtError",
                                                                                     BAD_REQUEST,
                                                                                     "MISSING_BODY",
                                                                                     "MISSING_KEY",
                                                                                     "MISSING_VALUE",
                                                                                     "MISSING_HLC"),
                                                                     Listing.listing(ROUTES + "DhtRoutes$DhtError",
                                                                                     FORBIDDEN,
                                                                                     "DEV_MODE_DISABLED"),
                                                                     Listing.listing(ROUTES + "DhtRoutes$DhtError",
                                                                                     UNAVAILABLE,
                                                                                     "DHT_UNAVAILABLE"),
                                                                     Listing.listing(ROUTES
                                                                                    + "CertificateRoutes$CertConfigureError",
                                                                                     BAD_REQUEST,
                                                                                     "MISSING_BODY",
                                                                                     "VALIDITY_TOO_LOW",
                                                                                     "VALIDITY_TOO_HIGH"),
                                                                     Listing.listing(ROUTES
                                                                                    + "CertificateRoutes$CertConfigureError",
                                                                                     FORBIDDEN,
                                                                                     "DEV_MODE_DISABLED"),
                                                                     Listing.listing(ROUTES
                                                                                    + "CertificateRoutes$CertConfigureError",
                                                                                     UNAVAILABLE,
                                                                                     "SCHEDULER_NOT_CONFIGURED"),
                                                                     Listing.listing(ROUTES
                                                                                    + "NodeLifecycleRoutes$PromoteError",
                                                                                     BAD_REQUEST,
                                                                                     "MISSING_TARGET_ROLE",
                                                                                     "UNSUPPORTED_TARGET_ROLE"),
                                                                     Listing.listing(ROUTES
                                                                                    + "NodeLifecycleRoutes$PromoteError",
                                                                                     CONFLICT,
                                                                                     "UNKNOWN_NODE_ROLE",
                                                                                     "IMMUTABLE_ROLE"),
                                                                     Listing.listing(ROUTES
                                                                                    + "ScheduledTaskRoutes$InjectError$General",
                                                                                     BAD_REQUEST,
                                                                                     "MISSING_BODY",
                                                                                     "MISSING_SECTION",
                                                                                     "MISSING_ARTIFACT",
                                                                                     "MISSING_METHOD"),
                                                                     Listing.listing(ROUTES
                                                                                    + "ScheduledTaskRoutes$InjectError$General",
                                                                                     FORBIDDEN,
                                                                                     "DEV_MODE_DISABLED"),
                                                                     Listing.listing(API
                                                                                    + "ClusterEventAggregator$PublishError",
                                                                                     UNAVAILABLE,
                                                                                     "PUBLISHER_NOT_BOUND"),
                                                                     Listing.listing("org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator$CoordinatorError",
                                                                                     CONFLICT,
                                                                                     "NOT_LEADER"),
                                                                     Listing.listing(ROUTES
                                                                                    + "ScheduledTaskRoutes$InjectError$SliceNotLocal",
                                                                                     CONFLICT),
                                                                     Listing.listing(ROUTES
                                                                                    + "ClusterTopologyRoutes$OwnershipError$UnknownDomain",
                                                                                     BAD_REQUEST),
                                                                     Listing.listing(ROUTES
                                                                                    + "GossipKeyRoutes$GossipKeyRotationError$Superseded",
                                                                                     CONFLICT),
                                                                     Listing.listing(ROUTES
                                                                                    + "ClusterConfigRoutes$UpgradeError$AlreadyAtVersion",
                                                                                     CONFLICT),
                                                                     Listing.listing(API
                                                                                    + "AlertForwarder$AlertForwarderError$WebhookError",
                                                                                     HttpStatus.BAD_GATEWAY))
                                                                 .flatMap(Listing::entries)
                                                                 .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                                                                       Map.Entry::getValue));

    private ManagementStatus() {}

    /// The status the funnel answers for `cause`: its classified status, else 500 after naming the unclassified type.
    public static HttpStatus statusOf(Cause cause) {
        return classifiedStatus(cause).or(() -> unclassified(cause));
    }

    /// The status `cause` is classified under, or empty when no rule covers it. The coverage test reads this.
    public static Option<HttpStatus> classifiedStatus(Cause cause) {
        return switch (cause) {
            case HttpStatusAware aware -> some(aware.httpStatus());
            case ParameterError _ -> some(HttpStatus.BAD_REQUEST);
            case StreamError error -> some(StreamErrorStatus.statusOf(error));
            case Causes.CompositeCause composite -> compositeStatus(composite);
            default -> option(CENTRAL.get(centralKey(cause)));
        };
    }

    /// Every key of the central table (`Class` or `Class#CONSTANT`).
    static Set<String> listedKeys() {
        return CENTRAL.keySet();
    }

    /// How many unclassified-cause WARNs were emitted for this type: exactly 1 once it has met the funnel.
    static int warningsFor(String type) {
        return WARNINGS.getOrDefault(type, 0);
    }

    /// The cause types reported as unclassified so far (each is logged once).
    static Set<String> reportedTypes() {
        return Set.copyOf(REPORTED);
    }

    private static Option<HttpStatus> compositeStatus(Causes.CompositeCause composite) {
        var members = composite.stream().map(ManagementStatus::classifiedStatus).toList();

        return members.stream()
                      .anyMatch(Option::isEmpty)
               ? none()
               : some(agreedStatus(members));
    }

    private static HttpStatus agreedStatus(List<Option<HttpStatus>> members) {
        var statuses = members.stream().flatMap(Option::stream).distinct().toList();

        return statuses.size() == 1
               ? statuses.getFirst()
               : HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String centralKey(Cause cause) {
        return cause instanceof Enum<?> constant
               ? constant.getDeclaringClass()
                         .getName() + "#" + constant.name()
               : cause.getClass()
                      .getName();
    }

    private static HttpStatus unclassified(Cause cause) {
        var type = cause.getClass().getName();

        if (REPORTED.add(type)) {
            WARNINGS.merge(type, 1, Integer::sum);
            LOG.warn("Management cause {} has no HTTP status: answering 500. List it in ManagementStatus or give it HttpStatusAware ({})",
                     type,
                     cause.message());
        }

        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /// One table row: a class (no constants), or the listed constants of an enum, all under one status.
    private record Listing(String type, HttpStatus status, List<String> constants) {
        static Listing listing(String type, HttpStatus status, String... constants) {
            return new Listing(type, status, List.of(constants));
        }

        Stream<Map.Entry<String, HttpStatus>> entries() {
            return constants.isEmpty()
                   ? Stream.of(Map.entry(type, status))
                   : constants.stream()
                              .map(constant -> Map.entry(type + "#" + constant, status));
        }
    }
}
