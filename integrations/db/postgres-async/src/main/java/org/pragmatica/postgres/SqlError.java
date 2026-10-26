package org.pragmatica.postgres;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.SqlStates;


@SuppressWarnings("unused")
public sealed interface SqlError extends Cause {
    record ConfigurationError(String message) implements SqlError {}

    record ChannelClosed(String message) implements SqlError, Cause.Transient {}

    record SimultaneousUseDetected(String message) implements SqlError {}

    record ConnectionPoolClosed(String message) implements SqlError {}

    record PoolExhausted(String message) implements SqlError, Cause.Transient {}

    record LogicalConnectionClosed(String message) implements SqlError {}

    record BadAuthenticationSequence(String message) implements SqlError {}

    record CommunicationError(String message) implements SqlError, Cause.Transient {}

    record InvalidChannelName(String message) implements SqlError {}

    record NoResultsReturned(String message) implements SqlError {}

    record TooManyResultsReturned(String message) implements SqlError {}

    record ColumnNotFound(String message) implements SqlError {}

    record InconvertibleColumnType(String message) implements SqlError {}

    record UnableToConvertColumnValue(String message) implements SqlError {}

    record ServerResponse(String code, String level, String message) {}

    @SuppressWarnings("unused")
    sealed interface ServerError extends SqlError {
        ServerResponse response();
        String readableCode();

        default String message() {
            return response().level() + ": SQLSTATE=" + response().code() + ", MESSAGE=" + response().message();
        }
    }

    record ServerWarning(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorNoData(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorSQLStatementNotYetComplete(ServerResponse response, String readableCode) implements ServerError {}

    /// Transience is decided PER SQLSTATE by the one classifier every database family shares, [SqlStates], not per
    /// class (#280, review of #1088 S8): a class-wide mark would admit `08007 transaction_resolution_unknown` - a COMMIT
    /// whose reply was lost, which a retry would re-run - and `08P01 protocol_violation`. `08003 connection_does_not_exist`
    /// is retryable: no statement ran, so a fresh connection is safe.
    record ServerConnectionException(ServerResponse response, String readableCode) implements ServerError {
        @Override
        public boolean isTransient() {
            return SqlStates.isTransient(response.code());
        }
    }

    record ServerTriggeredActionException(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorFeatureNotSupported(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorInvalidTransactionInitiation(ServerResponse response, String readableCode) implements ServerError {}

    record ServerLocatorException(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorInvalidGrantor(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorInvalidRoleSpecification(ServerResponse response, String readableCode) implements ServerError {}

    record ServerDiagnosticsException(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorCaseNotFound(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorCardinalityViolation(ServerResponse response, String readableCode) implements ServerError {}

    record ServerDataException(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorIntegrityConstraintViolation(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorInvalidCursorState(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorInvalidTransactionState(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorInvalidSQLStatementName(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorTriggeredDataChangeViolation(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorInvalidAuthorizationSpecification(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorDependentPrivilegeDescriptorsStillExist(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorInvalidTransactionTermination(ServerResponse response, String readableCode) implements ServerError {}

    record ServerSQLRoutineException(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorInvalidCursorName(ServerResponse response, String readableCode) implements ServerError {}

    record ServerExternalRoutineException(ServerResponse response, String readableCode) implements ServerError {}

    record ServerExternalRoutineInvocationException(ServerResponse response, String readableCode) implements ServerError {}

    record ServerSavepointException(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorInvalidCatalogName(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorInvalidSchemaName(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorTransactionRollback(ServerResponse response, String readableCode) implements ServerError, Cause.Transient {}

    record ServerSyntaxErrorOrAccessRuleViolation(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorWithCheckOptionViolation(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorInsufficientResources(ServerResponse response, String readableCode) implements ServerError, Cause.Transient {}

    record ServerErrorProgramLimitExceeded(ServerResponse response, String readableCode) implements ServerError {}

    record ServerErrorObjectNotInPrerequisiteState(ServerResponse response, String readableCode) implements ServerError {}

    /// `57P01 admin_shutdown`, `57P02 crash_shutdown`, `57P03 cannot_connect_now` pass; `57P04
    /// database_dropped` does not, and `57014 query_canceled` - a statement_timeout or an
    /// explicit cancel - is ruled NOT transient: a cancel should surface, not be re-driven. Decided by [SqlStates].
    record ServerErrorOperatorIntervention(ServerResponse response, String readableCode) implements ServerError {
        @Override
        public boolean isTransient() {
            return SqlStates.isTransient(response.code());
        }
    }

    record ServerSystemError(ServerResponse response, String readableCode) implements ServerError {}

    record ServerSnapshotFailure(ServerResponse response, String readableCode) implements ServerError {}

    record ServerConfigurationFileError(ServerResponse response, String readableCode) implements ServerError {}

    record ServerForeignDataWrapperError(ServerResponse response, String readableCode) implements ServerError {}

    record ServerPlPgSQLError(ServerResponse response, String readableCode) implements ServerError {}

    record ServerInternalError(ServerResponse response, String readableCode) implements ServerError {}

    record ServerUnknownError(ServerResponse response, String readableCode) implements ServerError {}

    static SqlError fromThrowable(Throwable th) {
        return new CommunicationError(th.getMessage());
    }
}
