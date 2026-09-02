package org.pragmatica.aether.example.banking.account;

import java.math.BigDecimal;
import java.time.Instant;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Compile-time validated persistence for the account slice.
///
/// Every statement here is checked against `schema/V001__create_tables.sql` by the `@PgSql`
/// annotation processor: table names, column names, `:param` names and parameter types must all
/// resolve, so a typo is a compilation error rather than a production incident.
///
/// `creditBalance` and `debitBalance` are the shapes worth copying. The guard lives in the `WHERE`
/// clause and `RETURNING` reports whether a row matched, which makes the balance change atomic --
/// there is no read-modify-write window for a concurrent transfer to slip through. An empty
/// [Option] means the guard rejected the update, and the slice turns that back into a typed
/// `AccountService.AccountError`.
///
/// Parameters are declared in the order their `:name` first appears in the SQL, because that is the
/// order the generated factory binds them in.
///
/// NOT demonstrated: a multi-statement transaction. `insertAccount` and `insertZeroBalance` are two
/// statements because the processor rejects data-modifying CTEs, and `@PgSql` does not surface the
/// `SqlConnector.transactional` entry point, so a crash between them leaves an account with no
/// balance row. Production code would open a transaction around the pair.
@PgSql
public interface AccountPersistence {
    record AccountRow(String accountId,
                      String holderName,
                      String email,
                      String currency,
                      String status,
                      Instant createdAt) {}

    record BalanceRow(String accountId, BigDecimal amount, BigDecimal pending, String currency) {}

    @Query("INSERT INTO accounts (account_id, holder_name, email, currency, status) "
          + "VALUES (:accountId, :holderName, :email, :currency, :status) "
          + "RETURNING account_id, holder_name, email, currency, status, created_at")
    Promise<AccountRow> insertAccount(String accountId,
                                      String holderName,
                                      String email,
                                      String currency,
                                      String status);

    @Query("INSERT INTO balances (account_id, amount, pending, currency) VALUES (:accountId, 0, 0, :currency)")
    Promise<Unit> insertZeroBalance(String accountId, String currency);

    @Query("SELECT account_id, holder_name, email, currency, status, created_at "
          + "FROM accounts WHERE account_id = :accountId")
    Promise<Option<AccountRow>> findAccount(String accountId);

    @Query("SELECT account_id, amount, pending, currency FROM balances WHERE account_id = :accountId")
    Promise<Option<BalanceRow>> findBalance(String accountId);

    @Query("UPDATE accounts SET status = :status WHERE account_id = :accountId "
          + "RETURNING account_id, holder_name, email, currency, status, created_at")
    Promise<Option<AccountRow>> updateStatus(String status, String accountId);

    @Query("UPDATE balances SET amount = amount + :amount, updated_at = now() "
          + "WHERE account_id = :accountId AND currency = :currency "
          + "RETURNING account_id, amount, pending, currency")
    Promise<Option<BalanceRow>> creditBalance(BigDecimal amount, String accountId, String currency);

    @Query("UPDATE balances SET amount = amount - :amount, updated_at = now() "
          + "WHERE account_id = :accountId AND currency = :currency AND amount >= :amount "
          + "RETURNING account_id, amount, pending, currency")
    Promise<Option<BalanceRow>> debitBalance(BigDecimal amount, String accountId, String currency);
}
