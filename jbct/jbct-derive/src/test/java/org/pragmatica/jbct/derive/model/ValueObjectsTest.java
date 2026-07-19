package org.pragmatica.jbct.derive.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// Parse-don't-validate tests for the sheet value objects: valid raw forms construct typed values;
/// invalid forms fail rather than producing a half-built object.
class ValueObjectsTest {
    @Test
    void scope_parses_operationKind() {
        Scope.scope("operation:submit-filing")
             .onFailure(cause -> fail("unexpected: " + cause.message()))
             .onSuccess(scope -> {
                 assertThat(scope.kind()).isEqualTo(ScopeKind.OPERATION);
                 assertThat(scope.name()).isEqualTo("submit-filing");
             });
    }

    @Test
    void scope_parses_dataClassKind() {
        Scope.scope("data-class:filings")
             .onFailure(cause -> fail("unexpected: " + cause.message()))
             .onSuccess(scope -> {
                 assertThat(scope.kind()).isEqualTo(ScopeKind.DATA_CLASS);
                 assertThat(scope.name()).isEqualTo("filings");
             });
    }

    @Test
    void scope_parses_systemAsBare() {
        Scope.scope("system")
             .onFailure(cause -> fail("unexpected: " + cause.message()))
             .onSuccess(scope -> assertThat(scope.isSystem()).isTrue());
    }

    @Test
    void scope_fails_forUnknownPrefix() {
        assertThat(Scope.scope("weird:x").isFailure()).isTrue();
    }

    @Test
    void scope_fails_forMissingColon() {
        assertThat(Scope.scope("nocolon").isFailure()).isTrue();
    }

    @Test
    void scope_fails_forEmptyName() {
        assertThat(Scope.scope("operation:").isFailure()).isTrue();
    }

    @Test
    void rowStatus_parses_answered() {
        RowStatus.rowStatus("answered")
                 .onFailure(cause -> fail("unexpected: " + cause.message()))
                 .onSuccess(status -> assertThat(status).isEqualTo(RowStatus.ANSWERED));
    }

    @Test
    void rowStatus_parses_unknownCaseInsensitively() {
        RowStatus.rowStatus("UNKNOWN")
                 .onFailure(cause -> fail("unexpected: " + cause.message()))
                 .onSuccess(status -> assertThat(status).isEqualTo(RowStatus.UNKNOWN));
    }

    @Test
    void rowStatus_fails_forGarbage() {
        assertThat(RowStatus.rowStatus("maybe").isFailure()).isTrue();
    }

    @Test
    void mode_parses_greenfieldAndLiving() {
        assertThat(Mode.mode("greenfield").isSuccess()).isTrue();
        assertThat(Mode.mode("living").isSuccess()).isTrue();
    }

    @Test
    void mode_fails_forGarbage() {
        assertThat(Mode.mode("hybrid").isFailure()).isTrue();
    }
}
