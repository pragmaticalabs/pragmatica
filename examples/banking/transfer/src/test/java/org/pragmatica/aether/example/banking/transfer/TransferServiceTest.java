package org.pragmatica.aether.example.banking.transfer;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.example.banking.exchange.ExchangeRateService;
import org.pragmatica.aether.example.banking.fraud.FraudDetectionService;
import org.pragmatica.aether.example.banking.shared.AccountId;
import org.pragmatica.aether.example.banking.shared.Currency;
import org.pragmatica.aether.example.banking.shared.TransferStatus;
import org.pragmatica.aether.example.banking.shared.TransferSummary;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.example.banking.transfer.StubAccountService.usd;


/// Saga tests for [TransferService], with the real exchange and fraud slices and a stubbed
/// [AccountService].
///
/// The [Compensation] block is the mutation check for issue #606: it drives the credit leg into
/// failure and asserts on the status that gets RECORDED. The previous implementation fired the
/// compensating credit and forgot it, so it recorded COMPENSATED unconditionally -- these tests fail
/// against that version and pass against the composed one.
class TransferServiceTest {
    private static final Cause DESTINATION_CREDIT_REJECTED = Causes.cause("destination credit rejected");
    private static final Cause COMPENSATING_CREDIT_REJECTED = Causes.cause("compensating credit rejected");
    private static final Cause DEBIT_REJECTED = Causes.cause("debit rejected");

    private StubAccountService accounts;
    private TransferService transferService;
    private AccountId source;
    private AccountId destination;

    @BeforeEach
    void setup() {
        accounts = StubAccountService.stubAccountService();
        source = accounts.register("Alice", Currency.USD);
        destination = accounts.register("Bob", Currency.USD);
        transferService = TransferService.transferService(accounts,
                                                          ExchangeRateService.exchangeRateService(),
                                                          FraudDetectionService.fraudDetectionService());
    }

    @Nested
    class HappyPath {
        @Test
        void transfer_succeeds_forActiveAccounts() {
            transferService.transfer(source, destination, usd("100.00"))
                           .await()
                           .onFailureRun(() -> fail("Expected success"))
                           .onSuccess(receipt -> {
                               assertThat(receipt.sourceAccountId()).isEqualTo(source);
                               assertThat(receipt.destinationAccountId()).isEqualTo(destination);
                               assertThat(receipt.status()).isEqualTo(TransferStatus.COMPLETED);
                           });
        }

        @Test
        void transfer_recordsCompleted_onSuccess() {
            transferService.transfer(source, destination, usd("100.00")).await();

            assertThat(latestStatus()).isEqualTo(TransferStatus.COMPLETED);
        }

        @Test
        void getStatus_returnsCompleted_forRecordedTransfer() {
            var receipt = transferService.transfer(source, destination, usd("100.00"))
                                         .await()
                                         .unwrap();

            transferService.getStatus(receipt.transferId())
                           .await()
                           .onFailureRun(() -> fail("Expected success"))
                           .onSuccess(status -> assertThat(status).isEqualTo(TransferStatus.COMPLETED));
        }
    }

    @Nested
    class Compensation {
        @Test
        void transfer_recordsCompensated_whenCompensatingCreditSucceeds() {
            accounts.failCreditFor(destination, DESTINATION_CREDIT_REJECTED);

            transferService.transfer(source, destination, usd("100.00")).await();

            assertThat(latestStatus()).isEqualTo(TransferStatus.COMPENSATED);
        }

        @Test
        void transfer_recordsCompensationFailed_whenCompensatingCreditFails() {
            accounts.failCreditFor(destination, DESTINATION_CREDIT_REJECTED);
            accounts.failCreditFor(source, COMPENSATING_CREDIT_REJECTED);

            transferService.transfer(source, destination, usd("100.00")).await();

            assertThat(latestStatus()).isEqualTo(TransferStatus.COMPENSATION_FAILED);
        }

        @Test
        void transfer_doesNotRecordCompensated_whenCompensatingCreditFails() {
            accounts.failCreditFor(destination, DESTINATION_CREDIT_REJECTED);
            accounts.failCreditFor(source, COMPENSATING_CREDIT_REJECTED);

            transferService.transfer(source, destination, usd("100.00")).await();

            assertThat(latestStatus()).isNotEqualTo(TransferStatus.COMPENSATED);
        }

        @Test
        void transfer_recordsBothCauses_whenCompensatingCreditFails() {
            accounts.failCreditFor(destination, DESTINATION_CREDIT_REJECTED);
            accounts.failCreditFor(source, COMPENSATING_CREDIT_REJECTED);

            transferService.transfer(source, destination, usd("100.00")).await();

            assertThat(latestSummary().failureDetail()
                                      .or("")).contains(DESTINATION_CREDIT_REJECTED.message())
                                              .contains(COMPENSATING_CREDIT_REJECTED.message());
        }

        @Test
        void transfer_leavesFailureDetailEmpty_whenCompensationSucceeds() {
            accounts.failCreditFor(destination, DESTINATION_CREDIT_REJECTED);

            transferService.transfer(source, destination, usd("100.00")).await();

            assertThat(latestSummary().failureDetail()
                                      .isPresent()).isFalse();
        }

        @Test
        void transfer_attemptsCompensatingCredit_whenCreditFails() {
            accounts.failCreditFor(destination, DESTINATION_CREDIT_REJECTED);

            transferService.transfer(source, destination, usd("100.00")).await();

            assertThat(accounts.creditedAccounts()).containsExactly(destination, source);
        }

        @Test
        void transfer_propagatesOriginalCreditFailure_whenCompensationSucceeds() {
            accounts.failCreditFor(destination, DESTINATION_CREDIT_REJECTED);

            transferService.transfer(source, destination, usd("100.00"))
                           .await()
                           .onSuccessRun(() -> fail("Expected failure"))
                           .onFailure(cause -> assertThat(cause.message()).isEqualTo(DESTINATION_CREDIT_REJECTED.message()));
        }

        @Test
        void transfer_propagatesOriginalCreditFailure_whenCompensationFails() {
            accounts.failCreditFor(destination, DESTINATION_CREDIT_REJECTED);
            accounts.failCreditFor(source, COMPENSATING_CREDIT_REJECTED);

            transferService.transfer(source, destination, usd("100.00"))
                           .await()
                           .onSuccessRun(() -> fail("Expected failure"))
                           .onFailure(cause -> assertThat(cause.message()).isEqualTo(DESTINATION_CREDIT_REJECTED.message()));
        }
    }

    @Nested
    class EarlyFailures {
        @Test
        void transfer_fails_whenDebitRejected() {
            accounts.failDebitFor(source, DEBIT_REJECTED);

            transferService.transfer(source, destination, usd("100.00"))
                           .await()
                           .onSuccessRun(() -> fail("Expected failure"))
                           .onFailure(cause -> assertThat(cause.message()).isEqualTo(DEBIT_REJECTED.message()));
        }

        @Test
        void transfer_recordsNothing_whenDebitRejected() {
            accounts.failDebitFor(source, DEBIT_REJECTED);

            transferService.transfer(source, destination, usd("100.00")).await();

            assertThat(recentTransfers()).isEmpty();
        }

        @Test
        void transfer_fails_forUnknownDestination() {
            transferService.transfer(source, AccountId.generate(), usd("100.00"))
                           .await()
                           .onSuccessRun(() -> fail("Expected failure"));
        }

        @Test
        void transfer_fails_whenFraudBlocksSelfTransfer() {
            transferService.transfer(source, source, usd("100.00"))
                           .await()
                           .onSuccessRun(() -> fail("Expected failure"))
                           .onFailure(cause -> assertThat(cause).isInstanceOf(TransferService.TransferError.FraudBlocked.class));
        }
    }

    private TransferStatus latestStatus() {
        return latestSummary().status();
    }

    private TransferSummary latestSummary() {
        var recorded = recentTransfers();

        assertThat(recorded).hasSize(1);

        return recorded.getFirst();
    }

    private List<TransferSummary> recentTransfers() {
        return transferService.recentTransfers(source, 10)
                              .await()
                              .unwrap();
    }
}
