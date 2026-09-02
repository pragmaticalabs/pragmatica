package org.pragmatica.aether.example.banking.transfer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// Transfer orchestrator coordinating accounts, exchange rates, and fraud detection.
///
/// Demonstrates:
///   - 1-param method: getStatus
///   - 2-param method: recentTransfers
///   - 3-param method: transfer
///   - 3 slice dependencies: AccountService, ExchangeRateService, FraudDetectionService
///   - Fork-Join: parallel account validation via Promise.all
///   - Compensation (BER): the compensating credit is composed into the chain, so the transfer does
///     not resolve until the compensation has finished and its outcome decides the recorded status
///
/// Does NOT demonstrate: durable compensation. The saga lives entirely in one in-process promise
/// chain and the history lives in a map, so a node that dies mid-transfer leaves money debited with
/// nothing recording it. A production saga needs a persisted log or a stream-backed outbox to drive
/// the compensation on restart -- see [#compensateDebit] for the exact guarantee this version earns.
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
        return new transferService(accounts, exchange, fraud, new ConcurrentHashMap<>());
    }

    record transferService(AccountService accounts,
                           ExchangeRateService exchange,
                           FraudDetectionService fraud,
                           Map<TransferId, TransferSummary> transfers) implements TransferService {
        @Override
        public Promise<TransferReceipt> transfer(AccountId from, AccountId to, Money amount) {
            var transferId = TransferId.generate();
            // Step 1: Fork-Join — parallel account validation
            return Promise.all(accounts.getAccount(from),
                               accounts.getAccount(to))
                          .flatMap((sourceAccount, destAccount) -> validateAccounts(sourceAccount, destAccount))
                          // Step 2: Sequencer — fraud risk check
                          .flatMap(validated -> assessRisk(from, to, amount).map(_ -> validated))
                          // Step 3: Condition — cross-currency conversion
                          .flatMap(validated -> resolveAmount(validated, amount).map(destAmount -> new TransferContext(transferId,
                                                                                                                       validated,
                                                                                                                       amount,
                                                                                                                       destAmount)))
                          // Step 4: Sequencer — debit source
                          .flatMap(ctx -> accounts.debit(ctx.accounts().source().id(),
                                                         ctx.sourceAmount())
                                                  .map(_ -> ctx))
                          // Step 5: Sequencer + Compensation — credit destination
                          .flatMap(this::creditDestination)
                          // Step 6: Leaf — build receipt
                          .map(ctx -> buildReceipt(ctx));
        }

        @Override
        public Promise<TransferStatus> getStatus(TransferId transferId) {
            return option(transfers.get(transferId)).map(TransferSummary::status)
                         .async(new TransferError.TransferNotFound(transferId));
        }

        @Override
        public Promise<List<TransferSummary>> recentTransfers(AccountId accountId, int limit) {
            var matching = transfers.values()
                                    .stream()
                                    .filter(t -> t.from()
                                                  .equals(accountId) || t.to()
                                                                         .equals(accountId))
                                    .sorted((a, b) -> b.timestamp()
                                                       .compareTo(a.timestamp()))
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

        private Promise<Unit> assessRisk(AccountId from, AccountId to, Money amount) {
            return fraud.assessTransfer(from, to, amount)
                        .flatMap(assessment -> assessment.isAcceptable()
                                               ? Promise.unitPromise()
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

        /// Credit the destination, and on failure run the compensation BEFORE the chain resolves.
        ///
        /// `onFailure` cannot express this. It is an independent side effect: it is started on
        /// resolution and never awaited, so the transfer would resolve while the compensating credit
        /// was still in flight, and its outcome could not influence anything. `fold` is Promise's
        /// error-path branch -- the primitive `flatMap` itself is built from -- and it is the only
        /// combinator here that lets the failure path perform further asynchronous work.
        private Promise<TransferContext> creditDestination(TransferContext ctx) {
            return accounts.credit(ctx.accounts().destination().id(),
                                   ctx.destinationAmount())
                           .fold(credit -> settleCredit(ctx, credit));
        }

        private Promise<TransferContext> settleCredit(TransferContext ctx, Result<Unit> credit) {
            return credit.fold(cause -> compensateDebit(ctx, cause), _ -> Promise.success(ctx));
        }

        /// BER -- compensate-by-inverse. The debit already moved money out of the source account, so
        /// the only correct answer to a failed credit is the inverse credit back to the source.
        ///
        /// Guarantee earned: a reader of the transfer history can tell whether the money came back.
        /// COMPENSATED is recorded only when the compensating credit actually succeeded, and
        /// COMPENSATION_FAILED (with both causes in the summary) when it did not.
        ///
        /// Mechanism: one in-process compensating call -- no retry, no outbox, no durable log. If
        /// this node dies between the debit and this credit, the money stays debited and nothing
        /// records that. Making the compensation survive a crash is the durable outbox this example
        /// deliberately omits.
        private Promise<TransferContext> compensateDebit(TransferContext ctx, Cause creditFailure) {
            return accounts.credit(ctx.accounts().source().id(),
                                   ctx.sourceAmount())
                           .fold(compensation -> recordCompensation(ctx, creditFailure, compensation));
        }

        private Promise<TransferContext> recordCompensation(TransferContext ctx,
                                                            Cause creditFailure,
                                                            Result<Unit> compensation) {
            return compensation.fold(compensationFailure -> recordAndFail(ctx,
                                                                          TransferStatus.COMPENSATION_FAILED,
                                                                          some(compensationDetail(creditFailure,
                                                                                                  compensationFailure)),
                                                                          creditFailure),
                                     _ -> recordAndFail(ctx, TransferStatus.COMPENSATED, none(), creditFailure));
        }

        /// Records the outcome and then re-raises the ORIGINAL credit failure. The caller asked for a
        /// transfer, so what it needs back is why the transfer failed, not why the cleanup failed;
        /// the cleanup's own fate lives in the recorded summary instead.
        private Promise<TransferContext> recordAndFail(TransferContext ctx,
                                                       TransferStatus status,
                                                       Option<String> failureDetail,
                                                       Cause failure) {
            transfers.put(ctx.transferId(), summaryOf(ctx, status, failureDetail));

            return failure.promise();
        }

        private static String compensationDetail(Cause creditFailure, Cause compensationFailure) {
            return "credit failed: " + creditFailure.message()
                 + "; compensating credit ALSO failed: " + compensationFailure.message();
        }

        private TransferReceipt buildReceipt(TransferContext ctx) {
            transfers.put(ctx.transferId(), summaryOf(ctx, TransferStatus.COMPLETED, none()));

            return TransferReceipt.transferReceipt(ctx.transferId(),
                                                   ctx.accounts().source().id(),
                                                   ctx.accounts().destination().id(),
                                                   ctx.sourceAmount(),
                                                   ctx.destinationAmount());
        }

        private static TransferSummary summaryOf(TransferContext ctx,
                                                 TransferStatus status,
                                                 Option<String> failureDetail) {
            return TransferSummary.transferSummary(ctx.transferId(),
                                                   ctx.accounts().source().id(),
                                                   ctx.accounts().destination().id(),
                                                   ctx.sourceAmount(),
                                                   status,
                                                   Instant.now(),
                                                   failureDetail);
        }
    }
}
