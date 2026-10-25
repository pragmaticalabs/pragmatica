// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.routing.ParameterError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/// The management-API status funnel is total over the management plane's cause types.
///
/// Every concrete `Cause` class under `org.pragmatica.aether.api` (every enum constant, every record, every class) is
/// discovered from the compiled classes and answered through [ManagementStatus]. The test fails when
///
/// - a cause type is unclassified (a NEW type that reaches the funnel with no status: add it to the central table or
///   give it `HttpStatusAware`),
/// - a type the table lists no longer exists (a rename: the table entry would silently stop applying),
/// - a status differs from the pinned one.
///
/// An ad-hoc `Causes.cause("text")` instance has no type, so it is outside this universe by construction.
class ManagementStatusCoverageTest {
    private static final String UNCLASSIFIED = "UNCLASSIFIED";
    private static final String API_PACKAGE = "org/pragmatica/aether/api";

    /// Pinned statuses. Records are probed with default component values; a record carrying its status as a component
    /// (`StreamRefused`) is probed with `CONFLICT`.
    private static final String API = "org.pragmatica.aether.api.";
    private static final String ROUTES = API + "routes.";
    private static final Map<String, String> PINNED = new TreeMap<>(Map.ofEntries(
            entry(API + "AlertForwarder$AlertForwarderError$WebhookError", "BAD_GATEWAY"),
            entry(API + "AlertManager$InjectionError#INVALID_SEVERITY", "BAD_REQUEST"),
            entry(API + "AlertManager$InjectionError#MESSAGE_REQUIRED", "BAD_REQUEST"),
            entry(API + "AlertManager$InjectionError#NAME_REQUIRED", "BAD_REQUEST"),
            entry(API + "ClusterEventAggregator$PublishError#PUBLISHER_NOT_BOUND", "SERVICE_UNAVAILABLE"),
            entry(API + "ManagementServerError$BindFailed", "INTERNAL_SERVER_ERROR"),
            entry(API + "ManagementServerError$Conflict", "CONFLICT"),
            entry(API + "ManagementServerError$InvalidArtifactPath", "BAD_REQUEST"),
            entry(API + "ManagementServerError$InvalidPartition", "BAD_REQUEST"),
            entry(API + "ManagementServerError$InvalidRequest", "BAD_REQUEST"),
            entry(API + "ManagementServerError$MissingField", "BAD_REQUEST"),
            entry(API + "ManagementServerError$NotFound", "NOT_FOUND"),
            entry(API + "ManagementServerError$NotImplemented", "NOT_IMPLEMENTED"),
            entry(API + "ManagementServerError$NotLeader", "CONFLICT"),
            entry(API + "ManagementServerError$PublishRetryable", "SERVICE_UNAVAILABLE"),
            entry(API + "ManagementServerError$ReservedStreamName", "BAD_REQUEST"),
            entry(API + "ManagementServerError$ServiceUnavailable", "SERVICE_UNAVAILABLE"),
            entry(API + "ManagementServerError$StrategyChangeNotSupported#INSTANCE", "NOT_IMPLEMENTED"),
            entry(API + "ManagementServerError$StreamAlreadyRegistered", "CONFLICT"),
            entry(API + "ManagementServerError$StreamInfoUnavailable", "SERVICE_UNAVAILABLE"),
            entry(API + "ManagementServerError$StreamRefused", "CONFLICT"),
            entry(API + "ManagementServerError$StreamUnavailable", "CONFLICT"),
            entry(ROUTES + "AlertRoutes$AlertError#METRIC_REQUIRED", "BAD_REQUEST"),
            entry(ROUTES + "AlertRoutes$AlertError#MISSING_FIELDS", "BAD_REQUEST"),
            entry(ROUTES + "ApiKeyRoutes$ConfigDeclaredKeyError", "CONFLICT"),
            entry(ROUTES + "ApiKeyRoutes$KeyNotFoundError", "NOT_FOUND"),
            entry(ROUTES + "BackupRoutes$BackupRouteError#BACKUP_NOT_ENABLED", "CONFLICT"),
            entry(ROUTES + "CertificateRoutes$CertConfigureError#DEV_MODE_DISABLED", "FORBIDDEN"),
            entry(ROUTES + "CertificateRoutes$CertConfigureError#MISSING_BODY", "BAD_REQUEST"),
            entry(ROUTES + "CertificateRoutes$CertConfigureError#SCHEDULER_NOT_CONFIGURED", "SERVICE_UNAVAILABLE"),
            entry(ROUTES + "CertificateRoutes$CertConfigureError#VALIDITY_TOO_HIGH", "BAD_REQUEST"),
            entry(ROUTES + "CertificateRoutes$CertConfigureError#VALIDITY_TOO_LOW", "BAD_REQUEST"),
            entry(ROUTES + "ClusterConfigRoutes$ConfigNotFoundError#NOT_FOUND", "NOT_FOUND"),
            entry(ROUTES + "ClusterConfigRoutes$UpgradeError$AlreadyAtVersion", "CONFLICT"),
            entry(ROUTES + "ClusterTopologyRoutes$OwnershipError$UnknownDomain", "BAD_REQUEST"),
            entry(ROUTES + "CommunityRouteError$CommunityNotFound", "NOT_FOUND"),
            entry(ROUTES + "ConfigRoutes$ConfigError#KEY_REQUIRED", "BAD_REQUEST"),
            entry(ROUTES + "ConfigRoutes$ConfigError#MISSING_FIELDS", "BAD_REQUEST"),
            entry(ROUTES + "DeployRouteError#INSTANCES_BELOW_MINIMUM", "BAD_REQUEST"),
            entry(ROUTES + "DeployRouteError#INVALID_STRATEGY", "BAD_REQUEST"),
            entry(ROUTES + "DeployRouteError#MISSING_BLUEPRINT", "BAD_REQUEST"),
            entry(ROUTES + "DeployRouteError#MISSING_CANARY_STAGES", "BAD_REQUEST"),
            entry(ROUTES + "DeployRouteError#MISSING_STRATEGY", "BAD_REQUEST"),
            entry(ROUTES + "DhtRoutes$DhtError#DEV_MODE_DISABLED", "FORBIDDEN"),
            entry(ROUTES + "DhtRoutes$DhtError#DHT_UNAVAILABLE", "SERVICE_UNAVAILABLE"),
            entry(ROUTES + "DhtRoutes$DhtError#MISSING_BODY", "BAD_REQUEST"),
            entry(ROUTES + "DhtRoutes$DhtError#MISSING_HLC", "BAD_REQUEST"),
            entry(ROUTES + "DhtRoutes$DhtError#MISSING_KEY", "BAD_REQUEST"),
            entry(ROUTES + "DhtRoutes$DhtError#MISSING_VALUE", "BAD_REQUEST"),
            entry(ROUTES + "GossipKeyRoutes$GossipKeyRotationError$Superseded", "CONFLICT"),
            entry(ROUTES + "LogLevelRoutes$LogLevelError#INVALID_LEVEL", "BAD_REQUEST"),
            entry(ROUTES + "LogLevelRoutes$LogLevelError#LOGGER_REQUIRED", "BAD_REQUEST"),
            entry(ROUTES + "LogLevelRoutes$LogLevelError#MISSING_FIELDS", "BAD_REQUEST"),
            entry(ROUTES + "MetricsRoutes$BackfillError#DEV_MODE_DISABLED", "FORBIDDEN"),
            entry(ROUTES + "MetricsRoutes$BackfillError#INVALID_INTERVAL", "BAD_REQUEST"),
            entry(ROUTES + "MetricsRoutes$BackfillError#INVALID_WINDOW", "BAD_REQUEST"),
            entry(ROUTES + "MetricsRoutes$BackfillError#MISSING_BODY", "BAD_REQUEST"),
            entry(ROUTES + "MetricsRoutes$BackfillError#MISSING_METRIC", "BAD_REQUEST"),
            entry(ROUTES + "NodeLifecycleRoutes$PromoteError#IMMUTABLE_ROLE", "CONFLICT"),
            entry(ROUTES + "NodeLifecycleRoutes$PromoteError#MISSING_TARGET_ROLE", "BAD_REQUEST"),
            entry(ROUTES + "NodeLifecycleRoutes$PromoteError#UNKNOWN_NODE_ROLE", "CONFLICT"),
            entry(ROUTES + "NodeLifecycleRoutes$PromoteError#UNSUPPORTED_TARGET_ROLE", "BAD_REQUEST"),
            entry(ROUTES + "NodeLifecycleRoutes$SliceFloorBreached", "CONFLICT"),
            entry(ROUTES + "NotImplementedRoutes$NotImplemented", "NOT_IMPLEMENTED"),
            entry(ROUTES + "ObservabilityRoutes$ObservabilityError#CONFIG_KEY_REQUIRED", "BAD_REQUEST"),
            entry(ROUTES + "ObservabilityRoutes$ObservabilityError#CONFIG_MISSING_FIELDS", "BAD_REQUEST"),
            entry(ROUTES + "ObservabilityRoutes$ObservabilityError#INVALID_DEPTH", "BAD_REQUEST"),
            entry(ROUTES + "ObservabilityRoutes$ObservabilityError#KEY_REQUIRED", "BAD_REQUEST"),
            entry(ROUTES + "ObservabilityRoutes$ObservabilityError#MISSING_FIELDS", "BAD_REQUEST"),
            entry(ROUTES + "ObservabilityRoutes$ObservabilityError#REQUEST_ID_REQUIRED", "BAD_REQUEST"),
            entry(ROUTES + "ScaleRouteError$InstancesBelowFloor", "BAD_REQUEST"),
            entry(ROUTES + "ScaleRouteError$InstancesBelowMinAvailable", "BAD_REQUEST"),
            entry(ROUTES + "ScheduledTaskRoutes$InjectError$General#DEV_MODE_DISABLED", "FORBIDDEN"),
            entry(ROUTES + "ScheduledTaskRoutes$InjectError$General#MISSING_ARTIFACT", "BAD_REQUEST"),
            entry(ROUTES + "ScheduledTaskRoutes$InjectError$General#MISSING_BODY", "BAD_REQUEST"),
            entry(ROUTES + "ScheduledTaskRoutes$InjectError$General#MISSING_METHOD", "BAD_REQUEST"),
            entry(ROUTES + "ScheduledTaskRoutes$InjectError$General#MISSING_SECTION", "BAD_REQUEST"),
            entry(ROUTES + "ScheduledTaskRoutes$InjectError$RunInFlight", "CONFLICT"),
            entry(ROUTES + "ScheduledTaskRoutes$InjectError$SliceNotLocal", "CONFLICT"),
            entry(ROUTES + "ScheduledTaskRoutes$TriggerConflict", "CONFLICT"),
            entry(ROUTES + "SchemaRouteError$InvalidVersionParameter", "BAD_REQUEST"),
            entry(ROUTES + "SchemaRouteError$SchemaAlreadyPending", "CONFLICT"),
            entry(ROUTES + "SchemaRouteError$SchemaAlreadyServing", "CONFLICT"),
            entry(ROUTES + "SchemaRouteError$SchemaNotFailed", "CONFLICT"),
            entry(ROUTES + "SchemaRouteError$SchemaNotLeader", "CONFLICT"),
            entry(ROUTES + "SchemaRouteError$SchemaRecordNotFound", "NOT_FOUND"),
            entry(ROUTES + "SchemaRouteError$SchemaStatusUndecodable", "CONFLICT")));

    @Test
    void everyManagementCauseType_isClassified_withItsPinnedStatus() throws Exception {
        var observed = observed();

        assertThat(observed).as("management cause types and the status each answers").isEqualTo(PINNED);
    }

    /// The table lists exactly the types that do not state their own status (and the coordinator refusal, which lives
    /// outside the management package): no type missing, and no stale row left behind for a type that has since become
    /// `HttpStatusAware`.
    @Test
    void centralTable_listsExactlyTheTypesThatDoNotStateTheirOwnStatus() throws Exception {
        var expected = new java.util.TreeSet<>(List.of("org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator$CoordinatorError#NOT_LEADER"));

        for (var type : causeTypes()) {
            if (!HttpStatusAware.class.isAssignableFrom(type)) {
                expected.addAll(type.isEnum()
                                ? Stream.of(type.getEnumConstants()).map(c -> type.getName() + "#" + ((Enum<?>) c).name()).toList()
                                : List.of(type.getName()));
            }
        }

        assertThat(new java.util.TreeSet<>(ManagementStatus.listedKeys())).isEqualTo(expected);
    }

    @Test
    void everyKeyTheCentralTableLists_stillNamesAnExistingTypeOrConstant() throws Exception {
        for (var key : ManagementStatus.listedKeys()) {
            var type = Class.forName(typeOf(key), false, ManagementStatus.class.getClassLoader());

            assertThat(Cause.class.isAssignableFrom(type)).as("%s is a Cause", key).isTrue();
            if (key.contains("#")) {
                var constant = key.substring(key.indexOf('#') + 1);

                assertThat(type.isEnum()).as("%s names an enum", key).isTrue();
                assertThat(Stream.of(type.getEnumConstants()).map(c -> ((Enum<?>) c).name())).as("constants of %s", type.getName()).contains(constant);
            }
        }
    }

    @Test
    void rawStreamError_reachingTheFunnel_answersItsClassStatus() {
        assertThat(ManagementStatus.statusOf(StreamError.General.BUFFER_FULL)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ManagementStatus.statusOf(StreamError.General.CONSUMER_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void parameterError_answers400() {
        assertThat(ManagementStatus.statusOf(new ParameterError.InvalidParameter("p"))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unclassifiedCause_answers500_andIsReportedOnce() {
        var untyped = Causes.cause("not a classified type");

        assertThat(ManagementStatus.classifiedStatus(untyped).isEmpty()).isTrue();
        assertThat(ManagementStatus.statusOf(untyped)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ManagementStatus.statusOf(untyped)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ManagementStatus.reportedTypes()).contains(untyped.getClass().getName());
    }

    @Test
    void composite_withAnUnclassifiedMember_isUnclassified() {
        var composite = Causes.composite(new ParameterError.InvalidParameter("a").result(), Causes.cause("untyped").result());

        assertThat(ManagementStatus.classifiedStatus(composite).isEmpty()).isTrue();
        assertThat(ManagementStatus.statusOf(composite)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void composite_ofAgreeingMembers_answersTheirStatus() {
        var composite = Causes.composite(new ParameterError.InvalidParameter("a").result(), new ParameterError.InvalidParameter("b").result());

        assertThat(ManagementStatus.statusOf(composite)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static String typeOf(String key) {
        var hash = key.indexOf('#');

        return hash < 0 ? key : key.substring(0, hash);
    }

    private static Map<String, String> observed() throws Exception {
        var observed = new TreeMap<String, String>();

        for (var type : causeTypes()) {
            if (type.isEnum()) {
                for (var constant : type.getEnumConstants()) {
                    observed.put(type.getName() + "#" + ((Enum<?>) constant).name(), statusName((Cause) constant));
                }
            } else {
                observed.put(type.getName(), statusName(instantiate(type)));
            }
        }
        return observed;
    }

    private static String statusName(Cause cause) {
        return ManagementStatus.classifiedStatus(cause).map(HttpStatus::name).or(UNCLASSIFIED);
    }

    private static List<Class<?>> causeTypes() throws IOException, ClassNotFoundException {
        var root = Path.of(ManagementStatus.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        var types = new ArrayList<Class<?>>();

        try (Stream<Path> files = Files.walk(root.resolve(API_PACKAGE))) {
            for (var file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                var name = root.relativize(file).toString().replace('/', '.').replaceAll("\\.class$", "");
                var type = Class.forName(name, false, ManagementStatus.class.getClassLoader());

                if (isConcreteNamedCause(type)) {
                    types.add(type);
                }
            }
        }
        return types;
    }

    private static boolean isConcreteNamedCause(Class<?> type) {
        return Cause.class.isAssignableFrom(type)
               && !type.isInterface()
               && !Modifier.isAbstract(type.getModifiers())
               && !type.isAnonymousClass()
               && !type.isLocalClass()
               && !type.isSynthetic();
    }

    private static Cause instantiate(Class<?> type) throws Exception {
        var constructor = type.getDeclaredConstructors()[0];
        var parameterTypes = constructor.getParameterTypes();
        var args = new Object[parameterTypes.length];

        for (int i = 0; i < args.length; i++) {
            args[i] = defaultFor(parameterTypes[i]);
        }
        constructor.setAccessible(true);
        return (Cause) constructor.newInstance(args);
    }

    private static Object defaultFor(Class<?> type) {
        if (type == int.class || type == long.class || type == short.class || type == byte.class) return 0;
        if (type == boolean.class) return false;
        if (type == double.class || type == float.class) return 0.0;
        if (type == String.class) return "";
        if (type == HttpStatus.class) return HttpStatus.CONFLICT;
        if (type == Cause.class) return Causes.cause("probe");
        if (type == Option.class) return Option.none();
        if (type == List.class) return List.of();
        if (type == Throwable.class) return new RuntimeException("probe");
        return null;
    }
}
