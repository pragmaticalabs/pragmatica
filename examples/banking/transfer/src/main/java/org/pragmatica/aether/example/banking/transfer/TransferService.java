package org.pragmatica.aether.example.banking.transfer;

import org.pragmatica.aether.example.banking.account.AccountService;
import org.pragmatica.aether.example.banking.exchange.ExchangeRateService;
import org.pragmatica.aether.example.banking.fraud.FraudDetectionService;
import org.pragmatica.aether.example.banking.shared.Account;
import org.pragmatica.aether.example.banking.shared.AccountId;
import org.pragmatica.aether.example.banking.shared.Money;
import org.pragmatica.aether.example.banking.shared.TransferId;
import org.pragmatica.aether.example.banking.shared.TransferReceipt;
import org.pragmatica.aether.example.banking.shared.TransferStatus;
import org.pragmatica.aether.example.banking.shared.TransferSummary;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Transfer orchestrator coordinating accounts, exchange rates, and fraud detection.
///
/// Demonstrates:
///   - 1-param method: getStatus
///   - 2-param method: recentTransfers
///   - 3-param method: transfer
///   - 3 slice dependencies: AccountService, ExchangeRateService, FraudDetectionService
///   - Fork-Join: parallel account validation via Promise.all
///   - Compensation: re-credit on credit failure after successful debit
@Slice
public interface TransferService {

    // === Errors ===
    sealed interface TransferError extends Cause {
        record AccountNotActive(AccountId accountId) implements TransferError {
            @Override
            public String message() {
                return "Account is not active: " + accountId.value();
            }
        }

        record FraudBlocked(String reason) implements TransferError {
            @Override
            public String message() {
                return "Transfer blocked by fraud detection: " + reason;
            }
        }

        record TransferNotFound(TransferId transferId) implements TransferError {
            @Override
            public String message() {
                return "Transfer not found: " + transferId.value();
            }
        }
    }

    // === Context Records for Pipeline ===
    record ValidatedAccounts(Account source, Account destination) {}

    record TransferContext(TransferId transferId,
                           ValidatedAccounts accounts,
                           Money sourceAmount,
                           Money destinationAmount) {}

    // === Operations ===

    /// Execute a transfer between two accounts. 3-param method.
    Promise<TransferReceipt> transfer(AccountId from, AccountId to, Money amount);

    /// Get the status of a transfer. 1-param method.
    Promise<TransferStatus> getStatus(TransferId transferId);

    /// Get recent transfers for an account. 2-param method.
    Promise<List<TransferSummary>> recentTransfers(AccountId accountId, int limit);

    // === Factory ===
    static TransferService transferService(AccountService accounts,
                                           ExchangeRateService exchange,
                                           FraudDetectionService fraud) {
        return new transferService(accounts, exchange, fraud);
    }

    record transferService(AccountService accounts,
                           ExchangeRateService exchange,
                           FraudDetectionService fraud) implements TransferService {

        private static final Map<TransferId, TransferSummary> TRANSFERS = new ConcurrentHashMap<>();

        @Override
        public Promise<TransferReceipt> transfer(AccountId from, AccountId to, Money amount) {
            var transferId = TransferId.generate();

            // Step 1: Fork-Join — parallel account validation
            return Promise.all(accounts.getAccount(from), accounts.getAccount(to))
                          .flatMap((sourceAccount, destAccount) -> validateAccounts(sourceAccount, destAccount))

                          // Step 2: Sequencer — fraud risk check
                          .flatMap(validated -> assessRisk(from, to, amount).map(_ -> validated))

                          // Step 3: Condition — cross-currency conversion
                          .flatMap(validated -> resolveAmount(validated, amount)
                              .map(destAmount -> new TransferContext(transferId, validated, amount, destAmount)))

                          // Step 4: Sequencer — debit source
                          .flatMap(ctx -> accounts.debit(ctx.accounts().source().id(), ctx.sourceAmount())
                                                  .map(_ -> ctx))

                          // Step 5: Sequencer + Compensation — credit destination
                          .flatMap(ctx -> accounts.credit(ctx.accounts().destination().id(), ctx.destinationAmount())
                                                  .onFailure(_ -> compensateDebit(ctx))
                                                  .map(_ -> ctx))

                          // Step 6: Leaf — build receipt
                          .map(ctx -> buildReceipt(ctx));
        }

        @Override
        public Promise<TransferStatus> getStatus(TransferId transferId) {
            var summary = TRANSFERS.get(transferId);
            return summary != null
                   ? Promise.success(summary.status())
                   : new TransferError.TransferNotFound(transferId).promise();
        }

        @Override
        public Promise<List<TransferSummary>> recentTransfers(AccountId accountId, int limit) {
            var matching = TRANSFERS.values()
                                    .stream()
                                    .filter(t -> t.from().equals(accountId) || t.to().equals(accountId))
                                    .sorted((a, b) -> b.timestamp().compareTo(a.timestamp()))
                                    .limit(limit)
                                    .toList();
            return Promise.success(matching);
        }

        private Promise<ValidatedAccounts> validateAccounts(Account source, Account destination) {
            if (!source.isActive()) {
                return new TransferError.AccountNotActive(source.id()).promise();
            }
            if (!destination.isActive()) {
                return new TransferError.AccountNotActive(destination.id()).promise();
            }
            return Promise.success(new ValidatedAccounts(source, destination));
        }

        private Promise<Void> assessRisk(AccountId from, AccountId to, Money amount) {
            return fraud.assessTransfer(from, to, amount)
                        .flatMap(assessment -> assessment.isAcceptable()
                                               ? Promise.success(null)
                                               : new TransferError.FraudBlocked(assessment.reason()).promise());
        }

        private Promise<Money> resolveAmount(ValidatedAccounts validated, Money amount) {
            var sourceCurrency = validated.source().currency();
            var destCurrency = validated.destination().currency();

            if (sourceCurrency.equals(destCurrency)) {
                return Promise.success(amount);
            }
            return exchange.convert(amount, destCurrency);
        }

        private void compensateDebit(TransferContext ctx) {
            accounts.credit(ctx.accounts().source().id(), ctx.sourceAmount());
            recordTransfer(ctx, TransferStatus.COMPENSATED);
        }

        private TransferReceipt buildReceipt(TransferContext ctx) {
            recordTransfer(ctx, TransferStatus.COMPLETED);
            return TransferReceipt.transferReceipt(ctx.transferId(),
                                                    ctx.accounts().source().id(),
                                                    ctx.accounts().destination().id(),
                                                    ctx.sourceAmount(),
                                                    ctx.destinationAmount());
        }

        private void recordTransfer(TransferContext ctx, TransferStatus status) {
            var summary = TransferSummary.transferSummary(ctx.transferId(),
                                                          ctx.accounts().source().id(),
                                                          ctx.accounts().destination().id(),
                                                          ctx.sourceAmount(),
                                                          status,
                                                          Instant.now());
            TRANSFERS.put(ctx.transferId(), summary);
        }
    }
}
