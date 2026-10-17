// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.config;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.config.ProviderBasedConfigService.providerBasedConfigService;

/// #822 — a record nested in an `Option` whose REQUIRED field is missing used to fail the whole enclosing
/// component with `Config section not found: Outer.component`: the nested bind's own `SectionNotFound` was read
/// by the parent as "this section is absent". An operator who wrote the section was told it does not exist. The
/// missing field is now reported as such, with its key. Per-field defaults (`DEFAULT_<COMPONENT>` constants) let
/// a record omit the settings that have a default without giving the whole record one, which would also supply
/// the ones that have none.
class ProviderBasedConfigServiceMissingFieldTest {
    /// `host` has no default and must stay required; `port` has one.
    record Endpoint(String host, int port) {
        public static final int DEFAULT_PORT = 587;
    }

    record Outer(String name, Option<Endpoint> endpoint) {}

    record BareOuter(String name, Endpoint endpoint) {}

    /// A constant of the wrong type is not a default for the component.
    record MistypedDefault(String host, int port) {
        public static final String DEFAULT_PORT = "587";
    }

    @Test
    void nestedOptionRecord_missingRequiredField_namesTheField_notTheSection() {
        var result = serviceWith(Map.of("svc.name", "x", "svc.endpoint.port", "25")).config("svc", Outer.class);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ConfigError.MissingField.class);
            assertThat(cause.message()).contains("svc.endpoint.host")
                      .contains("Endpoint.host")
                      .doesNotContain("section not found");
        });
    }

    @Test
    void nestedOptionRecord_omittedFieldWithADefault_bindsTheDefault() {
        var endpoint = serviceWith(Map.of("svc.name", "x", "svc.endpoint.host", "h")).config("svc", Outer.class)
                                                                                      .unwrap()
                                                                                      .endpoint()
                                                                                      .unwrap();

        assertThat(endpoint.host()).isEqualTo("h");
        assertThat(endpoint.port()).as("DEFAULT_PORT").isEqualTo(587);
    }

    @Test
    void nestedOptionRecord_explicitValueBeatsTheDefault() {
        var endpoint = serviceWith(Map.of("svc.name", "x", "svc.endpoint.host", "h", "svc.endpoint.port", "25")).config("svc",
                                                                                                                      Outer.class)
                                                                                                              .unwrap()
                                                                                                              .endpoint()
                                                                                                              .unwrap();

        assertThat(endpoint.port()).isEqualTo(25);
    }

    /// The control that keeps "no whole-record default" honest: with `host` omitted too, it is still reported.
    @Test
    void nestedOptionRecord_everythingOmitted_stillReportsTheRequiredField() {
        var result = serviceWith(Map.of("svc.name", "x", "svc.endpoint.unrelated", "y")).config("svc", Outer.class);

        result.onFailure(cause -> assertThat(cause.message()).contains("svc.endpoint.host"));
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void bareNestedRecord_missingRequiredField_namesTheField() {
        var result = serviceWith(Map.of("svc.name", "x", "svc.endpoint.port", "25")).config("svc", BareOuter.class);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("svc.endpoint.host").doesNotContain("section not found"));
    }

    @Test
    void topLevelRecord_missingRequiredField_isMissingField_notSectionNotFound() {
        var result = serviceWith(Map.of("svc.port", "25")).config("svc", Endpoint.class);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ConfigError.MissingField.class);
            assertThat(cause.message()).contains("svc.host");
        });
    }

    @Test
    void constantOfTheWrongType_isNotADefault() {
        var result = serviceWith(Map.of("svc.host", "h")).config("svc", MistypedDefault.class);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("svc.port"));
    }

    /// Controls: absence keeps its meaning. An absent section is still `SectionNotFound`, and an absent
    /// `Option<Record>` section is still `none()`.
    @Test
    void absentSection_isStillSectionNotFound_andAbsentOptionRecordIsStillNone() {
        var absent = serviceWith(Map.of("other.key", "v")).config("svc", Outer.class);
        var noEndpoint = serviceWith(Map.of("svc.name", "x")).config("svc", Outer.class);

        assertThat(absent.isFailure()).isTrue();
        absent.onFailure(cause -> assertThat(cause).isInstanceOf(ConfigError.SectionNotFound.class));
        assertThat(noEndpoint.unwrap().endpoint()).isEqualTo(Option.none());
    }

    /// Round 3: a path that holds a SCALAR where a record (or `Option<Record>`) is expected is an operator error,
    /// not an absent section. `hasSection` answers true for a scalar key, so the binder used to try to bind a record
    /// there and fail on its first required field; treating the scalar as absent instead (round 2) silently bound the
    /// record's default or `none()` and dropped what was written (`json = "snake_case"` where a table was expected).
    /// It is now a typed `TypeMismatch` naming the key and saying a table was expected; the value is not echoed.
    record Retention(String kind, int maxCount) {}

    /// Shaped like `StreamConfig`: a whole-record `DEFAULT` that supplies the nested record, which has none of its own.
    record Stream(String name, Retention retention) {
        public static final Stream DEFAULT = new Stream("d", new Retention("count", 100));
    }

    record OptionalRetentionHolder(String name, Option<Endpoint> endpoint) {}

    @Test
    void scalarKeyWhereABareRecordIsExpected_isATypeMismatch_notTheDefault() {
        var result = serviceWith(Map.of("s.name", "x", "s.retention", "time")).config("s", Stream.class);

        assertThat(result.isFailure()).as("a scalar where a table is expected must not bind the default").isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ConfigError.TypeMismatch.class);
            assertThat(cause.message()).contains("s.retention").contains("table").doesNotContain("time");
        });
    }

    @Test
    void scalarKeyWhereAnOptionRecordIsExpected_isATypeMismatch_notNone() {
        var result = serviceWith(Map.of("s.name", "x", "s.endpoint", "s3cretValue")).config("s", OptionalRetentionHolder.class);

        assertThat(result.isFailure()).as("a scalar where an Option<Record> table is expected must not bind none()").isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ConfigError.TypeMismatch.class);
            assertThat(cause.message()).contains("s.endpoint").contains("table").doesNotContain("s3cretValue");
        });
    }

    /// The control: a real section under the same name still binds.
    @Test
    void realSectionWhereARecordIsExpected_stillBinds() {
        var stream = serviceWith(Map.of("s.name", "x", "s.retention.kind", "time", "s.retention.max_count", "5")).config("s", Stream.class).unwrap();

        assertThat(stream.retention()).isEqualTo(new Retention("time", 5));
    }

    /// A record WITH a whole-record `DEFAULT` whose nested section IS present but partial. The nested record's
    /// missing field used to be read as absence, so the outer `DEFAULT`'s component silently replaced the section
    /// the operator wrote (`StreamConfig.retention` is the production instance: `[streams.x.retention]` with only
    /// `max_count` bound `RetentionPolicy` defaults and dropped the `5`). It now fails naming the field.
    record DefaultedOuter(String name, Endpoint endpoint) {
        public static final DefaultedOuter DEFAULT = new DefaultedOuter("d", new Endpoint("default-host", 1));
    }

    record DefaultedOptionOuter(String name, Option<Endpoint> endpoint) {
        public static final DefaultedOptionOuter DEFAULT = new DefaultedOptionOuter("d", Option.none());
    }

    @Test
    void partialNestedSection_underAnOuterDefault_failsNamingTheField_insteadOfBindingTheDefault() {
        var result = serviceWith(Map.of("svc.name", "x", "svc.endpoint.port", "25")).config("svc", DefaultedOuter.class);

        assertThat(result.isFailure()).as("the outer DEFAULT must not stand in for a section the operator wrote")
                                      .isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ConfigError.MissingField.class));
    }

    @Test
    void partialNestedOptionSection_underAnOuterDefault_failsNamingTheField_insteadOfBindingNone() {
        var result = serviceWith(Map.of("svc.name", "x", "svc.endpoint.port", "25")).config("svc",
                                                                                          DefaultedOptionOuter.class);

        assertThat(result.isFailure()).as("the outer DEFAULT's none() must not stand in for a section the operator wrote")
                                      .isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ConfigError.MissingField.class));
    }

    private static ProviderBasedConfigService serviceWith(Map<String, String> values) {
        var source = MapConfigSource.mapConfigSource("test-source", values).unwrap();

        return providerBasedConfigService(ConfigurationProvider.configurationProvider(source));
    }
}
