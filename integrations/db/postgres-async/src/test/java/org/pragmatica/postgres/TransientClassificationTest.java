// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.postgres;

import org.pragmatica.lang.Cause;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #280: the retry interceptor's default policy retries only causes that declare themselves
/// transient (`Cause.isTransient()`), so a transient infrastructure failure that is NOT marked is
/// silently never retried. This pins this module's classification — removing a `Cause.Transient`
/// reddens the row for it, and the unclassified rows guard against over-marking.
class TransientClassificationTest {
    @Test
    void transientCauses_areClassifiedTransient() {
        Cause[] transientCauses = {
            new SqlError.ChannelClosed("closed"),
            new SqlError.PoolExhausted("full"),
            new SqlError.CommunicationError("reset"),
            new SqlError.ServerErrorTransactionRollback(new SqlError.ServerResponse("40001", "ERROR", "could not serialize access"), "serialization_failure"),
            new SqlError.ServerConnectionException(new SqlError.ServerResponse("08006", "FATAL", "connection failure"), "connection_failure"),
            new SqlError.ServerConnectionException(new SqlError.ServerResponse("08001", "FATAL", "unable to connect"), "sqlclient_unable_to_establish_sqlconnection"),
            new SqlError.ServerConnectionException(new SqlError.ServerResponse("08004", "FATAL", "rejected"), "sqlserver_rejected_establishment_of_sqlconnection"),
            new SqlError.ServerErrorOperatorIntervention(new SqlError.ServerResponse("57P01", "FATAL", "terminating connection due to administrator command"), "admin_shutdown"),
            new SqlError.ServerErrorOperatorIntervention(new SqlError.ServerResponse("57P02", "FATAL", "crash"), "crash_shutdown"),
            new SqlError.ServerErrorInsufficientResources(new SqlError.ServerResponse("53300", "FATAL", "too many connections"), "too_many_connections"),
            new SqlError.ServerErrorOperatorIntervention(new SqlError.ServerResponse("57P03", "FATAL", "the database system is starting up"), "cannot_connect_now"),
        };

        for (var cause : transientCauses) {
            assertThat(cause.isTransient()).as(cause.getClass().getName()).isTrue();
            assertThat(cause.isTerminal()).as(cause.getClass().getName() + " is not also terminal").isFalse();
        }
    }

    @Test
    void unclassifiedCauses_stayUnclassified() {
        Cause[] unclassified = {
            new SqlError.ConfigurationError("bad"),
            new SqlError.NoResultsReturned("none"),
            new SqlError.ConnectionPoolClosed("closed"),
            new SqlError.ServerDataException(new SqlError.ServerResponse("22012", "ERROR", "division by zero"), "division_by_zero"),
            // 08007: a COMMIT whose reply was lost — retrying re-runs a possibly committed write
            new SqlError.ServerConnectionException(new SqlError.ServerResponse("08007", "FATAL", "transaction resolution unknown"), "transaction_resolution_unknown"),
            new SqlError.ServerConnectionException(new SqlError.ServerResponse("08P01", "FATAL", "protocol violation"), "protocol_violation"),
            new SqlError.ServerErrorOperatorIntervention(new SqlError.ServerResponse("57P04", "FATAL", "database dropped"), "database_dropped"),
            // 57014: a statement_timeout or explicit cancel surfaces, it is not re-driven (CTO ruling on #1088 S8)
            new SqlError.ServerErrorOperatorIntervention(new SqlError.ServerResponse("57014", "ERROR", "canceling statement due to statement timeout"), "query_canceled"),
        };

        for (var cause : unclassified) {
            assertThat(cause.isTransient()).as(cause.getClass().getName()).isFalse();
        }
    }
}
